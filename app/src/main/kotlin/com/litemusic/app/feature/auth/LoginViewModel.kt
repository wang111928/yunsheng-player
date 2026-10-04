package com.litemusic.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.AuthRepository
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * 登录：WebView 官方登录页获取 Cookie（主推，长期有效）
 * + 扫码登录（兜底，新号可能需要短信验证）。
 */
class LoginViewModel(
    private val auth: AuthRepository,
    private val api: NMApi,
) : ViewModel() {

    enum class QrStatus { INIT, WAIT_SCAN, SCANNED, SUCCESS, EXPIRED, ERROR }

    data class QrUiState(
        val status: QrStatus = QrStatus.INIT,
        /** 二维码内容（codekey URL），由 UI 用 QrCodeUtil 本地渲染成图片 */
        val qrContent: String = "",
        val message: String = "",
    )

    private val _qr = MutableStateFlow(QrUiState())
    val qr: StateFlow<QrUiState> = _qr.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    private val webLoginMutex = Mutex()
    @Volatile private var webGeneration = 0L
    private var completedCookies: Map<String, String>? = null
    private var activeWebCookies: Map<String, String>? = null
    private var failedCookies: Map<String, String>? = null

    private val qrSession = QrLoginSession(
        scope = viewModelScope,
        gateway = NmQrLoginGateway(api),
        onState = { state ->
            _busy.value = state.busy
            _qr.value = QrUiState(
                status = QrStatus.valueOf(state.status.name),
                qrContent = state.qrContent,
                message = state.message,
            )
        },
        onApproved = ::completeQrApproval,
    )

    /** WebView 页面加载后调用：从 CookieManager 提取关键 Cookie */
    suspend fun onPageLoaded(cookies: Map<String, String>): Boolean {
        if (cookies["MUSIC_U"].isNullOrBlank() && cookies["NTES_YD_SESS"].isNullOrBlank()) return false
        if (completedCookies == cookies) return true
        if (failedCookies == cookies) return false
        if (activeWebCookies == cookies && webLoginMutex.isLocked) return false
        if (activeWebCookies != cookies) {
            activeWebCookies = cookies
            webGeneration++
        }
        val generation = webGeneration
        return webLoginMutex.withLock {
            val context = currentCoroutineContext()
            if (generation != webGeneration) return@withLock false
            val result = withContext(Dispatchers.IO) {
                auth.completeLogin(cookies) { context.isActive && generation == webGeneration }
            }
            if (result is AppResult.Success) {
                completedCookies = cookies
                _toast.value = null
                true
            } else {
                if (generation == webGeneration) {
                    failedCookies = cookies.toMap()
                    _toast.value = (result as AppResult.Failure).message
                }
                false
            }
        }
    }

    fun startQr() {
        webGeneration++
        qrSession.start()
    }

    /** Leaving QR mode must stop its active poll before a web login takes over. */
    fun stopQr() {
        webGeneration++
        activeWebCookies = null
        failedCookies = null
        _toast.value = null
        qrSession.stop()
    }

    fun retryWebLogin() {
        webGeneration++
        activeWebCookies = null
        failedCookies = null
        _toast.value = null
    }

    private suspend fun completeQrApproval(token: QrLoginSession.SessionToken, cookie: String): QrApproval {
        if (!qrSession.isCurrent(token)) return QrApproval.CANCELLED
        val profile = withContext(Dispatchers.IO) {
            auth.completeLogin(parseCookieHeader(cookie)) { qrSession.isCurrent(token) }
        }
        return when {
            !qrSession.isCurrent(token) -> QrApproval.CANCELLED
            profile is AppResult.Success -> QrApproval.SUCCESS
            else -> QrApproval.PROFILE_FAILED
        }
    }

    private fun parseCookieHeader(header: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        header.split(";").forEach { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && (kv[0] == "MUSIC_U" || kv[0] == "__csrf" || kv[0] == "NTES_YD_SESS")) {
                map[kv[0]] = kv[1]
            }
        }
        return map
    }

    override fun onCleared() {
        qrSession.stop()
    }
}
