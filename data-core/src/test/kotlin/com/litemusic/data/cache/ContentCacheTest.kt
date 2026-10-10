package com.litemusic.data.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContentCacheTest {
    @Test
    fun replacingOneDiskEntryKeepsOnlyItsLatestByteCount() = runBlocking {
        val root = Files.createTempDirectory("content-cache-test").toFile()
        try {
            val cache = ContentCache(root)

            cache.put("cover", ByteArray(10))
            cache.put("cover", ByteArray(3))

            assertEquals(3L, cache.currentDiskSize())
            assertArrayEquals(ByteArray(3), cache.get("cover"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearLeavesOfflinePlaylistMetadataForItsAccount() = runBlocking {
        val root = Files.createTempDirectory("content-cache-test").toFile()
        try {
            val cache = ContentCache(root)
            cache.putString("cover", "temporary")
            cache.putOfflinePlaylistString(9L, "catalog", "offline")

            cache.clear()

            assertNull(cache.getString("cover"))
            assertEquals("offline", cache.getOfflinePlaylistString(9L, "catalog"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun offlinePlaylistMetadataIsSeparatedByStableAccountId() = runBlocking {
        val root = Files.createTempDirectory("content-cache-test").toFile()
        try {
            val cache = ContentCache(root)
            cache.putOfflinePlaylistString(1L, "detail:7", "account-one")
            cache.putOfflinePlaylistString(2L, "detail:7", "account-two")

            assertEquals("account-one", cache.getOfflinePlaylistString(1L, "detail:7"))
            assertEquals("account-two", cache.getOfflinePlaylistString(2L, "detail:7"))
        } finally {
            root.deleteRecursively()
        }
    }
}
