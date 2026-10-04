package com.litemusic.app.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginWebAuthRouteTest {
    @Test fun qqEntryKeepsTheOfficialOauthFlowInDesktopWebMode() {
        val route = loginWebAuthConfig(LoginWebAuthRoute.QQ_OAUTH)

        assertEquals(LoginWebAuthRoute.QQ_OAUTH, route.kind)
        assertEquals(OFFICIAL_QR_USER_AGENT, route.userAgent)
        assertEquals(OFFICIAL_LOGIN_URL, route.entryUrl)
        assertTrue(route.openOtherLoginOptions)
    }

    @Test fun officialQrRouteLoadsTheSameOfficialComponentWithoutOpeningOptions() {
        val route = loginWebAuthConfig(LoginWebAuthRoute.OFFICIAL_QR)

        assertEquals(OFFICIAL_LOGIN_URL, route.entryUrl)
        assertFalse(route.openOtherLoginOptions)
    }

    @Test fun qqLoginUiPagesAreNormalizedToDisableNativeOneKeyHandoffs() {
        val url = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=100495085"

        assertTrue(normalizedQqWebLoginUrl(url).contains("pt_no_onekey=1"))
        assertEquals(
            "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=100495085&pt_no_onekey=1",
            normalizedQqWebLoginUrl(url),
        )
    }

    @Test fun authHandoffProbeLeavesTheOfficialQqUiUrlUntouched() {
        val url = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=100495085"

        assertTrue(normalizedQqWebLoginUrl(url).contains("pt_no_onekey=1"))
        assertEquals(url, normalizedQqWebLoginUrl(url, allowNativeHandoff = true))
    }

    @Test fun qqCallbackAndLookalikeHostAreNeverRewritten() {
        val signedCallback = "https://ssl.ptlogin2.qq.com/jump?sig=must-not-change"
        val lookalike = "https://evilptlogin2.qq.com/cgi-bin/xlogin?appid=1"

        assertEquals(signedCallback, normalizedQqWebLoginUrl(signedCallback))
        assertEquals(lookalike, normalizedQqWebLoginUrl(lookalike))
    }

    @Test fun nativeQqHandoffIsKeptInsideTheExistingWebLoginPage() {
        assertTrue(keepLoginNavigationInWebView("wtloginmqq://ptlogin/qlogin?appid=1"))
        assertTrue(keepLoginNavigationInWebView("intent://ptlogin#Intent;scheme=wtloginmqq;end"))
        assertFalse(keepLoginNavigationInWebView("https://music.163.com/#/login"))
        assertFalse(keepLoginNavigationInWebView("https://example.com/?scheme=wtloginmqq"))
    }

    @Test fun popupUsesTheParentDesktopUserAgent() {
        assertEquals(OFFICIAL_QR_USER_AGENT, popupLoginUserAgent(OFFICIAL_QR_USER_AGENT))
    }

    @Test fun authHandoffProbeUsesAMobileUserAgentOnlyForItsPopup() {
        assertEquals(OFFICIAL_QR_USER_AGENT, popupLoginUserAgent(OFFICIAL_QR_USER_AGENT, authHandoffExperiment = false))
        assertTrue(popupLoginUserAgent(OFFICIAL_QR_USER_AGENT, authHandoffExperiment = true).contains("Android"))
    }

    @Test fun authHandoffProbePreservesTheProviderGeneratedQqPayloadByteForByte() {
        val nativeRequest = "wtloginmqq://ptlogin/qlogin?p=provider-generated%2Fjump%3Fu1%3Dopaque"

        assertEquals(nativeRequest, normalizedQqWebLoginUrl(nativeRequest, allowNativeHandoff = true))
        assertFalse(keepLoginNavigationInWebView(nativeRequest, allowNativeHandoff = true))
    }

    @Test fun authHandoffProbeOnlyAllowsTheExactQqNativeEndpoint() {
        assertTrue(keepLoginNavigationInWebView("wtloginmqq://ptlogin/qlogin?p=opaque", allowNativeHandoff = false))
        assertTrue(keepLoginNavigationInWebView("wtloginmqq://ptlogin/not-qlogin?p=opaque", allowNativeHandoff = true))
        assertTrue(keepLoginNavigationInWebView("wtloginmqq://other/qlogin?p=opaque", allowNativeHandoff = true))
        assertTrue(keepLoginNavigationInWebView("intent://ptlogin/not-qlogin#Intent;scheme=wtloginmqq;end", allowNativeHandoff = true))
        assertFalse(keepLoginNavigationInWebView("intent://ptlogin/qlogin#Intent;scheme=wtloginmqq;end", allowNativeHandoff = true))
    }
}
