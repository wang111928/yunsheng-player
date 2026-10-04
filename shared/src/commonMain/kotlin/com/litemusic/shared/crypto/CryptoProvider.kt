package com.litemusic.shared.crypto

/**
 * 平台加密原语（expect/actual）。
 * JVM/Android 实际实现使用 javax.crypto + java.security，
 * 保证 shared 核心层纯 Kotlin、可在 JVM 上单元测试。
 */
expect object CryptoProvider {
    /** AES-128-CBC(PKCS5Padding)，返回 Base64 */
    fun aesCbcEncrypt(data: String, key: String, iv: String): String

    /** AES-128-ECB(PKCS5Padding)，返回 Base64 */
    fun aesEcbEncrypt(data: String, key: String): String

    /** RSA 公钥加密（NoPadding），输入 hex 指数/模数，返回小写 hex */
    fun rsaEncrypt(data: ByteArray, exponentHex: String, modulusHex: String): String

    /** MD5 hex */
    fun md5(data: String): String

    /** HMAC-SHA256 hex */
    fun hmacSha256(data: String, key: String): String

    /** Base64 编码（UTF-8 输入） */
    fun base64Encode(data: String): String

    /** URL 编码（application/x-www-form-urlencoded） */
    fun urlEncode(data: String): String

    /** AES-ECB(PKCS5Padding)，返回大写 hex */
    fun aesEcbEncryptHex(data: String, key: String): String

    /** AES-ECB(PKCS5Padding) 字节级：key 长度决定 AES-128/192/256 */
    fun aesEcbEncryptBytes(key: ByteArray, data: ByteArray): ByteArray

    /** Base64 编码（字节输入） */
    fun base64EncodeBytes(data: ByteArray): String

    /**
     * xeapi 会话加密：X25519 ECDH + AES-128-GCM。
     * 返回 临时公钥(32) || iv(12) || 密文 || authTag(16)。
     * @param publicKeyBase64 服务端 X25519 公钥（base64，32 字节）
     */
    fun x25519Encrypt(
        dynamicKey: ByteArray,
        publicKeyBase64: String,
        os: String,
        sk: String,
    ): ByteArray

    /** AES-ECB(PKCS5Padding) 解密：hex 密文 → UTF-8 明文 */
    fun aesEcbDecryptHex(cipherHex: String, key: String): String

    /** AES-ECB(PKCS5Padding) 字节级解密 */
    fun aesEcbDecryptBytes(key: ByteArray, data: ByteArray): ByteArray

    /** 字节 → 大写 hex */
    fun bytesToHex(data: ByteArray): String

    /** gzip 解压（eapi aeapi 变体响应） */
    fun gunzip(data: ByteArray): ByteArray

    /** Base64 解码 */
    fun base64Decode(data: String): ByteArray
}
