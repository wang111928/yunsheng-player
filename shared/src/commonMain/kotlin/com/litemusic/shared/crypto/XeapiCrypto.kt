package com.litemusic.shared.crypto

/**
 * xeapi 通道（新版官方客户端 9.x）：X25519 密钥协商 + AES-256-ECB + AES-128-GCM。
 *
 * 真实算法（对齐 NeteaseCloudMusicApiEnhanced util/crypto.js）：
 *   1. dynamicKey = sessionKey（base64）或随机 16 字节
 *   2. plaintext  = JSON{contentType, method, queryString(含 e_r=true), body(base64 表单)}
 *   3. enc1       = AES-256-ECB(xeapiStaticKey, plaintext)
 *   4. shuffled   = xeapiMidTransform(enc1)：随机 16 字节异或 + base64 字符串循环移位
 *   5. B          = base64(AES-ECB(dynamicKey, shuffled))
 *   6. S          = X25519 ECDH：临时密钥对 + 服务端公钥，AES-128-GCM 加密
 *                  "base64(dynamicKey)|os|sk"（iv 随机 12 字节，S = 临时公钥32||iv||ct||tag）
 *   7. R          = base64(AES-ECB(xeapiStaticKey, "version|sessionId"))
 *
 * publicKeyState（服务端 X25519 公钥 + version + sk）需从官方接口引导获取，
 * 接入点见 TogetherRepository / NMApi 的 TODO；在拿到 key-state 前，
 * 降级链维持 eapi → weapi（见 CryptoEngine）。
 */
class XeapiCrypto {

    companion object {
        val STATIC_KEY: ByteArray = hexToBytes(
            "ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84"
        )
        const val SIGN_KEY =
            "mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g=="

        private fun hexToBytes(hex: String): ByteArray =
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    data class XeapiForm(val b: String, val s: String, val r: String)

    /**
     * @param publicKeyState 服务端密钥状态（{publicKey: base64 32字节, version, sk}），
     *                       当前未实现引导获取时传 null 会抛出，由调用方降级。
     * @param os "android"
     */
    fun encrypt(
        uri: String,
        body: Map<String, String>,
        publicKeyState: PublicKeyState?,
        os: String = "android",
        sessionKey: ByteArray? = null,
        sessionId: String = "",
    ): XeapiForm {
        val dynamicKey = sessionKey ?: randomBytes(16)
        val plaintext = buildPlaintext(uri, body)

        val enc1 = CryptoProvider.aesEcbEncryptBytes(STATIC_KEY, plaintext)
        val shuffled = midTransform(enc1)
        val b = CryptoProvider.aesEcbEncryptBytes(dynamicKey, shuffled)

        val s = publicKeyState?.let { state ->
            CryptoProvider.x25519Encrypt(dynamicKey, state.publicKey, os, state.sk)
        } ?: throw IllegalStateException("xeapi 需要 publicKeyState（未实现引导）")

        val r = CryptoProvider.aesEcbEncryptBytes(
            STATIC_KEY,
            (publicKeyState?.version ?: "").toByteArray(Charsets.UTF_8)
        )

        return XeapiForm(
            b = CryptoProvider.base64EncodeBytes(b),
            s = CryptoProvider.base64EncodeBytes(s),
            r = CryptoProvider.base64EncodeBytes(r),
        )
    }

    private fun buildPlaintext(uri: String, body: Map<String, String>): ByteArray {
        val fields = buildString {
            append("{")
            append("\"contentType\":\"application/x-www-form-urlencoded;charset=utf-8\",")
            append("\"method\":\"POST\",")
            append("\"queryString\":\"e_r=true\",")
            append("\"body\":\"")
            val form = body.entries.joinToString("&") { (k, v) ->
                CryptoProvider.urlEncode(k) + "=" + CryptoProvider.urlEncode(v)
            }
            append(CryptoProvider.base64Encode(form))
            append("\"}")
        }
        return fields.toByteArray(Charsets.UTF_8)
    }

    /** 随机 16 字节异或 + base64 循环移位（对齐官方 xeapiMidTransform） */
    private fun midTransform(ciphertext: ByteArray): ByteArray {
        val random = randomBytes(16)
        val xored = ByteArray(ciphertext.size)
        for (i in ciphertext.indices) {
            xored[i] = (ciphertext[i].toInt() xor (random[i and 0x0F].toInt() and 0xFF)).toByte()
        }
        val b64 = CryptoProvider.base64EncodeBytes(xored)
        val rot = if (b64.isNotEmpty()) (random[0].toInt() and 0x0F) % b64.length else 0
        val rotated = b64.substring(rot) + b64.substring(0, rot)
        val prefix = random
        return prefix + rotated.toByteArray(Charsets.UTF_8)
    }

    data class PublicKeyState(
        val publicKey: String, // base64 32 字节 X25519 公钥
        val version: String,
        val sk: String = "",
    )

    private fun randomBytes(n: Int): ByteArray = ByteArray(n) { kotlin.random.Random.nextInt(256).toByte() }

}
