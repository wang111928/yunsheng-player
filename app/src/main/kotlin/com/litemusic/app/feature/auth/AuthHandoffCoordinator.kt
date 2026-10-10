package com.litemusic.app.feature.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal const val AUTH_HANDOFF_TTL_MILLIS = 5 * 60_000L
private const val AUTH_HANDOFF_GESTURE_DEDUP_MILLIS = 1_500L

/**
 * Holds the minimum state needed to observe one experimental QQ app handoff.
 * Callback values are intentionally never interpreted as a NetEase login result: the
 * existing CookieManager + account verification path remains the only login authority.
 */
internal class AuthHandoffCoordinator(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Pending(
        val startedAtMillis: Long,
        val expiresAtMillis: Long,
        val providerRequest: String,
    )

    private var pending: Pending? = null

    /**
     * Removes browser-only callback prefixes from QQ's outer qlogin URI. The provider-built
     * [p] value stays byte-for-byte untouched, including its encoded NetEase jump URL and state.
     */
    @Suppress("UNUSED_PARAMETER")
    fun begin(packageName: String, providerRequest: String): AuthHandoffStart? {
        if (!isExactQqNativeHandoffRequest(providerRequest) || !outerQueryHasNonBlankValue(providerRequest, "p")) return null
        val current = pending
        val now = nowMillis()
        if (current != null && now <= current.expiresAtMillis && current.providerRequest == providerRequest &&
            now - current.startedAtMillis in 0..AUTH_HANDOFF_GESTURE_DEDUP_MILLIS
        ) {
            return AuthHandoffStart(handoffUrl = null, duplicate = true)
        }
        pending = Pending(
            startedAtMillis = now,
            expiresAtMillis = now + AUTH_HANDOFF_TTL_MILLIS,
            providerRequest = providerRequest,
        )
        return AuthHandoffStart(handoffUrl = removeOuterQueryParameter(providerRequest, "schemacallback"))
    }

    /**
     * Compatibility shell for a legacy Activity callback. QQ's actual return is the verified
     * HTTPS jump handled by [captureHttpsReturn], never this app-defined scheme callback.
     */
    fun capture(callbackUrl: String): AuthHandoffCapture? = null

    fun hasPending(): Boolean {
        val current = pending ?: return false
        if (nowMillis() > current.expiresAtMillis) {
            pending = null
            return false
        }
        return true
    }

    /** A native HTTPS jump is accepted only against the exact live provider session. */
    fun captureHttpsReturn(candidateUrl: String): String? {
        val current = pending ?: return null
        if (!hasPending() || !QqHttpsReturnMatcher.matches(current.providerRequest, candidateUrl)) return null
        pending = null
        return candidateUrl
    }

    fun cancel() {
        pending = null
    }

    fun callbackShape(callbackUrl: String): AuthHandoffCallbackShape {
        return AuthHandoffCallbackShape(false, false, emptySet(), 0, false)
    }

}

internal data class AuthHandoffCapture(
    val queryKeys: Set<String>,
    val unknownKeyCount: Int,
    val hasUrlParameter: Boolean,
)

internal data class AuthHandoffStart(
    val handoffUrl: String?,
    val duplicate: Boolean = false,
)

internal data class AuthHandoffCallbackShape(
    val schemeMatches: Boolean,
    val routeMatches: Boolean,
    val queryKeys: Set<String>,
    val unknownKeyCount: Int,
    val hasUrlParameter: Boolean,
)

internal fun isExactQqNativeHandoffRequest(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return when {
        uri.scheme.equals("wtloginmqq", ignoreCase = true) ->
            uri.userInfo == null && uri.port == -1 && uri.rawFragment == null &&
                uri.host.equals("ptlogin", ignoreCase = true) && uri.path == "/qlogin"
        uri.scheme.equals("intent", ignoreCase = true) ->
            uri.userInfo == null && uri.port == -1 && uri.host.equals("ptlogin", ignoreCase = true) && uri.path == "/qlogin" &&
                uri.rawFragment.orEmpty().split(';').any {
                    it.equals("scheme=wtloginmqq", ignoreCase = true)
                }
        else -> false
    }
}

private fun parseQueryEntries(rawQuery: String?): List<Pair<String, String>> {
    if (rawQuery.isNullOrEmpty()) return emptyList()
    return buildList {
        rawQuery.split('&').forEach { part ->
            val rawKey = part.substringBefore('=')
            if (rawKey.isEmpty()) return@forEach
            val rawValue = part.substringAfter('=', "")
            val key = runCatching { URLDecoder.decode(rawKey, StandardCharsets.UTF_8) }.getOrNull() ?: return@forEach
            val value = runCatching { URLDecoder.decode(rawValue, StandardCharsets.UTF_8) }.getOrNull() ?: return@forEach
            add(key to value)
        }
    }
}

private fun outerQueryHasNonBlankValue(url: String, name: String): Boolean {
    val beforeFragment = url.substringBefore('#')
    return parseQueryEntries(beforeFragment.substringAfter('?', "")).any { (key, value) ->
        key == name && value.isNotBlank()
    }
}

private fun removeOuterQueryParameter(url: String, name: String): String {
    val fragmentIndex = url.indexOf('#')
    val beforeFragment = if (fragmentIndex < 0) url else url.substring(0, fragmentIndex)
    val fragment = if (fragmentIndex < 0) "" else url.substring(fragmentIndex)
    val queryIndex = beforeFragment.indexOf('?')
    if (queryIndex < 0) return url
    val base = beforeFragment.substring(0, queryIndex)
    val kept = beforeFragment.substring(queryIndex + 1).split('&').filter { segment ->
        val rawName = segment.substringBefore('=')
        runCatching { URLDecoder.decode(rawName, StandardCharsets.UTF_8) }.getOrNull() != name
    }
    return "$base?${kept.joinToString("&")}$fragment"
}
