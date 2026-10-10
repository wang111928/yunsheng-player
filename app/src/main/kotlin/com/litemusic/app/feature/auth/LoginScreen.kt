package com.litemusic.app.feature.auth

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.litemusic.design.components.NmlButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.litemusic.app.BuildConfig
import com.litemusic.app.ui.SystemBarAppearance
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.util.WeakHashMap

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(viewModel: LoginViewModel = koinViewModel()) {
    val loginError by viewModel.toast.collectAsState()
    val scope = rememberCoroutineScope()
    var officialQrReload by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    var nativeEnabled by remember { mutableStateOf(qqNativeHandoffEnabled(context)) }
    var loginRoute by remember { mutableStateOf(if (nativeEnabled) LoginWebAuthRoute.QQ_OAUTH else LoginWebAuthRoute.OFFICIAL_QR) }
    var showNativeHelp by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val allowed = qqNativeHandoffEnabled(context)
        if (allowed != nativeEnabled) {
            nativeEnabled = allowed
            loginRoute = if (allowed) LoginWebAuthRoute.QQ_OAUTH else LoginWebAuthRoute.OFFICIAL_QR
            officialQrReload += 1
        }
    }
    if (showNativeHelp) AlertDialog(
        onDismissRequest = { showNativeHelp = false },
        title = { Text("QQ 免截图授权") },
        text = { Text("在系统的“打开链接”中，允许云声打开 ssl.ptlogin2.qq.com，然后返回本页，从官网的其他登录方式选择 QQ 并确认授权。只有开启此链接后才能尝试直接返回云声。手机号、邮箱和官网扫码仍可正常使用。") },
        confirmButton = { TextButton(onClick = {
            showNativeHelp = false
            openAuthLinkSettings(context)
        }) { Text("打开链接设置") } },
        dismissButton = { TextButton(onClick = { showNativeHelp = false }) { Text("稍后") } },
    )

    SystemBarAppearance(darkIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Column(
                Modifier.fillMaxWidth().zIndex(1f)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Text(
                    "登录网易云音乐",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp),
                )
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    Button(onClick = {
                        // A failed cookie validation is cached by LoginViewModel.  Clear that
                        // attempt before deliberately returning to the official QR entry.
                        viewModel.retryWebLogin()
                        loginRoute = LoginWebAuthRoute.OFFICIAL_QR
                        officialQrReload += 1
                    }, modifier = Modifier.fillMaxWidth()) { Text("官方扫码") }
                }
                TextButton(onClick = { if (nativeEnabled) { loginRoute = LoginWebAuthRoute.QQ_OAUTH; officialQrReload += 1 } else showNativeHelp = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (nativeEnabled) "使用 QQ 直接授权" else "启用 QQ 免截图授权")
                }
                loginError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            }
            WebView(
                modifier = Modifier.weight(1f),
                loginRoute = loginRoute,
                officialQrReload = officialQrReload,
                onRetry = viewModel::retryWebLogin,
                onCookies = { cookies ->
                    scope.launch {
                        if (viewModel.onPageLoaded(cookies)) {
                            // 登录成功，AppRoot 会自动切换
                        }
                    }
                },
            )
        }
    }
}

internal const val OFFICIAL_LOGIN_URL = "https://music.163.com/#/login"

