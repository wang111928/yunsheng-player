package com.litemusic.app.feature.player

import com.litemusic.shared.player.PlayerUiState
import com.litemusic.shared.player.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueuePresentationTest {
    @Test
    fun duplicateSongsHaveDistinctKeysAndAppendingKeepsExistingKeys() {
        val song = QueueItem(7L, "重复歌曲", "歌手")
        val rows = playbackQueueRows(PlayerUiState(queue = listOf(song, song, QueueItem(8L, "另一首", "歌手"))))
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        val appended = playbackQueueRows(PlayerUiState(queue = rows.map { it.item } + song))
        assertEquals(rows.map { it.key }, appended.take(rows.size).map { it.key })
    }

    @Test
    fun queueRowsKeepPlaybackOrderAndMarkCurrentSong() {
        val songs = listOf(
            QueueItem(1L, "第一首", "歌手 A"),
            QueueItem(2L, "正在播放", "歌手 B"),
            QueueItem(3L, "下一首", "歌手 C"),
        )

        val rows = playbackQueueRows(PlayerUiState(queue = songs, currentIndex = 1))

        assertEquals(listOf(0, 1, 2), rows.map { it.index })
        assertEquals(listOf(false, true, false), rows.map { it.isCurrent })
        assertEquals(songs, rows.map { it.item })
    }

    @Test
    fun onlyIncompleteRemoteSongsAreUnavailableOffline() {
        assertTrue(isOfflineUnavailable(isOnline = false, hasCompleteCache = false))
        assertFalse(isOfflineUnavailable(isOnline = false, hasCompleteCache = true))
        assertFalse(isOfflineUnavailable(isOnline = true, hasCompleteCache = false))
    }
}
