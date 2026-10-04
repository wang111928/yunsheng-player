package com.litemusic.shared.api

import com.litemusic.shared.crypto.CryptoEngine
import com.litemusic.shared.crypto.CryptoProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * 统一网络网关：
 *  - 所有请求经加密通道（weapi/eapi/xeapi）
 *  - 写操作默认 eapi，读操作默认 weapi
 *  - eapi 失败自动降级 xeapi → weapi（-460 风控规避）
 */
class ApiClient(
    engine: HttpClientEngine,
    val baseUrl: String = "https://music.163.com",
    private val crypto: CryptoEngine = CryptoEngine(),
    val userAgent: String = "Mozilla/5.0 (Linux; Android 15; V2329A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36 NeteaseMusic/9.1.90",
    cookieProvider: (() -> Map<String, String>)? = null,
) {
    companion object {
        private const val EAPI_KEY = "e82ckenh8dichen8"
        private val EAPI_KEY_BYTES = EAPI_KEY.toByteArray(Charsets.UTF_8)
        /** eapi 通道使用官方 App UA（服务器校验） */
        const val APP_UA =
            "NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; HBN-AL00 Build/cd737a2.0)"
        /** eapi 域名（官方 App 实际请求域名） */
        const val EAPI_HOST = "https://interfacepc.music.163.com"
        /** eapi 参数内嵌的 header 设备信息（对齐官方 App / NeteaseCloudMusicApiEnhanced） */
        private const val DEVICE_ID = "16fdb57d4e15a52b8c9a37e22b8f2b22"
        private const val OS_VER = "14"
        private const val APP_VER = "8.20.20.231215173437"
        private const val VERSION_CODE = "140"
        private const val MOBILE_NAME = "HBN-AL00"
        private const val RESOLUTION = "1260x2800"
        private const val CHANNEL = "xiaomi"
    }
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true // 官方接口部分字段为 null（如 dailySongs[].reason），归为默认值
    }

    val client: HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(this@ApiClient.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
    }

    private val cookies: () -> Map<String, String> = cookieProvider ?: { emptyMap() }

    fun cookie(name: String): String = cookies()[name].orEmpty()

    /** 调试日志出口（App 层注入，写入私有文件；OriginOS 屏蔽 logcat 时使用） */
    var logger: ((String) -> Unit)? = null

    private fun cookieHeader(): String =
        cookies().entries.joinToString("; ") { (k, v) -> k + "=" + v }

    /** 构造 eapi 的 header 设备信息（含登录凭证） */
    private fun buildEapiHeader(extra: Map<String, String>): Map<String, String> {
        val ts = currentTimeMillis()
        val csrf = extra["__csrf"] ?: ""
        return buildMap {
            put("osver", OS_VER)
            put("deviceId", DEVICE_ID)
            put("os", "android")
            put("appver", APP_VER)
            put("versioncode", VERSION_CODE)
            put("mobilename", MOBILE_NAME)
            put("buildver", (ts / 1000).toString())
            put("resolution", RESOLUTION)
            put("__csrf", csrf)
            put("channel", CHANNEL)
            put("requestId", ts.toString() + "_" + kotlin.random.Random.nextInt(1000, 10000))
            extra["MUSIC_U"]?.let { put("MUSIC_U", it) }
            extra["NMTID"]?.let { put("NMTID", it) }
            eapiSessionCookie(extra).takeIf { it.isNotBlank() }?.let { put("ntes_sess", it) }
        }
    }

    /** weapi 读操作 */
    suspend fun weapiPost(
        path: String,
        params: Map<String, Any?> = emptyMap(),
        cookieOverrides: Map<String, String> = emptyMap(),
    ): String {
        val requestCookies = cookies() + cookieOverrides
        val body = crypto.weapi(params + mapOf("csrf_token" to requestCookies["__csrf"].orEmpty()))
        return decodeBody(rawPost(baseUrl, path, body, requestCookies))
    }

    /**
     * eapi 写操作，带 weapi 自动降级。
     * 对齐官方实现：host=interfacepc，path=/eapi/xxx，params 内嵌 header，cookie 全量。
     */
    suspend fun eapiPost(path: String, params: Map<String, Any?> = emptyMap()): String {
        val requestCookies = cookies()
        val attempts = listOf(
            "eapi" to crypto.eapi(path, params + mapOf("header" to buildEapiHeader(requestCookies))),
            "weapi" to crypto.weapi(params + mapOf("csrf_token" to requestCookies["__csrf"].orEmpty())),
        )
        var lastError: Exception? = null
        for ((channel, body) in attempts) {
            val t0 = currentTimeMillis()
            try {
                val resp = decodeBody(rawPost(
                    if (channel == "eapi") EAPI_HOST else baseUrl,
                    if (channel == "eapi") "/eapi" + path.removePrefix("/api") else "/weapi" + path.removePrefix("/api"),
                    body, requestCookies
                )).also {
                    if (it.contains("\"code\":-460") || it.contains("\"-460\"")) throw DegradeException()
                }
                logger?.invoke(
                    "POST[$channel] $path ok ${currentTimeMillis() - t0}ms <- " +
                        if (path == "/api/event/square/dual/feed/get") "[redacted]" else resp.take(260)
                )
                return resp
            } catch (e: DegradeException) {
                logger?.invoke("POST[$channel] $path DEGRADE(-460)")
                lastError = e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.invoke("POST[$channel] $path EX ${e.javaClass.simpleName}: ${e.message}")
                lastError = e
                // A lost response does not prove that a write was rejected. Never replay it.
                throw e
            }
        }
        throw lastError ?: IllegalStateException("eapi 全部通道失败")
    }

    private class DegradeException : RuntimeException()

    private suspend fun rawPost(host: String, path: String, body: Map<String, String>, requestCookies: Map<String, String> = cookies()): ByteArray {
        // Ktor 3：Parameters 不能直接作为 body，显式编码为 form-urlencoded 字符串
        val encoded = body.entries.joinToString("&") { (k, v) ->
            CryptoProvider.urlEncode(k) + "=" + CryptoProvider.urlEncode(v)
        }
        val isEapi = host == EAPI_HOST
        // 注意：OkHttp 引擎已禁用 cookieJar（NetworkEngine.okHttpClientNoCookies），
        // 因此这里的显式 Cookie 头会真正发出，eapi 设备指纹不会被覆盖。
        val ck = requestCookies
        val cookieAll = if (isEapi) ck + buildEapiHeader(ck) else ck
        logger?.invoke(
            "REQ $host$path ua=" + (if (isEapi) "APP" else "WEB") +
                " cookies=[" + cookieAll.keys.joinToString(",") + "]" +
                " params=" + encoded.take(140)
        )
        val resp = client.post(host + path) {
            header(
                HttpHeaders.UserAgent,
                if (isEapi) APP_UA else userAgent,
            )
            header("Referer", "https://music.163.com")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            if (cookieAll.isNotEmpty()) header(HttpHeaders.Cookie, cookieAll.entries.joinToString("; ") { (k, v) -> k + "=" + v })
            setBody(encoded)
        }
        return resp.bodyAsBytes()
    }

    /**
     * eapi 响应解密：官方在 e_r=true 时返回 AES-ECB 加密的 hex 密文。
     * 自动检测：明文 JSON 直接用；否则按 hex 解密；aeapi 变体（x-aeapi）再 gunzip。
     */
    private fun decodeBody(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8).trim()
        if (text.startsWith("{") || text.startsWith("[")) return text
        // hex 密文 → AES-ECB 解密
        val decrypted = runCatching {
            CryptoProvider.aesEcbDecryptHex(CryptoProvider.bytesToHex(bytes), EAPI_KEY)
        }.getOrNull()
        if (!decrypted.isNullOrBlank() && (decrypted.trim().startsWith("{") || decrypted.trim().startsWith("["))) {
            return decrypted
        }
        // aeapi：解密 → base64 → gunzip
        val gunzipped = runCatching {
            val raw = CryptoProvider.aesEcbDecryptBytes(EAPI_KEY_BYTES, bytes)
            val b64 = String(raw, Charsets.UTF_8).trim()
            val zipped = CryptoProvider.base64Decode(b64)
            CryptoProvider.gunzip(zipped)
        }.getOrNull()
        if (gunzipped != null && gunzipped.isNotEmpty()) {
            val gzText = String(gunzipped, Charsets.UTF_8).trim()
            if (gzText.startsWith("{") || gzText.startsWith("[")) return gzText
        }
        return text
    }

    /** 明文 GET（扫码登录等免加密接口） */
    suspend fun plainGet(path: String, query: Map<String, String> = emptyMap()): String {
        val resp = client.get(baseUrl + path) {
            header(HttpHeaders.UserAgent, userAgent)
            header("Referer", "https://music.163.com")
            val cookie = cookieHeader()
            if (cookie.isNotBlank()) header(HttpHeaders.Cookie, cookie)
            query.forEach { (k, v) -> url.parameters.append(k, v) }
        }
        return resp.bodyAsText()
    }

    /** 明文 POST 表单（显式编码为 form-urlencoded，避免 Ktor 引擎对 Parameters body 的支持差异） */
    suspend fun plainPost(path: String, form: Map<String, String>): String {
        val encoded = form.entries.joinToString("&") { (k, v) ->
            CryptoProvider.urlEncode(k) + "=" + CryptoProvider.urlEncode(v)
        }
        val resp = client.post(baseUrl + path) {
            header(HttpHeaders.UserAgent, userAgent)
            header("Referer", "https://music.163.com")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            val cookie = cookieHeader()
            if (cookie.isNotBlank()) header(HttpHeaders.Cookie, cookie)
            setBody(encoded)
        }
        return resp.bodyAsText()
    }

    inline fun <reified T> decode(raw: String): T = json.decodeFromString<T>(raw)
}

/**
 * The persisted web cookie is named NTES_YD_SESS while the eapi encrypted
 * header expects ntes_sess.  Keep both spellings so private-message writes
 * retain their authentication after app restart.
 */
internal fun eapiSessionCookie(cookies: Map<String, String>): String =
    cookies["ntes_sess"].orEmpty().ifBlank { cookies["NTES_YD_SESS"].orEmpty() }


