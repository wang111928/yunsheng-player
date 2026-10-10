package com.litemusic.app.feature.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import com.litemusic.app.BuildConfig
import java.lang.ref.WeakReference

/**
 * Process-local bridge for the opt-in authorization probe. It observes an app return and lets
 * the already-live WebView re-check its NetEase cookies; callback parameters are never trusted.
 */
internal object AuthHandoffBridge {
    private const val TAG = "NmlAuthProbe"
    private val coordinator = AuthHandoffCoordinator()
    private var sourceWebView = WeakReference<WebView>(null)
    private var sourceDocumentUrl: String? = null

    fun beginQq(context: Context, webView: WebView, providerRequest: String): AuthHandoffStart? {
        val oldSource = sourceWebView.get()
        if (oldSource == null || oldSource !== webView || sourceDocumentUrl != webView.url) coordinator.cancel()
        val handoff = coordinator.begin(context.packageName, providerRequest) ?: return null
        if (handoff.duplicate) return handoff
        sourceWebView = WeakReference(webView)
        sourceDocumentUrl = webView.url
        logProviderUrl("qq", "outbound", providerRequest)
        diagnostic(context, "outbound", mapOf("exactRequest" to isExactQqNativeHandoffRequest(providerRequest)))
        if (BuildConfig.AUTH_HANDOFF_EXPERIMENT) runCatching {
            val outer = Uri.parse(providerRequest)
            val jump = Uri.parse(outer.getQueryParameter("p"))
            val nested = Uri.parse(jump.getQueryParameter("u1"))
            diagnostic(context, "request_shape", mapOf(
                "expectedJump" to (jump.scheme == "https" && jump.host == "ssl.ptlogin2.qq.com" && jump.path == "/jump"),
                "hasBlankOriginal" to jump.queryParameterNames.any { jump.getQueryParameter(it).isNullOrBlank() },
                "uniqueOriginalKeys" to jump.queryParameterNames.all { jump.getQueryParameters(it).size == 1 },
                "hasOpenLoginBinding" to (!jump.getQueryParameter("pt_openlogin_data").isNullOrBlank() || !jump.getQueryParameter("openlogin_data").isNullOrBlank()),
                "connectRoot" to (nested.scheme == "https" && nested.host == "connect.qq.com" && (nested.path.isNullOrEmpty() || nested.path == "/") && nested.query.isNullOrEmpty()),
                "graphAuthorize" to (nested.scheme == "https" && nested.host == "graph.qq.com" && nested.path == "/oauth2.0/authorize"),
            ))
        }
        return handoff
    }

    /** Legacy custom-scheme entry; the coordinator rejects these callbacks. */
    fun captureCallback(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val callback = intent.data ?: return false
        if (sourceWebView.get() == null) {
            coordinator.cancel()
            logCallback("qq", "source_not_live", AuthHandoffCallbackShape(false, false, emptySet(), 0, false), allowed = false)
            return false
        }
        val shape = coordinator.callbackShape(callback.toString())
        val capture = coordinator.capture(callback.toString())
        logCallback("qq", if (capture == null) "rejected" else "accepted", shape, allowed = capture != null)
        return capture != null
    }

    /** Restore only the live original QQ document with a session-matched official HTTPS jump. */
    fun captureHttpsReturn(intent: Intent?, context: Context? = null): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val view = sourceWebView.get()
        val diagnosticContext = context ?: view?.context
        if (diagnosticContext != null) diagnostic(diagnosticContext, "return", mapOf(
            "sourceLive" to (view != null),
            "sourceUnchanged" to (view != null && view.url == sourceDocumentUrl),
            "trustedUi" to isQqProviderUiUrl(view?.url.orEmpty()),
            "pending" to coordinator.hasPending(),
        ))
        if (view == null || view.url != sourceDocumentUrl || !isQqProviderUiUrl(view.url.orEmpty())) {
            cancel()
            return false
        }
        val candidate = intent.data?.toString() ?: return false
        val acceptedUrl = coordinator.captureHttpsReturn(candidate) ?: run {
            if (diagnosticContext != null) diagnostic(diagnosticContext, "return_unmatched", emptyMap())
            return false
        }
        sourceWebView = WeakReference(null)
        sourceDocumentUrl = null
        return runCatching {
            view.loadUrl(acceptedUrl)
            if (diagnosticContext != null) diagnostic(diagnosticContext, "restored", emptyMap())
            Log.w(TAG, "provider=qq stage=https_return restored=true")
            true
        }.getOrDefault(false)
    }

    fun release(webView: WebView) {
        if (sourceWebView.get() === webView) {
            sourceWebView = WeakReference(null)
            sourceDocumentUrl = null
            coordinator.cancel()
        }
    }

    fun cancel() {
        sourceWebView = WeakReference(null)
        sourceDocumentUrl = null
        coordinator.cancel()
    }

    /** Isolated test evidence only; no URL, cookie, provider token, or account is persisted. */
    private fun diagnostic(context: Context, stage: String, fields: Map<String, Boolean>) {
        if (!BuildConfig.AUTH_HANDOFF_EXPERIMENT) return
        runCatching {
            val line = org.json.JSONObject(fields).put("stage", stage).toString() + "\n"
            java.io.File(context.cacheDir, "auth-return-diagnostic.jsonl").appendText(line)
        }
    }

    private fun logProviderUrl(provider: String, stage: String, url: String) {
        val payloadLength = Uri.parse(url).getQueryParameter("p")?.length ?: 0
        Log.i(TAG, "provider=$provider stage=$stage exactRequest=${isExactQqNativeHandoffRequest(url)} pLength=$payloadLength")
    }

    private fun logCallback(provider: String, stage: String, shape: AuthHandoffCallbackShape, allowed: Boolean) {
        val keys = shape.queryKeys.sorted().joinToString(",")
        Log.i(TAG, "provider=$provider stage=$stage schemeMatches=${shape.schemeMatches} routeMatches=${shape.routeMatches} keys=$keys unknownKeyCount=${shape.unknownKeyCount} hasUrl=${shape.hasUrlParameter} allowed=$allowed")
    }
}
