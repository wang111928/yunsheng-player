package com.litemusic.player

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamCacheStorageTest {
    private lateinit var root: File

    @Before
    fun createRoot() {
        root = Files.createTempDirectory("stream-cache-storage").toFile()
    }

    @After
    fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test
    fun usesPersistentDirectoryWhenNoLegacyCacheExists() {
        val noBackup = File(root, "no-backup").apply { mkdirs() }
        val legacyCache = File(root, "cache").apply { mkdirs() }

        val selected = StreamCacheStorage.resolveDirectory(noBackup, legacyCache)

        assertEquals(File(noBackup, StreamAudioCache.CACHE_DIRECTORY), selected)
        assertFalse(selected.exists())
    }

    @Test
    fun migratesLegacyCacheWithItsExistingFiles() {
        val noBackup = File(root, "no-backup").apply { mkdirs() }
        val legacyCache = File(root, "cache").apply { mkdirs() }
        val legacy = File(legacyCache, StreamAudioCache.CACHE_DIRECTORY).apply { mkdirs() }
        val cachedSpan = File(legacy, "3.00000000.v3.exo").apply { writeText("audio") }
        val uid = File(legacy, "4.uid").apply { writeText("cache-uid") }
        val index = File(legacy, "cached_content_index.exi").apply { writeText("cache-index") }

        val selected = StreamCacheStorage.resolveDirectory(noBackup, legacyCache)

        assertEquals(File(noBackup, StreamAudioCache.CACHE_DIRECTORY), selected)
        assertFalse(legacy.exists())
        assertEquals("audio", File(selected, cachedSpan.name).readText())
        assertEquals("cache-uid", File(selected, uid.name).readText())
        assertEquals("cache-index", File(selected, index.name).readText())
    }

    @Test
    fun preservesExistingPersistentCacheInsteadOfOverwritingIt() {
        val noBackup = File(root, "no-backup").apply { mkdirs() }
        val legacyCache = File(root, "cache").apply { mkdirs() }
        val legacy = File(legacyCache, StreamAudioCache.CACHE_DIRECTORY).apply { mkdirs() }
        File(legacy, "legacy-span").writeText("legacy")
        val persistent = File(noBackup, StreamAudioCache.CACHE_DIRECTORY).apply { mkdirs() }
        File(persistent, "persistent-span").writeText("persistent")

        val selected = StreamCacheStorage.resolveDirectory(noBackup, legacyCache)

        assertEquals(persistent, selected)
        assertTrue(File(persistent, "persistent-span").exists())
        assertTrue(File(legacy, "legacy-span").exists())
    }

    @Test
    fun retainsLegacyCacheWhenPersistentLocationCannotAcceptMigration() {
        val noBackupFile = File(root, "not-a-directory").apply { writeText("block migration") }
        val legacyCache = File(root, "cache").apply { mkdirs() }
        val legacy = File(legacyCache, StreamAudioCache.CACHE_DIRECTORY).apply { mkdirs() }
        val cachedSpan = File(legacy, "3.00000000.v3.exo").apply { writeText("audio") }

        val selected = StreamCacheStorage.resolveDirectory(noBackupFile, legacyCache)

        assertEquals(legacy, selected)
        assertTrue(cachedSpan.exists())
    }
}
