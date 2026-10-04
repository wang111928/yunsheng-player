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
        nonceFactory = { "test-nonce" },
    )

    @Test fun preservesQqPayloadAndAddsOnlyTheOuterCallbackParameter() {
        val original = "wtloginmqq://ptlogin/qlogin?p=https%3A%2F%2Fssl.ptlogin2.qq.com%2Fjump%3Fu1%3Dopaque%2526state"

        val handoff = coordinator.begin("com.example.authprobe", original)?.handoffUrl

        assertNotNull(handoff)
        assertTrue(handoff!!.startsWith("$original&schemacallback="))
        assertTrue(handoff.contains("com.example.authprobe.auth%3A%2F%2Fauth%2Fqq%3Fnonce%3Dtest-nonce%26url%3D"))
    }

    @Test fun onlyAcceptsTheExactLiveCallbackOnceBeforeExpiry() {
        coordinator.begin("com.example.authprobe", "wtloginmqq://ptlogin/qlogin?p=opaque")

        assertNull(coordinator.capture("com.example.authprobe.auth://auth/not-qq?nonce=test-nonce"))
        assertNull(coordinator.capture("com.example.authprobe.auth://auth/qq?nonce=wrong"))
        assertNull(coordinator.capture("com.example.authprobe.auth://auth/qq?nonce=test-nonce&nonce=test-nonce"))
        val capture = coordinator.capture("com.example.authprobe.auth://auth/qq?nonce=test-nonce&url=https%3A%2F%2Fssl.ptlogin2.qq.com%2Fjump")
        assertNotNull(capture)
        assertEquals(setOf("nonce", "url"), capture!!.queryKeys)
        assertTrue(capture.hasUrlParameter)
        assertNull(coordinator.capture("com.example.authprobe.auth://auth/qq?nonce=test-nonce"))
    }

    @Test fun expiresThePendingHandoffInsteadOfAcceptingALateCallback() {
        coordinator.begin("com.example.authprobe", "wtloginmqq://ptlogin/qlogin?p=opaque")
        now += AUTH_HANDOFF_TTL_MILLIS + 1

        assertNull(coordinator.capture("com.example.authprobe.auth://auth/qq?nonce=test-nonce"))
        assertFalse(coordinator.hasPending())
    }

    @Test fun ignoresTheSameProviderRequestWhileTheFirstHandoffIsLive() {
        val request = "wtloginmqq://ptlogin/qlogin?p=opaque"
        assertNotNull(coordinator.begin("com.example.authprobe", request)?.handoffUrl)

        val duplicate = coordinator.begin("com.example.authprobe", request)

        assertTrue(duplicate!!.duplicate)
        assertNull(duplicate.handoffUrl)
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
