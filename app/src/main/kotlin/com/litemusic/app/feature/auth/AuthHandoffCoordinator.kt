package com.litemusic.app.feature.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

internal const val AUTH_HANDOFF_TTL_MILLIS = 90_000L

/**
 * Holds the minimum state needed to observe one experimental QQ app handoff.
 * Callback values are intentionally never interpreted as a NetEase login result: the
 * existing CookieManager + account verification path remains the only login authority.
 */
internal class AuthHandoffCoordinator(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nonceFactory: () -> String = ::newAuthHandoffNonce,
) {
    private data class Pending(
        val callbackScheme: String,
        val nonce: String,
        val expiresAtMillis: Long,
        val providerRequest: String,
    )

    private var pending: Pending? = null

    /**
     * Adds a callback only to QQ's outer qlogin URI. The provider-built [p] value stays
     * byte-for-byte untouched, including its encoded NetEase jump URL and any state.
     */
    fun begin(packageName: String, providerRequest: String): AuthHandoffStart? {
        if (!isExactQqNativeHandoffRequest(providerRequest) || !outerQueryHasNonBlankValue(providerRequest, "p")) return null
        val current = pending
        if (current != null && nowMillis() <= current.expiresAtMillis && current.providerRequest == providerRequest) {
            return AuthHandoffStart(handoffUrl = null, duplicate = true)
        }
        val callbackScheme = "${packageName.lowercase()}.auth"
        val nonce = nonceFactory()
        pending = Pending(
            callbackScheme = callbackScheme,
            nonce = nonce,
            expiresAtMillis = nowMillis() + AUTH_HANDOFF_TTL_MILLIS,
            providerRequest = providerRequest,
        )
        // Probe a callback prefix, following the public H5 browser-prefix pattern. The actual
        // native return format is unverified: capture its shape without trusting its values.
        val callback = "$callbackScheme://auth/qq?nonce=$nonce&url="
        return AuthHandoffStart(handoffUrl = replaceOuterQueryParameter(
            providerRequest,
            name = "schemacallback",
            value = URLEncoder.encode(callback, StandardCharsets.UTF_8),
        ))
    }

    /**
     * Captures only callback shape after a strict route, nonce and expiry check. Values such as
     * code, token or redirect URLs are never returned or loaded by this coordinator.
     */
    fun capture(callbackUrl: String): AuthHandoffCapture? {
        val expected = pending ?: return null
        if (nowMillis() > expected.expiresAtMillis) {
            pending = null
            return null
        }
        val uri = runCatching { URI(callbackUrl) }.getOrNull() ?: return null
        if (!matchesRoute(uri, expected)) return null

        val query = parseQueryEntries(uri.rawQuery)
        val nonces = query.filter { it.first == "nonce" }.map { it.second }
        if (nonces.size != 1 || nonces.single() != expected.nonce) return null
        pending = null
        return AuthHandoffCapture(
            queryKeys = query.map { it.first }.toSet().intersect(AUTH_HANDOFF_ALLOWED_QUERY_KEYS),
            unknownKeyCount = query.map { it.first }.toSet().count { it !in AUTH_HANDOFF_ALLOWED_QUERY_KEYS },
            hasUrlParameter = query.any { it.first == "url" },
        )
    }

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
        val expected = pending ?: return AuthHandoffCallbackShape(false, false, emptySet(), 0, false)
        val uri = runCatching { URI(callbackUrl) }.getOrNull()
            ?: return AuthHandoffCallbackShape(false, false, emptySet(), 0, false)
        val query = parseQueryEntries(uri.rawQuery)
        return AuthHandoffCallbackShape(
            schemeMatches = uri.scheme.equals(expected.callbackScheme, ignoreCase = true),
            routeMatches = matchesRoute(uri, expected),
            queryKeys = query.map { it.first }.toSet().intersect(AUTH_HANDOFF_ALLOWED_QUERY_KEYS),
            unknownKeyCount = query.map { it.first }.toSet().count { it !in AUTH_HANDOFF_ALLOWED_QUERY_KEYS },
            hasUrlParameter = query.any { it.first == "url" },
        )
    }

    private fun matchesRoute(uri: URI, expected: Pending): Boolean =
        uri.userInfo == null && uri.port == -1 && uri.rawFragment == null &&
            uri.scheme.equals(expected.callbackScheme, ignoreCase = true) &&
            uri.host.equals("auth", ignoreCase = true) && uri.path == "/qq"
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

private val AUTH_HANDOFF_ALLOWED_QUERY_KEYS = setOf("nonce", "url")

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

private fun replaceOuterQueryParameter(url: String, name: String, value: String): String {
    val fragmentIndex = url.indexOf('#')
    val beforeFragment = if (fragmentIndex < 0) url else url.substring(0, fragmentIndex)
    val fragment = if (fragmentIndex < 0) "" else url.substring(fragmentIndex)
    val queryIndex = beforeFragment.indexOf('?')
    if (queryIndex < 0) return "$beforeFragment?$name=$value$fragment"
    val base = beforeFragment.substring(0, queryIndex)
    val kept = beforeFragment.substring(queryIndex + 1).split('&').filter { segment ->
        val rawName = segment.substringBefore('=')
        runCatching { URLDecoder.decode(rawName, StandardCharsets.UTF_8) }.getOrNull() != name
    }
    return "$base?${(kept + "$name=$value").joinToString("&")}$fragment"
}

private fun newAuthHandoffNonce(): String {
    val bytes = ByteArray(18)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
