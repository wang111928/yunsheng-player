package com.litemusic.app.feature.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Matches QQ's browser HTTPS return against the exact provider-generated `p` jump URL. This is
 * a routing guard only: callers must still rely on the existing NetEase cookie verification.
 */
internal object QqHttpsReturnMatcher {
    private const val MAX_URL_LENGTH = 8_192
    private val nativeAddedKeys = setOf("keyindex", "clientuin", "clientkey")

    fun matches(providerRequest: String, candidateReturn: String): Boolean = runCatching {
        if (providerRequest.length > MAX_URL_LENGTH || candidateReturn.length > MAX_URL_LENGTH) return false
        val outer = URI(providerRequest)
        if (!isExactQqNativeHandoffRequest(providerRequest)) return false
        val outerQuery = uniqueQuery(outer.rawQuery) ?: return false
        val originalJump = outerQuery["p"].takeUnless { it.isNullOrBlank() } ?: return false
        val original = strictJump(originalJump) ?: return false
        val candidate = strictJump(candidateReturn) ?: return false
        if (original["openlogin_data"].isNullOrBlank()) return false
        if (!candidate.keys.containsAll(original.keys)) return false
        if (original.any { (key, value) -> candidate[key] != value }) return false
        val additions = candidate.keys - original.keys
        if (additions != nativeAddedKeys || additions.any { candidate[it].isNullOrBlank() }) return false

        val nested = strictU1(original["u1"].orEmpty()) ?: return false
        // Candidate u1 equality above preserves the exact authorize origin, path and opaque
        // state. Parsing it makes that invariant explicit and rejects malformed encodings.
        strictU1(candidate["u1"].orEmpty()) == nested
    }.getOrDefault(false)

    private fun strictJump(value: String): Map<String, String>? {
        if (value.length > MAX_URL_LENGTH) return null
        val uri = URI(value)
        if (!uri.scheme.equals("https", true) || !uri.host.equals("ssl.ptlogin2.qq.com", true) ||
            uri.path != "/jump" || uri.userInfo != null || uri.port != -1 || uri.rawFragment != null
        ) return null
        val query = uniqueQuery(uri.rawQuery) ?: return null
        return query.takeIf { it["u1"].isNullOrBlank().not() && it.values.none(String::isBlank) }
    }

    private fun strictU1(value: String): URI? {
        if (value.length > MAX_URL_LENGTH) return null
        val uri = URI(value)
        if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.port != -1 || uri.rawFragment != null) return null
        val connectRoot = uri.host.equals("connect.qq.com", true) && uri.path == "/" && uri.rawQuery.isNullOrEmpty()
        val graphAuthorize = uri.host.equals("graph.qq.com", true) && uri.path == "/oauth2.0/authorize"
        return uri.takeIf { connectRoot || graphAuthorize }
    }

    private fun uniqueQuery(rawQuery: String?): Map<String, String>? {
        if (rawQuery.isNullOrEmpty()) return null
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { part ->
            val rawKey = part.substringBefore('=')
            if (rawKey.isEmpty()) return null
            val rawValue = part.substringAfter('=', "")
            val key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8)
            val value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8)
            if (key.isEmpty() || result.put(key, value) != null) return null
        }
        return result
    }
}
