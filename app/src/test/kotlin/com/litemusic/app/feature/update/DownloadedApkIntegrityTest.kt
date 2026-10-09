package com.litemusic.app.feature.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class DownloadedApkIntegrityTest {
    @Test
    fun acceptsExactSizeAndOptionalSha256Digest() {
        val directory = Files.createTempDirectory("update-test").toFile()
        val apk = File(directory, "update.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val digest = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1, 2, 3)).joinToString("") { "%02x".format(it) }

        assertTrue(DownloadedApkIntegrity.isComplete(apk, 3, "sha256:$digest"))
        assertTrue(DownloadedApkIntegrity.isComplete(apk, 3, null))
    }

    @Test
    fun rejectsTruncatedOrDigestMismatchedDownload() {
        val directory = Files.createTempDirectory("update-test").toFile()
        val apk = File(directory, "update.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertFalse(DownloadedApkIntegrity.isComplete(apk, 4, null))
        assertFalse(DownloadedApkIntegrity.isComplete(apk, 3, "sha256:deadbeef"))
    }
}
