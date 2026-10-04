package com.litemusic.app.feature.auth

import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.QrCheckResponse
import com.litemusic.shared.model.QrKeyResponse
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The two official QR endpoints used by one cancellable login session. */
internal interface QrLoginGateway {
    suspend fun requestKey(): AppResult<QrKeyResponse>
    suspend fun check(key: String): AppResult<QrCheckResponse>
}

internal class NmQrLoginGateway(private val api: NMApi) : QrLoginGateway {
    override suspend fun requestKey() = api.qrKey()
    override suspend fun check(key: String) = api.qrCheck(key)
}

internal enum class QrSessionStatus { INIT, WAIT_SCAN, SCANNED, SUCCESS, EXPIRED, ERROR }

internal data class QrSessionState(
    val status: QrSessionStatus = QrSessionStatus.INIT,
    val qrContent: String = "",
    val message: String = "",
    val busy: Boolean = false,
)

internal enum class QrApproval { SUCCESS, PROFILE_FAILED, CANCELLED }

/**
 * Owns one QR generation request and its poll loop. Starting another session invalidates every
 * late result from the previous one, including a confirmation whose profile lookup was pending.
 */
internal class QrLoginSession(
    private val scope: CoroutineScope,
    private val gateway: QrLoginGateway,
    private val onState: (QrSessionState) -> Unit,
    private val onApproved: suspend (SessionToken, String) -> QrApproval,
    private val pollIntervalMs: Long = 2_000L,
) {
    internal data class SessionToken internal constructor(val value: Long)

    @Volatile private var generation = 0L
    private var job: Job? = null

    fun start() {
        val token = SessionToken(++generation)
        job?.cancel()
        job = scope.launch(start = CoroutineStart.LAZY) {
            emitIfCurrent(token, QrSessionState(message = "正在获取二维码…", busy = true))
            try {
                val keyResult = gateway.requestKey()
                if (!isCurrent(token)) return@launch
                val key = (keyResult as? AppResult.Success)?.data?.let { it.unikey.ifBlank { it.data?.unikey.orEmpty() } }
                if (key.isNullOrBlank()) {
                    val detail = (keyResult as? AppResult.Failure)?.message.orEmpty()
                    emitIfCurrent(token, QrSessionState(QrSessionStatus.ERROR, message = "二维码获取失败${detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"))
                    return@launch
                }
                emitIfCurrent(token, QrSessionState(
                    status = QrSessionStatus.WAIT_SCAN,
                    qrContent = "https://music.163.com/login?codekey=$key",
                    message = "请使用网易云音乐 App 扫码",
                ))
                poll(token, key)
            } finally {
                if (token.value == generation) {
                    lastState = lastState.copy(busy = false)
                    onState(lastState)
                }
            }
        }
        job?.start()
    }

    private var lastState = QrSessionState()

    fun stop() {
        generation += 1
        job?.cancel()
        job = null
        onState(lastState.copy(busy = false))
    }

    fun isCurrent(token: SessionToken): Boolean = token.value == generation && job?.isActive == true

    private fun emitIfCurrent(token: SessionToken, next: QrSessionState) {
        if (isCurrent(token)) {
            lastState = next
            onState(next)
        }
    }

    private suspend fun poll(token: SessionToken, key: String) {
        var waited = 0L
        while (isCurrent(token)) {
            delay(pollIntervalMs)
            waited += pollIntervalMs
            if (!isCurrent(token)) return
            when (val result = gateway.check(key)) {
                is AppResult.Success -> when (result.data.code) {
                    800 -> {
                        emitIfCurrent(token, QrSessionState(QrSessionStatus.EXPIRED, message = "二维码已失效，请重新获取"))
                        return
                    }
                    801 -> Unit
                    802 -> emitIfCurrent(token, lastState.copy(status = QrSessionStatus.SCANNED, message = "已扫描，请在手机上确认"))
                    803 -> {
                        if (result.data.cookie.isBlank()) {
                            emitIfCurrent(token, QrSessionState(QrSessionStatus.ERROR, message = "扫码已确认，但接口未返回登录凭据"))
                        } else {
                            when (onApproved(token, result.data.cookie)) {
                                QrApproval.SUCCESS -> emitIfCurrent(token, QrSessionState(QrSessionStatus.SUCCESS, message = "登录成功"))
                                QrApproval.PROFILE_FAILED -> emitIfCurrent(token, QrSessionState(QrSessionStatus.ERROR, message = "扫码已确认，但账号资料读取失败，请重试"))
                                QrApproval.CANCELLED -> Unit
                            }
                        }
                        return
                    }
                    else -> {
                        emitIfCurrent(token, QrSessionState(QrSessionStatus.ERROR, message = "扫码状态异常 (${result.data.code})"))
                        return
                    }
                }
                is AppResult.Failure -> {
                    emitIfCurrent(token, QrSessionState(QrSessionStatus.ERROR, message = "扫码状态读取失败：${result.message}"))
                    return
                }
            }
            if (waited >= 5 * 60 * 1_000L) {
                emitIfCurrent(token, QrSessionState(QrSessionStatus.EXPIRED, message = "二维码已失效，请重新获取"))
                return
            }
        }
    }
}
