package com.litemusic.shared.crypto

/**
 * eapi 通道：AES-128-ECB + MD5 摘要，输出大写 hex。
 * 写操作（收藏/歌单/评论/关注）首选通道，规避 -460 风控。
 *
 * 算法（对齐当前官方实现 / NeteaseCloudMusicApiEnhanced）：
 *   message = "nobody" + url + "use" + text + "md5forencrypt"
 *   digest  = md5hex(message)
 *   data    = url + "-36cd479b6b5-" + text + "-36cd479b6b5-" + digest
 *   params  = AES-128-ECB(data, "e82ckenh8dichen8") 的大写 hex
 */
object EapiCrypto {

    private const val SECRET_KEY = "e82ckenh8dichen8"
    private const val MAGIC = "36cd479b6b5"

    fun encrypt(url: String, text: String): String {
        val message = "nobody" + url + "use" + text + "md5forencrypt"
        val digest = CryptoProvider.md5(message)
        val data = url + "-" + MAGIC + "-" + text + "-" + MAGIC + "-" + digest
        return CryptoProvider.aesEcbEncryptHex(data, SECRET_KEY)
    }

    fun encrypt(url: String, params: Map<String, Any?>): String =
        encrypt(url, JsonText.build(params))
}

/** 轻量 JSON 序列化（仅用于加密前的文本拼接，避免循环依赖） */
internal object JsonText {
    fun build(map: Map<String, Any?>): String = value(map)

    private fun quoted(text: String): String = kotlinx.serialization.json.JsonPrimitive(text).toString()

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> quoted(v)
        is Boolean -> v.toString()
        is Int, is Long, is Float, is Double -> v.toString()
        is List<*> -> v.joinToString(",", prefix = "[", postfix = "]") { value(it) }
        is Map<*, *> -> v.entries.joinToString(",", prefix = "{", postfix = "}") { (key, item) ->
            quoted(key.toString()) + ":" + value(item)
        }
        else -> quoted(v.toString())
    }
}
