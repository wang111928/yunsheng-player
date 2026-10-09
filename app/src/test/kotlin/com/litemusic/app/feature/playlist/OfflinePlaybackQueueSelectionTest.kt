package com.litemusic.app.feature.playlist

import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflinePlaybackQueueSelectionTest {
    private val cached = QueueItem(1L, "已缓存", "歌手", quality = Quality.HIGH)
    private val second = QueueItem(3L, "另一首", "歌手", quality = Quality.LOSSLESS)

    @Test
    fun filtersUnavailableTracksAndKeepsActualCachedQualities() {
        val selection = selectOfflinePlaybackQueue(listOf(cached, null, second), sourceIndex = 2)!!
        assertEquals(listOf(cached, second), selection.items)
        assertEquals(1, selection.startIndex)
    }

    @Test
    fun tappingAnUnavailableSongDoesNotStartAnotherSong() {
        assertNull(selectOfflinePlaybackQueue(listOf(cached, null, second), sourceIndex = 1))
    }

    @Test
    fun batchPlaybackCanStartAtTheFirstAvailableSong() {
        val selection = selectOfflinePlaybackQueue(listOf(null, cached, second), 0, allowFirstAvailable = true)!!
        assertEquals(listOf(cached, second), selection.items)
        assertEquals(0, selection.startIndex)
        assertNull(selectOfflinePlaybackQueue(listOf(null, null), 0, allowFirstAvailable = true))
    }

    @Test
    fun duplicateSongOccurrencesKeepTheTappedPosition() {
        val selection = selectOfflinePlaybackQueue(listOf(cached, null, cached), sourceIndex = 2)!!
        assertEquals(listOf(cached, cached), selection.items)
        assertEquals(1, selection.startIndex)
    }

    @Test
    fun metadataEnrichmentKeepsTheFilteredQueueAndItsCachedQuality() {
        val metadata = queueMetadataForSelection(
            listOf(second, cached, second),
            listOf(Song(1L, "补充标题"), Song(2L, "已过滤"), Song(3L, "补充另一首")),
        )
        assertEquals(listOf(3L, 1L, 3L), metadata.map { it.id })
        assertEquals(listOf(Quality.LOSSLESS, Quality.HIGH, Quality.LOSSLESS), metadata.map { it.quality })
        assertEquals(listOf("补充另一首", "补充标题", "补充另一首"), metadata.map { it.title })
    }
}
