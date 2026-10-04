package com.litemusic.app.feature.auth

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqHttpsReturnMatcherTest {
    private val authorize = "https://connect.qq.com/"
    private val jump = "https://ssl.ptlogin2.qq.com/jump?u1=${encode(authorize)}&openlogin_data=opaque-session&client_id=client&state=opaque&redirect_uri=https%3A%2F%2Fmusic.163.com"
    private val providerRequest = "wtloginmqq://ptlogin/qlogin?p=${encode(jump)}"
    private val intentProviderRequest = "intent://ptlogin/qlogin?p=${encode(jump)}#Intent;scheme=wtloginmqq;end"
    private val validReturn = "$jump&keyindex=1&clientuin=opaque-user&clientkey=opaque-key"

    @Test fun acceptsAProviderReturnThatPreservesEveryPendingJumpParameter() {
        assertTrue(QqHttpsReturnMatcher.matches(providerRequest, validReturn))
    }

    @Test fun acceptsExactIntentWrappedProviderRequest() {
        assertTrue(QqHttpsReturnMatcher.matches(intentProviderRequest, validReturn))
    }

    @Test fun rejectsIntentWrapperWithWrongNativeScheme() {
        val wrongScheme = intentProviderRequest.replace("scheme=wtloginmqq", "scheme=other")
        assertFalse(QqHttpsReturnMatcher.matches(wrongScheme, validReturn))
    }

    @Test fun rejectsDifferentOriginPathAndAnyCoreParameterChange() {
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, validReturn.replace("ssl.ptlogin2.qq.com", "evil.example")))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, validReturn.replace("client_id=client", "client_id=other")))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, validReturn.replace("state=opaque", "state=other")))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, validReturn.replace("redirect_uri=https%3A%2F%2Fmusic.163.com", "redirect_uri=https%3A%2F%2Fevil.example")))
    }

    @Test fun rejectsDuplicateMissingOrInvalidNestedParameters() {
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, "$validReturn&state=opaque"))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, validReturn.replace("&state=opaque", "")))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, validReturn.replace(encode(authorize), encode("https://connect.qq.com/not-authorize"))))
    }

    @Test fun rejectsUnsafeOrNonCanonicalJumpUris() {
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, validReturn.replace("https://ssl.ptlogin2.qq.com", "https://user@ssl.ptlogin2.qq.com")))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, validReturn.replace("ssl.ptlogin2.qq.com/jump", "ssl.ptlogin2.qq.com:443/jump")))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, "$validReturn#fragment"))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, validReturn.replace("/jump?", "/other?")))
    }

    @Test fun rejectsOversizedAndMalformedUrls() {
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, "https://ssl.ptlogin2.qq.com/jump?u1=%ZZ"))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, "https://ssl.ptlogin2.qq.com/jump?u1=${"x".repeat(9_000)}"))
    }

    @Test fun rejectsUnknownOrEmptyNativeFieldsAndMissingOpenLoginBinding() {
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, "$validReturn&unknown=value"))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, validReturn.replace("clientkey=opaque-key", "clientkey=")))
        assertFalse(QqHttpsReturnMatcher.matches(providerRequest, "$validReturn&keyindex=2"))
        val withoutBinding = "wtloginmqq://ptlogin/qlogin?p=${encode(jump.replace("openlogin_data=opaque-session&", ""))}"
        assertFalse(QqHttpsReturnMatcher.matches(withoutBinding, validReturn))
    }

    @Test fun acceptsReorderedAndPercentEquivalentProviderQuery() {
        val candidate = "https://ssl.ptlogin2.qq.com/jump?state=opaque&u1=${encode(authorize)}&openlogin_data=opaque%2Dsession&redirect_uri=https%3A%2F%2Fmusic.163.com&client_id=client&clientkey=opaque-key&clientuin=opaque-user&keyindex=1"
        assertTrue(QqHttpsReturnMatcher.matches(providerRequest, candidate))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
