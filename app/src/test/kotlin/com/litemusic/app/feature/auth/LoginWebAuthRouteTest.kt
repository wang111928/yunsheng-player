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

    @Test fun providerPopupKeepsTheOfficialDesktopUserAgentByDefault() {
        assertEquals(OFFICIAL_QR_USER_AGENT, popupLoginUserAgent(OFFICIAL_QR_USER_AGENT))
    }

    @Test fun navigationUsesMobileUaOnlyForTheTrustedQqLoginUiWhenNativeIsAllowed() {
        val qqUi = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=100495085"

        assertTrue(loginNavigationUserAgent(OFFICIAL_QR_USER_AGENT, qqUi, nativeAllowed = true).contains("Android"))
        assertEquals(OFFICIAL_QR_USER_AGENT, loginNavigationUserAgent(OFFICIAL_QR_USER_AGENT, qqUi, nativeAllowed = false))
    }

    @Test fun nativeNavigationUsesMobileUaForTheExactTopLevelQqAuthorizationPage() {
        val qrPage = "https://graph.qq.com/oauth2.0/show?which=Login&display=pc"
        val authorizePage = "https://graph.qq.com/oauth2.0/authorize?client_id=100495085"

        assertTrue(loginNavigationUserAgent(OFFICIAL_QR_USER_AGENT, qrPage, nativeAllowed = true).contains("Android"))
        assertTrue(loginNavigationUserAgent(OFFICIAL_QR_USER_AGENT, authorizePage, nativeAllowed = true).contains("Android"))
        assertEquals(
            OFFICIAL_QR_USER_AGENT,
            loginNavigationUserAgent(OFFICIAL_QR_USER_AGENT, qrPage, nativeAllowed = false),
        )
    }

    @Test fun qqProviderUiTrustRequiresTheExactHttpsHostsAndLoginPaths() {
        assertTrue(isQqProviderUiUrl("https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=1"))
        assertTrue(isQqProviderUiUrl("https://ui.ptlogin2.qq.com/cgi-bin/xlogin?appid=1"))

        assertFalse(isQqProviderUiUrl("http://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=1"))
        assertFalse(isQqProviderUiUrl("https://ptlogin2.qq.com/cgi-bin/xlogin?appid=1"))
        assertFalse(isQqProviderUiUrl("https://ssl.ptlogin2.qq.com/cgi-bin/xlogin?appid=1"))
        assertFalse(isQqProviderUiUrl("https://ui.ptlogin2.qq.com/login?appid=1"))
        assertFalse(isQqProviderUiUrl("https://xui.ptlogin2.qq.com/other?appid=1"))
        assertFalse(isQqProviderUiUrl("https://user@xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=1"))
        assertFalse(isQqProviderUiUrl("https://xui.ptlogin2.qq.com:8443/cgi-bin/xlogin?appid=1"))
    }

    @Test fun navigationRestoresDesktopUaOutsideTheTrustedQqLoginUi() {
        assertEquals(
            OFFICIAL_QR_USER_AGENT,
            loginNavigationUserAgent(OFFICIAL_QR_USER_AGENT, "https://music.163.com/#/login", nativeAllowed = true),
        )
        assertEquals(
            OFFICIAL_QR_USER_AGENT,
            loginNavigationUserAgent(OFFICIAL_QR_USER_AGENT, "https://xui.ptlogin2.qq.com.evil.example/cgi-bin/xlogin", nativeAllowed = true),
        )
        assertEquals(
            OFFICIAL_QR_USER_AGENT,
            loginNavigationUserAgent(OFFICIAL_QR_USER_AGENT, "https://open.weixin.qq.com/connect/oauth2/authorize", nativeAllowed = true),
        )
        assertEquals(
            OFFICIAL_QR_USER_AGENT,
            loginNavigationUserAgent(OFFICIAL_QR_USER_AGENT, "https://graph.qq.com/oauth2.0/token", nativeAllowed = true),
        )
        assertEquals(
            OFFICIAL_QR_USER_AGENT,
            loginNavigationUserAgent(OFFICIAL_QR_USER_AGENT, "https://evil.graph.qq.com/oauth2.0/show", nativeAllowed = true),
        )
    }

    @Test fun pageStartNeverReloadsProviderOrMusicCallbacks() {
        assertFalse(
            shouldReloadLoginPageAtStart(
                baseUserAgent = OFFICIAL_QR_USER_AGENT,
                currentUserAgent = AUTH_HANDOFF_MOBILE_USER_AGENT,
                targetUrl = "https://ssl.ptlogin2.qq.com/jump?sig=signed",
                nativeAllowed = true,
            ),
        )
        assertFalse(
            shouldReloadLoginPageAtStart(
                baseUserAgent = OFFICIAL_QR_USER_AGENT,
                currentUserAgent = AUTH_HANDOFF_MOBILE_USER_AGENT,
                targetUrl = "https://music.163.com/api/login/qrcode/client/login?code=callback",
                nativeAllowed = true,
            ),
        )
        assertFalse(
            shouldReloadLoginPageAtStart(
                baseUserAgent = OFFICIAL_QR_USER_AGENT,
                currentUserAgent = AUTH_HANDOFF_MOBILE_USER_AGENT,
                targetUrl = "https://open.weixin.qq.com/connect/oauth2/authorize?code=callback",
                nativeAllowed = true,
            ),
        )
    }

    @Test fun pageStartReloadsOnlyWhenAnInitialQqPageNeedsItsModeOrWebOnlyHint() {
        val graphAuthorization = "https://graph.qq.com/oauth2.0/show?which=Login"
        val unbridgedQqLogin = "https://ssl.ptlogin2.qq.com/cgi-bin/xlogin?appid=1"

        assertTrue(
            shouldReloadLoginPageAtStart(
                baseUserAgent = OFFICIAL_QR_USER_AGENT,
                currentUserAgent = OFFICIAL_QR_USER_AGENT,
                targetUrl = graphAuthorization,
                nativeAllowed = true,
            ),
        )
        assertFalse(
            shouldReloadLoginPageAtStart(
                baseUserAgent = OFFICIAL_QR_USER_AGENT,
                currentUserAgent = AUTH_HANDOFF_MOBILE_USER_AGENT,
                targetUrl = graphAuthorization,
                nativeAllowed = true,
            ),
        )
        assertTrue(
            shouldReloadLoginPageAtStart(
                baseUserAgent = OFFICIAL_QR_USER_AGENT,
                currentUserAgent = OFFICIAL_QR_USER_AGENT,
                targetUrl = unbridgedQqLogin,
                nativeAllowed = true,
            ),
        )
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
