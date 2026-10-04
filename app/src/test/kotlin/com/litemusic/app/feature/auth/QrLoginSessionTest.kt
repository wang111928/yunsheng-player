package com.litemusic.app.feature.auth

import com.litemusic.shared.model.QrCheckResponse
import com.litemusic.shared.model.QrKeyResponse
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QrLoginSessionTest {
    private class Gateway : QrLoginGateway {
        var requests = 0
        var checks = 0
        var code = 800
        override suspend fun requestKey(): AppResult<QrKeyResponse> {
            requests++
            return AppResult.Success(QrKeyResponse(code = 200, unikey = "key-$requests"))
        }
        override suspend fun check(key: String): AppResult<QrCheckResponse> {
            checks++
            return AppResult.Success(QrCheckResponse(code = code))
        }
    }

    @Test fun immediateDispatcherPublishesQrAndExpiryStopsPolling() = runTest {
        val gateway = Gateway()
        val states = mutableListOf<QrSessionState>()
        val session = QrLoginSession(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), gateway,
            states::add, { _, _ -> QrApproval.SUCCESS }, pollIntervalMs = 10)
        session.start()
        assertEquals(QrSessionStatus.WAIT_SCAN, states.last().status)
        advanceTimeBy(11)
        runCurrent()
        assertEquals(QrSessionStatus.EXPIRED, states.last().status)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, gateway.checks)
    }

    @Test fun refreshCancelsPreviousPollAndLeavingModeStopsAllChecks() = runTest {
        val gateway = Gateway().apply { code = 801 }
        val states = mutableListOf<QrSessionState>()
        val session = QrLoginSession(backgroundScope, gateway, states::add,
            { _, _ -> QrApproval.SUCCESS }, pollIntervalMs = 10)
        session.start(); runCurrent()
        session.start(); runCurrent()
        assertTrue(states.last().qrContent.endsWith("key-2"))
        advanceTimeBy(11); runCurrent()
        assertEquals(1, gateway.checks)
        session.stop()
        advanceTimeBy(100); runCurrent()
        assertEquals(1, gateway.checks)
        assertFalse(states.last().busy)
    }

    @Test fun lateKeyFromCancelledGenerationCannotReplaceTheRefreshedQr() = runTest {
        val oldKey = CompletableDeferred<AppResult<QrKeyResponse>>()
        var requested = 0
        val gateway = object : QrLoginGateway {
            override suspend fun requestKey(): AppResult<QrKeyResponse> = when (++requested) {
                1 -> withContext(NonCancellable) { oldKey.await() }
                else -> AppResult.Success(QrKeyResponse(code = 200, unikey = "new-key"))
            }

            override suspend fun check(key: String): AppResult<QrCheckResponse> =
                AppResult.Success(QrCheckResponse(code = 801))
        }
        val states = mutableListOf<QrSessionState>()
        val session = QrLoginSession(backgroundScope, gateway, states::add,
            { _, _ -> QrApproval.SUCCESS }, pollIntervalMs = 10)

        session.start(); runCurrent() // first request is now deliberately NonCancellable
        session.start(); runCurrent()
        assertTrue(states.last().qrContent.endsWith("new-key"))

        oldKey.complete(AppResult.Success(QrKeyResponse(code = 200, unikey = "old-key")))
        runCurrent()

        assertEquals("https://music.163.com/login?codekey=new-key", states.last().qrContent)
        assertFalse(states.any { it.qrContent.endsWith("old-key") })
        session.stop()
    }

    @Test fun lateApprovalAfterRefreshAndStopCannotPublishSuccess() = runTest {
        val approvalStarted = CompletableDeferred<Unit>()
        val oldApproval = CompletableDeferred<QrApproval>()
        var requested = 0
        val gateway = object : QrLoginGateway {
            override suspend fun requestKey(): AppResult<QrKeyResponse> =
                AppResult.Success(QrKeyResponse(code = 200, unikey = "key-${++requested}"))

            override suspend fun check(key: String): AppResult<QrCheckResponse> = when (key) {
                "key-1" -> AppResult.Success(QrCheckResponse(code = 803, cookie = "MUSIC_U=old"))
                else -> AppResult.Success(QrCheckResponse(code = 801))
            }
        }
        val states = mutableListOf<QrSessionState>()
        val session = QrLoginSession(backgroundScope, gateway, states::add,
            { _, _ ->
                approvalStarted.complete(Unit)
                withContext(NonCancellable) { oldApproval.await() }
            }, pollIntervalMs = 10)

        session.start(); runCurrent()
        advanceTimeBy(11); runCurrent()
        approvalStarted.await()

        session.start(); runCurrent()
        assertTrue(states.last().qrContent.endsWith("key-2"))
        session.stop()
        val statesBeforeLateApproval = states.toList()

        oldApproval.complete(QrApproval.SUCCESS)
        runCurrent()

        assertEquals(statesBeforeLateApproval, states)
        assertFalse(states.any { it.status == QrSessionStatus.SUCCESS })
        assertFalse(states.last().busy)
    }
}