@Composable
private fun WebView(
    modifier: Modifier = Modifier,
    onCookies: (Map<String, String>) -> Unit,
    loginRoute: LoginWebAuthRoute,
    officialQrReload: Int,
    onRetry: () -> Unit,
) {
    val authConfig = remember(loginRoute) { loginWebAuthConfig(loginRoute) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentWebView by remember { mutableStateOf<WebView?>(null) }
    var webViewUnavailable by remember { mutableStateOf(false) }
    var webViewGeneration by remember { mutableStateOf(0) }
    var lastRenderProcessWebView by remember { mutableStateOf<WebView?>(null) }

    val latestOnCookies by rememberUpdatedState(onCookies)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(currentWebView, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                currentWebView?.checkLoginCookies(latestOnCookies)
                currentWebView?.evaluateJavascript("window.nmlQrEntryState || ''") { result ->
                    if (result == "\"unavailable\"") {
                        errorMessage = "官方扫码页面暂不可用，请点重试，或使用页面中的其他登录方式"
                    }
                }
                delay(2_000)
            }
        }
    }
    // An explicit tap on the top button deliberately returns to NetEase's QR view.
    // We do not reload while a QQ/phone/web OAuth flow is in progress, so its WebView
    // cookies and popup state survive app switches.
    key(webViewGeneration, loginRoute, officialQrReload) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.userAgentString = authConfig.userAgent
                    val lifecycleScript = configureLoginWebView(
                        userAgent = authConfig.userAgent,
                        openOtherLoginOptions = authConfig.openOtherLoginOptions,
                        onCookies = { latestOnCookies(it) },
                        onPageLoadingChanged = {
                            loading = it
                            if (it) errorMessage = null
                        },
                        onPageError = { errorMessage = it },
                        onRenderProcessGone = { goneView ->
                            lastRenderProcessWebView = goneView
                            if (currentWebView === goneView) currentWebView = null
                            webViewUnavailable = true
                            goneView.releaseLoginWebView()
                        },
                    )
                    if (lifecycleScript != null) qqLifecycleScriptHandlers[this] = lifecycleScript
                    if (qqNativeHandoffEnabled(ctx)) {
                        QqNativeHandoffBridge.install(this) { providerUri ->
                            tryQqHandoff(this, providerUri) { errorMessage = it }
                        }
                    }
                    currentWebView = this
                    loadUrl(authConfig.entryUrl)
                }
            },
            onRelease = { releasedWebView ->
                if (releasedWebView !== lastRenderProcessWebView) {
                    releasedWebView.releaseLoginWebView()
                }
                if (currentWebView === releasedWebView) currentWebView = null
            },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = {
            onRetry()
            if (webViewUnavailable) {
                webViewUnavailable = false
                webViewGeneration += 1
            } else {
                currentWebView?.let { view ->
                    if (view.canGoBack()) view.goBack() else view.loadUrl(authConfig.entryUrl)
                }
            }
        }) { Text("返回") }
        Spacer(Modifier.size(8.dp))
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("正在加载登录页…", modifier = Modifier.padding(start = 8.dp))
        }
        errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                onRetry()
                errorMessage = null
                if (webViewUnavailable) {
                    webViewUnavailable = false
                    webViewGeneration += 1
                } else {
                    currentWebView?.reload()
                }
            }) { Text("重试") }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureLoginWebView(
    userAgent: String,
    openOtherLoginOptions: Boolean,
    onCookies: (Map<String, String>) -> Unit,
    onPageLoadingChanged: (Boolean) -> Unit,
    onPageError: (String) -> Unit,
    onRenderProcessGone: (WebView) -> Unit,
): androidx.webkit.ScriptHandler? {
    if (BuildConfig.AUTH_HANDOFF_EXPERIMENT) WebView.setWebContentsDebuggingEnabled(true)
    settings.userAgentString = userAgent
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(true)

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureLoginWebView, true)
    }

    webViewClient = loginWebViewClient(
        onCookies = onCookies,
        onPageLoadingChanged = onPageLoadingChanged,
        onPageError = onPageError,
        onRenderProcessGone = onRenderProcessGone,
        openOtherLoginOptions = openOtherLoginOptions,
    )
    webChromeClient = LoginWebChromeClient(
        onCookies = onCookies,
        onPageError = onPageError,
    )
    return QqLoginLifecycleBridge.install(this)
}

