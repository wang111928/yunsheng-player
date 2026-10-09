package com.litemusic.player

import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.util.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAudioCachePolicyTest {
    @Test
    fun cacheKeyStaysStableWhenTheSignedUrlWouldRotate() {
        val song = QueueItem(id = 123L, title = "song", artist = "artist", quality = Quality.LOSSLESS)

        assertEquals("stream:123:${Quality.LOSSLESS.br}", streamCacheKey(song))
    }

    @Test
    fun cacheSeparatesQualityVariantsOfTheSameSong() {
        val high = QueueItem(id = 123L, title = "song", artist = "artist", quality = Quality.HIGH)
        val lossless = high.copy(quality = Quality.LOSSLESS)

        assertTrue(streamCacheKey(high) != streamCacheKey(lossless))
    }

    @Test
    fun localMusicNeverEntersRemoteStreamCacheOrPrefetch() {
        val local = QueueItem(id = 0L, title = "local", artist = "artist", localPath = "/music/local.mp3")

        assertNull(streamCacheKey(local))
        assertFalse(shouldPrefetchCurrentStream(local))
    }

    @Test
    fun offlineCacheUriContainsOnlyStableSongIdentityAndNeverASignedSourceUrl() {
        val song = QueueItem(id = 456L, title = "song", artist = "artist", quality = Quality.EXHIGH)

        assertEquals("https://cache.invalid/stream/456-${Quality.EXHIGH.br}.mp3", offlineStreamCacheUri(song))
        assertFalse(offlineStreamCacheUri(song).contains('?'))
    }

    @Test
    fun cachedQualityFallsBackToACompletedLowerVariantWhenRequestedVariantWasNeverCached() {
        val song = QueueItem(id = 789L, title = "song", artist = "artist", quality = Quality.LOSSLESS)

        assertEquals(
            Quality.HIGH,
            cachedQualityForOfflinePlayback(song) { quality -> quality == Quality.HIGH },
        )
    }

    @Test
    fun cachedQualityRemainsAvailableWhenTheSavedDefaultWasLoweredAfterListening() {
        val song = QueueItem(id = 790L, title = "song", artist = "artist", quality = Quality.STANDARD)

        assertEquals(
            Quality.EXHIGH,
            cachedQualityForOfflinePlayback(song) { quality -> quality == Quality.EXHIGH },
        )
    }

    @Test
    fun cachedQualityPrefersTheRequestedCompletedVariant() {
        val song = QueueItem(id = 791L, title = "song", artist = "artist", quality = Quality.EXHIGH)

        assertEquals(
            Quality.EXHIGH,
            cachedQualityForOfflinePlayback(song) { quality -> quality != Quality.LOSSLESS },
        )
    }

    @Test
    fun offlineFallbackIsRecheckedForAnExplicitReselectionOfTheSameInstalledItem() {
        assertTrue(
            shouldCheckOfflineCachedVariant(
                mediaKeyMatches = true,
                isNewExplicitSelection = true,
            ),
        )
    }

    @Test
    fun offlineFallbackIsNotRescannedForAnUnchangedPlaybackState() {
        assertFalse(
            shouldCheckOfflineCachedVariant(
                mediaKeyMatches = true,
                isNewExplicitSelection = false,
            ),
        )
    }
}
