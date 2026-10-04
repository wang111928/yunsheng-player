package com.litemusic.shared.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual object CryptoProvider {

    actual fun aesCbcEncrypt(data: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    actual fun aesEcbEncrypt(data: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    actual fun rsaEncrypt(data: ByteArray, exponentHex: String, modulusHex: String): String {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(BigInteger(modulusHex, 16), BigInteger(exponentHex, 16)))
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    actual fun md5(data: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(data.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    actual fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    actual fun base64Encode(data: String): String =
        Base64.getEncoder().encodeToString(data.toByteArray(Charsets.UTF_8))

    actual fun urlEncode(data: String): String =
        java.net.URLEncoder.encode(data, "UTF-8")

    actual fun aesEcbEncryptHex(data: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    }

    actual fun aesEcbEncryptBytes(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    actual fun base64EncodeBytes(data: ByteArray): String =
        Base64.getEncoder().encodeToString(data)

    actual fun x25519Encrypt(
        dynamicKey: ByteArray,
        publicKeyBase64: String,
        os: String,
        sk: String,
    ): ByteArray {
        val raw = Base64.getDecoder().decode(publicKeyBase64)
        val spkiPrefix = hexToBytes("302a300506032b656e032100")
        val peerKey = java.security.KeyFactory.getInstance("X25519")
            .generatePublic(java.security.spec.X509EncodedKeySpec(spkiPrefix + raw))
        val kp = java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val ephemeralRaw = kp.public.encoded.copyOfRange(kp.public.encoded.size - 32, kp.public.encoded.size)
        val keyAgreement: javax.crypto.KeyAgreement = javax.crypto.KeyAgreement.getInstance("X25519")
        keyAgreement.init(kp.private)
        keyAgreement.doPhase(peerKey, true)
        val sharedSecret: ByteArray = keyAgreement.generateSecret()
        // HKDF 简化版（对齐官方 deriveX25519AesKey）
        val prk = hmacSha256Bytes(ByteArray(32), if (sharedSecret.isEmpty()) ByteArray(32) else sharedSecret)
        val aesKey = hmacSha256Bytes(prk, ephemeralRaw + byteArrayOf(1)).copyOf(16)
        val iv = ByteArray(12) { kotlin.random.Random.nextInt(256).toByte() }
        val plain = (Base64.getEncoder().encodeToString(dynamicKey) + "|" + os + "|" + sk).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain)
        return ephemeralRaw + iv + ct + cipher.iv // iv == cipher.iv
    }

    actual fun aesEcbDecryptHex(cipherHex: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return String(cipher.doFinal(hexToBytes(cipherHex)), Charsets.UTF_8)
    }

    actual fun aesEcbDecryptBytes(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    actual fun bytesToHex(data: ByteArray): String =
        data.joinToString("") { "%02X".format(it) }

    actual fun gunzip(data: ByteArray): ByteArray =
        java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(data)).use { it.readBytes() }

    actual fun base64Decode(data: String): ByteArray =
        Base64.getDecoder().decode(data)

    private fun hmacSha256Bytes(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