private fun loginWebViewClient(
    onCookies: (Map<String, String>) -> Unit,
    onPageLoadingChanged: (Boolean) -> Unit,
    onPageError: (String) -> Unit,
    onRenderProcessGone: (WebView) -> Unit,
    openOtherLoginOptions: Boolean,
): WebViewClient = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handleNavigation(view, request.url, request.isForMainFrame, "request")

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handleNavigation(view, android.net.Uri.parse(url), true, "legacy")

    private fun handleNavigation(view: WebView, uri: android.net.Uri, mainFrame: Boolean, entry: String): Boolean {
        val url = uri.toString()
        if (BuildConfig.AUTH_HANDOFF_EXPERIMENT &&
            (uri.scheme.equals("wtloginmqq", true) || uri.scheme.equals("intent", true))
        ) {
            android.util.Log.i("NmlAuthProbe", "stage=navigation entry=$entry mainFrame=$mainFrame sourceTrusted=${isTrustedQqLoginSource(view.url)} exactQq=${isExactQqNativeHandoffRequest(url)}")
        }
        // Only the origin-restricted message bridge can authorize a native QQ handoff.
        // A late or subframe scheme navigation must not bypass its user-gesture window.
        // A native QQ request from an untrusted document must remain blocked even in the probe.
        if (keepLoginNavigationInWebView(url)) {
            onPageError("请用本页二维码或账号完成 QQ 登录")
            return true
        }
        if (BuildConfig.AUTH_HANDOFF_EXPERIMENT && uri.scheme.equals("sinaweibo", true)) {
            if (!isTrustedWeiboLoginSource(view.url) || uri.host != "browser" ||
                !uri.path.isNullOrEmpty() || uri.userInfo != null || uri.port != -1 ||
                uri.getQueryParameter("url").isNullOrBlank()
            ) return true
            view.evaluateJavascript("window.nmlWeiboHandoffIssued = true", null)
            if (weiboHandoffAttempts[view] == view.url) return true
            weiboHandoffAttempts[view] = view.url.orEmpty()
            if (!openAuthHandoff(view, url)) {
                val message = "未安装微博或无法打开授权，请使用当前页面的网页登录"
                Toast.makeText(view.context, message, Toast.LENGTH_LONG).show()
            }
            return true
        }
        if (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            val normalizedUrl = if (mainFrame) {
                normalizedQqWebLoginUrl(url, allowNativeHandoff = qqNativeHandoffEnabled(view.context))
            } else url
            if (normalizedUrl != url) {
                view.loadUrl(normalizedUrl)
                return true
            }
            return false
        }
        return try {
            val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else Intent(Intent.ACTION_VIEW, uri)
            val fallback = intent.getStringExtra("browser_fallback_url")
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            intent.removeExtra("browser_fallback_url")
            try {
                view.context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                if (fallback != null && android.net.Uri.parse(fallback).scheme.equals("https", true)) {
                    view.loadUrl(fallback)
                } else onPageError("无法打开授权应用，请安装对应应用，或使用授权页中的其他登录方式")
            }
            true
        } catch (_: Exception) {
            onPageError("授权跳转失败，请返回重试或改用官方扫码")
            true
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        weiboHandoffAttempts.remove(view)
        onPageLoadingChanged(true)
        view.checkLoginCookies(onCookies)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        onPageLoadingChanged(false)
        view.checkLoginCookies(onCookies)
        val uri = android.net.Uri.parse(url)
        if (BuildConfig.AUTH_HANDOFF_EXPERIMENT && isTrustedWeiboLoginSource(url)) {
            val script = view.context.assets.open("weibo_handoff_probe.js").bufferedReader().use { it.readText() }
            view.evaluateJavascript(script, null)
        }
        if (uri.host in setOf("graph.qq.com", "xui.ptlogin2.qq.com", "ui.ptlogin2.qq.com")) {
            // These pages use percentage heights. In an embedded WebView their root can stay
            // at 0px, clipping the one-key authorization control despite a loaded DOM.
            view.evaluateJavascript("""
                (function() {
                    document.documentElement.style.height = '100vh';
                    document.body.style.minHeight = '100vh';
                    document.body.style.height = 'auto';
                })();
            """.trimIndent(), null)
        }
        if (uri.host == "music.163.com" && uri.path == "/" && uri.fragment == "/login") {
            view.evaluateJavascript(officialQrEntryScript(openOtherLoginOptions)) { result ->
                if (result == "\"unavailable\"") onPageError("官方扫码页面暂不可用，请点重试，或使用页面中的其他登录方式")
            }
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            onPageLoadingChanged(false)
            onPageError("登录页加载失败：${error.description}")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request.isForMainFrame) {
            onPageLoadingChanged(false)
            onPageError("登录页请求失败（${errorResponse.statusCode}）")
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onPageLoadingChanged(false)
        onPageError("登录页进程已退出，请点重试重新打开")
        onRenderProcessGone(view)
        return true
    }
}

private class LoginWebChromeClient(
    private val onCookies: (Map<String, String>) -> Unit,
    private val onPageError: (String) -> Unit,
) : WebChromeClient() {
    private val popups = mutableMapOf<WebView, Dialog>()

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
        val popup = WebView(view.context)
        val lifecycleScript = popup.configureLoginWebView(
            userAgent = popupLoginUserAgent(
                view.settings.userAgentString ?: OFFICIAL_QR_USER_AGENT,
                authHandoffExperiment = qqNativeHandoffEnabled(view.context),
            ),
            openOtherLoginOptions = false,
            onCookies = onCookies,
            onPageLoadingChanged = {},
            onPageError = onPageError,
            onRenderProcessGone = { gonePopup ->
                val dialog = popups.remove(gonePopup)
                if (dialog != null) dialog.dismiss() else gonePopup.releaseLoginWebView()
            },
        )
        if (lifecycleScript != null) qqLifecycleScriptHandlers[popup] = lifecycleScript
        if (qqNativeHandoffEnabled(view.context)) {
            QqNativeHandoffBridge.install(popup) { providerUri ->
                tryQqHandoff(popup, providerUri, onPageError)
            }
        }
        val dialog = Dialog(view.context).apply {
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP && popup.canGoBack()) {
                    popup.goBack(); true
                } else false
            }
            setTitle("登录授权")
            setContentView(FrameLayout(view.context).apply {
                addView(
                    popup,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            })
            setOnDismissListener {
                popups.remove(popup)
                popup.releaseLoginWebView()
            }
        }
        popups[popup] = dialog
        val transport = resultMsg.obj as? WebView.WebViewTransport
        if (transport == null) {
            dialog.dismiss()
            onPageError("无法打开登录授权窗口")
            return false
        }
        transport.webView = popup
        resultMsg.sendToTarget()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            setLayout(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        return true
    }

    override fun onCloseWindow(window: WebView) {
        popups.remove(window)?.dismiss()
        super.onCloseWindow(window)
    }

    fun dispose() {
        popups.values.toList().forEach { it.dismiss() }
        popups.clear()
    }
}

