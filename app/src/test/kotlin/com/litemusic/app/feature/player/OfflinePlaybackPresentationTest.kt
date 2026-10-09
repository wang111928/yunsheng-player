package com.litemusic.app.feature.player

import com.litemusic.shared.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflinePlaybackPresentationTest {
    private val songs = listOf(
        Song(id = 1L, name = "缓存 1"),
        Song(id = 2L, name = "未缓存"),
        Song(id = 3L, name = "缓存 2"),
    )

    @Test
    fun offlineQueueExcludesUnavailableSongsAndMapsOriginalRowIndex() {
        val unavailable = setOf(2L)

        assertEquals(listOf(1L, 3L), offlinePlayableQueue(songs, isOnline = false, unavailable).map { it.id })
        assertEquals(1, offlineQueueStartIndex(songs, 2, isOnline = false, unavailable))
        assertNull(offlineQueueStartIndex(songs, 1, isOnline = false, unavailable))
    }

    @Test
    fun cacheScanInProgressDoesNotCreateAPlayableOfflineQueue() {
        assertEquals(emptyList<Song>(), offlinePlayableQueue(songs, isOnline = false, unavailableIds = null))
        assertNull(offlineQueueStartIndex(songs, 0, isOnline = false, unavailableIds = null))
    }

    @Test
    fun offlineRowsExplainWhetherCacheCheckIsPendingOrTheSongIsUnavailable() {
        assertEquals("正在检查离线缓存", offlineUnavailableLabel(isOnline = false, unavailableIds = null, songId = 1L))
        assertEquals("离线不可播", offlineUnavailableLabel(isOnline = false, unavailableIds = setOf(2L), songId = 2L))
        assertNull(offlineUnavailableLabel(isOnline = false, unavailableIds = setOf(2L), songId = 1L))
        assertNull(offlineUnavailableLabel(isOnline = true, unavailableIds = setOf(2L), songId = 2L))
    }
}
