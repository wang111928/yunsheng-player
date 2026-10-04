package com.litemusic.app.data

import java.nio.file.Files
import java.nio.charset.Charset
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricFileStoreTest {
    @Test
    fun persistsValidatedLyricsAndSeparatesSongIdentities() {
        val root = Files.createTempDirectory("local-lyric-store").toFile()
        try {
            val first = LocalLyricFileStore(root)
            assertTrue(first.save(1L, "/music/a.mp3", "[00:01.00]A".byteInputStream()))
            assertTrue(first.save(1L, "/music/b.mp3", "[00:01.00]B".byteInputStream()))

            val afterRestart = LocalLyricFileStore(root)
            assertEquals("[00:01.00]A", afterRestart.load(1L, "/music/a.mp3"))
            assertEquals("[00:01.00]B", afterRestart.load(1L, "/music/b.mp3"))
            assertNull(afterRestart.load(2L, "/music/a.mp3"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidImportKeepsPreviouslyValidatedLyrics() {
        val root = Files.createTempDirectory("local-lyric-store").toFile()
        try {
            val store = LocalLyricFileStore(root)
            assertTrue(store.save(1L, "/music/a.mp3", "[00:01.00]保留".byteInputStream()))

            assertFalse(store.save(1L, "/music/a.mp3", "not an lrc".byteInputStream()))
            assertEquals("[00:01.00]保留", store.load(1L, "/music/a.mp3"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun gbkInputThatExpandsPastTheStoredLimitKeepsOldLyrics() {
        val root = Files.createTempDirectory("local-lyric-store").toFile()
        try {
            val store = LocalLyricFileStore(root)
            assertTrue(store.save(1L, "/music/a.mp3", "[00:01.00]保留".byteInputStream()))
            val source = "[00:01.00]" + "中".repeat((com.litemusic.lyric.LocalLrcLoader.MAX_LRC_BYTES - 64) / 2)
            val gbk = source.toByteArray(Charset.forName("GBK"))

            assertTrue(gbk.size <= com.litemusic.lyric.LocalLrcLoader.MAX_LRC_BYTES)
            assertFalse(store.save(1L, "/music/a.mp3", gbk.inputStream()))
            assertEquals("[00:01.00]保留", store.load(1L, "/music/a.mp3"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun inputReadFailureKeepsOldLyrics() {
        val root = Files.createTempDirectory("local-lyric-store").toFile()
        try {
            val store = LocalLyricFileStore(root)
            assertTrue(store.save(1L, "/music/a.mp3", "[00:01.00]保留".byteInputStream()))
            val brokenInput = object : InputStream() {
                override fun read(): Int = throw IOException("simulated read failure")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw IOException("simulated read failure")
            }
            assertFalse(store.save(1L, "/music/a.mp3", brokenInput))
            assertEquals("[00:01.00]保留", store.load(1L, "/music/a.mp3"))
        } finally {
            root.deleteRecursively()
        }
    }
}
