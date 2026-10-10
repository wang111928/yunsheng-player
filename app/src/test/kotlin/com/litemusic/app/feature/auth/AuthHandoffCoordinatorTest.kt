package com.litemusic.app.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthHandoffCoordinatorTest {
    private var now = 1_000L
    private val coordinator = AuthHandoffCoordinator(
        nowMillis = { now },
    )

    @Test fun removesBrowserCallbackPrefixesWithoutChangingQqPayloadOrOtherFields() {
        val original = "wtloginmqq://ptlogin/qlogin?p=https%3A%2F%2Fssl.ptlogin2.qq.com%2Fjump%3Fu1%3Dopaque%2526state&foo=first&schemacallback=chrome%3A%2F%2Fcallback&foo=second&schemacallback=old"

        val handoff = coordinator.begin("com.example.authprobe", original)?.handoffUrl

        assertEquals(
            "wtloginmqq://ptlogin/qlogin?p=https%3A%2F%2Fssl.ptlogin2.qq.com%2Fjump%3Fu1%3Dopaque%2526state&foo=first&foo=second",
            handoff,
        )
    }

    @Test fun doesNotTreatAnApplicationSchemeAsAProviderReturn() {
        coordinator.begin("com.example.authprobe", "wtloginmqq://ptlogin/qlogin?p=opaque")

        assertNull(coordinator.capture("com.example.authprobe.auth://auth/qq?nonce=test-nonce&url=https%3A%2F%2Fssl.ptlogin2.qq.com%2Fjump"))
        assertTrue(coordinator.hasPending())
    }

    @Test fun expiresThePendingHandoffInsteadOfAcceptingALateCallback() {
        val jump = "https://ssl.ptlogin2.qq.com/jump?u1=https%3A%2F%2Fconnect.qq.com%2F&openlogin_data=opaque-session"
        coordinator.begin("com.example.authprobe", "wtloginmqq://ptlogin/qlogin?p=" + java.net.URLEncoder.encode(jump, java.nio.charset.StandardCharsets.UTF_8))
        now += AUTH_HANDOFF_TTL_MILLIS + 1

        assertNull(coordinator.captureHttpsReturn(jump))
        assertFalse(coordinator.hasPending())
    }

    @Test fun ignoresTheSameProviderRequestWhileTheFirstHandoffIsLive() {
        val request = "wtloginmqq://ptlogin/qlogin?p=opaque"
        assertNotNull(coordinator.begin("com.example.authprobe", request)?.handoffUrl)

        val duplicate = coordinator.begin("com.example.authprobe", request)

        assertTrue(duplicate!!.duplicate)
        assertNull(duplicate.handoffUrl)
    }

    @Test fun sameProviderRequestCanRetryAfterTheShortGestureDeduplicationWindow() {
        val request = "wtloginmqq://ptlogin/qlogin?p=opaque"
        coordinator.begin("com.example.authprobe", request)
        now += 2_000L

        val retry = coordinator.begin("com.example.authprobe", request)

        assertFalse(retry!!.duplicate)
        assertEquals(request, retry.handoffUrl)
    }

    @Test fun cancellingAnUnfinishedHandoffAllowsAnImmediateRetry() {
        val request = "wtloginmqq://ptlogin/qlogin?p=opaque"
        coordinator.begin("com.example.authprobe", request)
        coordinator.cancel()

        val retry = coordinator.begin("com.example.authprobe", request)

        assertFalse(retry!!.duplicate)
        assertEquals(request, retry.handoffUrl)
    }

    @Test fun httpsReturnRequiresMatchingLiveRequestAndConsumesItOnce() {
        val jump = "https://ssl.ptlogin2.qq.com/jump?u1=https%3A%2F%2Fconnect.qq.com%2F&openlogin_data=opaque-session"
        val request = "wtloginmqq://ptlogin/qlogin?p=" + java.net.URLEncoder.encode(jump, java.nio.charset.StandardCharsets.UTF_8)
        coordinator.begin("com.example.authprobe", request)
        assertNull(coordinator.captureHttpsReturn(jump.replace("opaque-session", "other-session")))
        assertNull(coordinator.captureHttpsReturn(jump.replace("ssl.ptlogin2.qq.com", "untrusted.example")))
        val callback = "$jump&keyindex=opaque-index&clientuin=opaque-account&clientkey=opaque-provider-ticket"
        assertEquals(callback, coordinator.captureHttpsReturn(callback))
        assertNull(coordinator.captureHttpsReturn(callback))
        assertFalse(coordinator.hasPending())
    }

    @Test fun httpsReturnWithoutLiveContextIsRejected() {
        val jump = "https://ssl.ptlogin2.qq.com/jump?u1=https%3A%2F%2Fconnect.qq.com%2F&openlogin_data=opaque-session"
        assertNull(coordinator.captureHttpsReturn(jump))
        coordinator.begin("com.example.authprobe", "wtloginmqq://ptlogin/qlogin?p=" + java.net.URLEncoder.encode(jump, java.nio.charset.StandardCharsets.UTF_8))
        now += AUTH_HANDOFF_TTL_MILLIS + 1
        assertNull(coordinator.captureHttpsReturn(jump))
    }

    @Test fun httpsReturnAlsoPreservesAnAcceptedIntentWrappedProviderRequest() {
        val jump = "https://ssl.ptlogin2.qq.com/jump?u1=https%3A%2F%2Fconnect.qq.com%2F&openlogin_data=opaque-session"
        val wrapped = "intent://ptlogin/qlogin?p=" + java.net.URLEncoder.encode(jump, java.nio.charset.StandardCharsets.UTF_8) +
            "#Intent;scheme=wtloginmqq;package=com.tencent.mobileqq;end"
        assertNotNull(coordinator.begin("com.example.authprobe", wrapped))
        val callback = "$jump&keyindex=opaque-index&clientuin=opaque-account&clientkey=opaque-provider-ticket"
        assertEquals(callback, coordinator.captureHttpsReturn(callback))
        assertNull(coordinator.captureHttpsReturn(callback))
    }
}
