package com.litemusic.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCacheUsageTest {
    @Test
    fun usageDeduplicatesSongVariantsAndDoesNotCallPartialVariantComplete() {
        val usage = audioCacheUsageForResources(
            listOf(
                CachedAudioResource("stream:10:320000", cachedBytes = 100L, complete = true),
                CachedAudioResource("stream:10:999000", cachedBytes = 20L, complete = false),
                CachedAudioResource("stream:20:320000", cachedBytes = 30L, complete = false),
                CachedAudioResource("not-a-stream", cachedBytes = 999L, complete = true),
            ),
            freeBytes = 50L,
        )

        assertEquals(150L, usage.bytes)
        assertEquals(setOf(10L), usage.completeSongIds)
        assertEquals(setOf(20L), usage.partialSongIds)
        assertEquals(50L, usage.freeBytes)
    }
}
