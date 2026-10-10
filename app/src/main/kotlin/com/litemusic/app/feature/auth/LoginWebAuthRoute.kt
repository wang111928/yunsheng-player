package com.litemusic.app.feature.auth

import java.net.URI

/**
 * Starts authorization in the official music site's WebView. QQ's native handoff is
 * enabled only when Android link handling and the trusted document bridge are available;
 * otherwise the provider's web flow stays in the same cookie session.
 */
internal enum class LoginWebAuthRoute {
    OFFICIAL_QR,
    QQ_OAUTH,
}

internal data class LoginWebAuthConfig(
    val kind: LoginWebAuthRoute,
    val entryUrl: String,
    val userAgent: String,
    val openOtherLoginOptions: Boolean,
)

/** Routes start inside NetEase's component so its official click handler creates OAuth state. */
internal fun loginWebAuthConfig(route: LoginWebAuthRoute): LoginWebAuthConfig = when (route) {
    LoginWebAuthRoute.QQ_OAUTH -> LoginWebAuthConfig(
        kind = LoginWebAuthRoute.QQ_OAUTH,
        entryUrl = OFFICIAL_LOGIN_URL,
        userAgent = OFFICIAL_QR_USER_AGENT,
        openOtherLoginOptions = true,
    )
    LoginWebAuthRoute.OFFICIAL_QR -> LoginWebAuthConfig(
        kind = LoginWebAuthRoute.OFFICIAL_QR,
        entryUrl = OFFICIAL_LOGIN_URL,
        userAgent = OFFICIAL_QR_USER_AGENT,
        openOtherLoginOptions = false,
    )
}

/** Add QQ's documented web-only flag before the page can request the native one-key app. */
internal fun normalizedQqWebLoginUrl(url: String, allowNativeHandoff: Boolean = false): String {
    if (allowNativeHandoff && (isExactQqNativeHandoffRequest(url) || isQqProviderUiUrl(url))) return url
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return url
    val host = uri.host?.lowercase() ?: return url
    if (host != "ptlogin2.qq.com" && !host.endsWith(".ptlogin2.qq.com")) return url
    // /jump is a signed hand-off callback. Only the initial QQ login UI should receive
    // the web-only hint, otherwise the callback's byte-exact parameters can be corrupted.
    if (uri.path !in setOf("/cgi-bin/xlogin", "/login")) return url
    if (uri.rawQuery?.split("&")?.any { it.substringBefore('=') == "pt_no_onekey" } == true) return url
    val fragmentStart = url.indexOf('#')
    val beforeFragment = if (fragmentStart < 0) url else url.substring(0, fragmentStart)
    val fragment = if (fragmentStart < 0) "" else url.substring(fragmentStart)
    val separator = if (uri.rawQuery.isNullOrEmpty()) "?" else "&"
    return "$beforeFragment$separator" + "pt_no_onekey=1$fragment"
}

internal fun isQqProviderUiUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        (host == "xui.ptlogin2.qq.com" || host == "ui.ptlogin2.qq.com") &&
        uri.userInfo == null && uri.port == -1 &&
        uri.path == "/cgi-bin/xlogin"
}

/** Do not leave the embedded login session for QQ's native one-key protocol. */
internal fun keepLoginNavigationInWebView(url: String, allowNativeHandoff: Boolean = false): Boolean {
    if (allowNativeHandoff && isExactQqNativeHandoffRequest(url)) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme.equals("wtloginmqq", ignoreCase = true)) return true
    if (!uri.scheme.equals("intent", ignoreCase = true)) return false
    val intentFragment = uri.rawFragment ?: return false
    return intentFragment.startsWith("Intent;", ignoreCase = true) &&
        intentFragment.split(';').any { it.equals("scheme=wtloginmqq", ignoreCase = true) }
}

/** A provider popup always inherits the desktop UA to avoid changing other official providers. */
internal fun popupLoginUserAgent(parentUserAgent: String): String = parentUserAgent

/**
 * Chooses mobile UA only for QQ's exact authorization entries and login UI. Loading the
 * graph entry in mobile mode lets QQ navigate to the top-level xlogin document with its bridge.
 */
internal fun loginNavigationUserAgent(
    baseUserAgent: String,
    targetUrl: String,
    nativeAllowed: Boolean,
): String = if (nativeAllowed && (isQqProviderUiUrl(targetUrl) || isQqTopLevelAuthorizationUrl(targetUrl))) {
    AUTH_HANDOFF_MOBILE_USER_AGENT
} else {
    baseUserAgent
}

internal fun shouldReloadLoginPageAtStart(
    baseUserAgent: String,
    currentUserAgent: String,
    targetUrl: String,
    nativeAllowed: Boolean,
): Boolean {
    // A non-bridge QQ page still needs the documented web-only hint. The normalizer leaves
    // signed /jump callbacks untouched, so they can never enter this branch.
    if (normalizedQqWebLoginUrl(targetUrl, nativeAllowed) != targetUrl) return true

    val isInitialNativePage = isQqProviderUiUrl(targetUrl) || isQqTopLevelAuthorizationUrl(targetUrl)
    if (!isInitialNativePage) return false

    return currentUserAgent != loginNavigationUserAgent(baseUserAgent, targetUrl, nativeAllowed)
}

private fun isQqTopLevelAuthorizationUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("graph.qq.com", ignoreCase = true) &&
        uri.userInfo == null && uri.port == -1 &&
        uri.path in setOf("/oauth2.0/show", "/oauth2.0/authorize")
}

internal const val AUTH_HANDOFF_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
        "Chrome/141.0.0.0 Mobile Safari/537.36"
