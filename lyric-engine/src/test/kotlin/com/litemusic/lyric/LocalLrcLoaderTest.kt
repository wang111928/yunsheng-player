package com.litemusic.lyric

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLrcLoaderTest {
    @Test
    fun loadsUtf8SidecarAndSkipsAnInvalidEarlierCandidate() {
        val directory = Files.createTempDirectory("local-lrc").toFile()
        try {
            File(directory, "song.lrc").writeText("not an lrc")
            File(directory, "song-title.lrc").writeText("[00:01.00]可用歌词")

            assertEquals("[00:01.00]可用歌词", LocalLrcLoader.load(File(directory, "song.mp3").path, "song-title"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun loadsUppercaseExtensionAndFileUri() {
        val directory = Files.createTempDirectory("local-lrc").toFile()
        try {
            val audio = File(directory, "song.mp3")
            File(directory, "song.LRC").writeText("[00:01.00]大写扩展名")

            assertEquals("[00:01.00]大写扩展名", LocalLrcLoader.load(audio.toURI().toString(), "song"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun supportsGbkAndRejectsMalformedUtf8WithoutTimestampedText() {
        val gbk = "[00:01.00]中文歌词".toByteArray(Charset.forName("GBK"))
        assertEquals("[00:01.00]中文歌词", LocalLrcLoader.decodeAndValidate(gbk))
        assertNull(LocalLrcLoader.decodeAndValidate(byteArrayOf(0xE4.toByte(), 0x00)))
        assertNull(LocalLrcLoader.decodeAndValidate("[00:01.00]   ".toByteArray()))
    }

    @Test
    fun blankOrUnsafeTitleDoesNotSelectAnArbitraryOrParentFile() {
        val directory = Files.createTempDirectory("local-lrc").toFile()
        try {
            File(directory, "unrelated.lrc").writeText("[00:01.00]不应匹配")
            assertNull(LocalLrcLoader.load(File(directory, "song.mp3").path, ""))
            assertNull(LocalLrcLoader.load(File(directory, "song.mp3").path, "../unrelated"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun boundedStreamRejectsOversizeInput() {
        val bytes = ByteArray(LocalLrcLoader.MAX_LRC_BYTES + 1) { 'a'.code.toByte() }
        assertNull(LocalLrcLoader.readValidatedText(bytes.inputStream()))
        assertTrue(LocalLrcLoader.MAX_LRC_BYTES < bytes.size)
    }
}
