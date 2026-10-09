package com.litemusic.app.feature.update

import java.io.File
import java.security.MessageDigest

object DownloadedApkIntegrity {
    fun isComplete(file: File, expectedSize: Long?, digest: String?): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (expectedSize != null && file.length() != expectedSize) return false
        val expectedDigest = digest?.removePrefix("sha256:")?.lowercase() ?: return true
        if (!expectedDigest.matches(Regex("[0-9a-f]{64}"))) return false
        val actualDigest = MessageDigest.getInstance("SHA-256").let { sha256 ->
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sha256.update(buffer, 0, count)
                }
            }
            sha256.digest().joinToString("") { "%02x".format(it) }
        }
        return actualDigest == expectedDigest
    }
}
