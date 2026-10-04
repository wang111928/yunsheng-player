package com.litemusic.data.auth

import android.util.Base64
import com.litemusic.data.prefs.AuthStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 登录凭证加密备份/恢复（FuoEvolve 优点）：
 * AES-256-GCM，密钥由用户口令 PBKDF2 派生，导出为 JSON 文件。
 */
class CredentialBackup(private val authStore: AuthStore) {
    private val json = Json { prettyPrint = true }

    @Serializable
    data class CredentialFile(
        val version: Int = 1,
        val session: AuthStore.Session,
        val exportedAt: Long,
    )

    fun exportTo(file: File, passphrase: String): Boolean = runCatching {
        val session = kotlinx.coroutines.runBlocking { authStore.current() }
        val payload = json.encodeToString(CredentialFile.serializer(), CredentialFile(session = session, exportedAt = System.currentTimeMillis()))
        file.writeText(encrypt(payload, passphrase))
        true
    }.getOrDefault(false)

    fun importFrom(file: File, passphrase: String): AuthStore.Session? = runCatching {
        val payload = decrypt(file.readText(), passphrase) ?: return null
        val cred = json.decodeFromString(CredentialFile.serializer(), payload)
        kotlinx.coroutines.runBlocking { authStore.save(cred.session) }
        cred.session
    }.getOrNull()

    private fun encrypt(plain: String, passphrase: String): String {
        val key = deriveKey(passphrase)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(data: String, passphrase: String): String? = runCatching {
        val raw = Base64.decode(data, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    private fun deriveKey(passphrase: String): SecretKeySpec {
        val salt = "netease-music-lite".toByteArray()
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 100_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return SecretKeySpec(key.encoded, "AES")
    }
}