private val qqLifecycleScriptHandlers = WeakHashMap<WebView, androidx.webkit.ScriptHandler>()
private val weiboHandoffAttempts = WeakHashMap<WebView, String>()

private fun WebView.releaseLoginWebView() {
    weiboHandoffAttempts.remove(this)
    AuthHandoffBridge.release(this)
    QqNativeHandoffBridge.remove(this)
    qqLifecycleScriptHandlers.remove(this)?.let(QqLoginLifecycleBridge::remove)
    (webChromeClient as? LoginWebChromeClient)?.dispose()
    stopLoading()
    webChromeClient = WebChromeClient()
    webViewClient = WebViewClient()
    destroy()
}

private fun tryQqHandoff(view: WebView, providerUri: String, onError: (String) -> Unit) {
    val handoff = AuthHandoffBridge.beginQq(view.context, view, providerUri)
    if (handoff?.duplicate == true) {
        android.util.Log.i("NmlAuthProbe", "provider=qq stage=duplicate")
        return
    }
    val opened = handoff?.handoffUrl?.let { openAuthHandoff(view, it) } == true
    android.util.Log.i("NmlAuthProbe", "provider=qq stage=launch opened=$opened")
    if (!opened) {
        AuthHandoffBridge.cancel()
        onError("无法打开 QQ 授权应用，请使用本页中的其他登录方式")
    }
}

private fun openAuthHandoff(view: WebView, handoffUrl: String): Boolean = try {
    val intent = (if (handoffUrl.startsWith("intent:", ignoreCase = true)) {
        Intent.parseUri(handoffUrl, Intent.URI_INTENT_SCHEME)
    } else Intent(Intent.ACTION_VIEW, android.net.Uri.parse(handoffUrl))).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        component = null
        selector = null
        removeExtra("browser_fallback_url")
        if (isExactQqNativeHandoffRequest(handoffUrl)) setPackage("com.tencent.mobileqq")
    }
    view.context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: Exception) {
    false
}

private fun isTrustedQqLoginSource(url: String?): Boolean {
    val uri = url?.let(android.net.Uri::parse) ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host in setOf("xui.ptlogin2.qq.com", "ui.ptlogin2.qq.com") &&
        uri.path in setOf("/cgi-bin/xlogin", "/login") && uri.userInfo == null && uri.port == -1
}

private fun isTrustedWeiboLoginSource(url: String?): Boolean {
    val uri = url?.let(android.net.Uri::parse) ?: return false
    return uri.scheme == "https" && uri.host == "api.weibo.com" &&
        uri.path == "/oauth2/authorize" && uri.userInfo == null && uri.port == -1
}

private fun WebView.checkLoginCookies(onCookies: (Map<String, String>) -> Unit) {
    val cookies = musicLoginCookies(CookieManager.getInstance().getCookie("https://music.163.com"))
    if (cookies.containsKey("MUSIC_U") || cookies.containsKey("NTES_YD_SESS")) {
        onCookies(cookies)
    }
}

internal fun musicLoginCookies(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val map = mutableMapOf<String, String>()
    raw.split(";").forEach { part ->
        val kv = part.trim().split("=", limit = 2)
        val name = kv.getOrNull(0) ?: return@forEach
        if (name == "MUSIC_U" || name == "__csrf" || name == "NTES_YD_SESS") {
            map[name] = kv.getOrNull(1) ?: ""
        }
    }
    return map
}
