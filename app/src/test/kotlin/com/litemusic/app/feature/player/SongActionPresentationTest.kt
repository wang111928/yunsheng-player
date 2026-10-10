package com.litemusic.app.feature.player

import com.litemusic.lyric.LyricEngine
import com.litemusic.lyric.LyricLine
import com.litemusic.lyric.LyricSection
import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongActionPresentationTest {
    @Test
    fun songActionsMatchTheScreenshotOrder() {
        assertEquals(
            listOf(
                "赞赏好音乐",
                "下一首播放",
                "喜欢",
                "收藏到歌单",
                "减少推荐",
                "下载",
                "评论",
                "分享",
                "单曲购买",
                "歌手",
            ),
            songActionLabels(),
        )
    }

    @Test
    fun playNextRejectsAnUncachedRemoteSongOnlyWhileOffline() {
        assertTrue(canEnqueueNext(isOnline = true, hasCompleteCache = false))
        assertTrue(canEnqueueNext(isOnline = false, hasCompleteCache = true))
        assertEquals(false, canEnqueueNext(isOnline = false, hasCompleteCache = false))
        assertEquals("离线状态下该歌曲尚未完整缓存，无法加入播放队列", offlineEnqueueNextMessage())
    }

    @Test
    fun collectOnlyOffersPlaylistsOwnedByTheCurrentUser() {
        val playlists = listOf(
            Playlist(id = 1L, name = "我的歌单", userId = 7L),
            Playlist(id = 2L, name = "收藏的歌单", userId = 8L),
            Playlist(id = 3L, name = "无主歌单"),
        )

        assertEquals(listOf(1L), ownedPlaylistIds(playlists, currentUserId = 7L))
    }

    @Test
    fun collectSkipsTheWriteWhenTheSongAlreadyBelongsToThePlaylist() {
        assertEquals(
            CollectWriteDecision.AlreadyPresent,
            collectWriteDecision(songId = 42L, membership = setOf(42L, 99L)),
        )
        assertEquals(
            CollectWriteDecision.Add,
            collectWriteDecision(songId = 42L, membership = setOf(99L)),
        )
    }

    @Test
    fun detectedLyricSectionsAreLabeled高潮() {
        assertEquals("高潮", LyricSection(0, 1, 0, 1).label)

        val lines = listOf(
            LyricLine(0, "星光落在肩上"),
            LyricLine(1_000, "风吹过你的窗"),
            LyricLine(8_000, "星光落在肩上"),
            LyricLine(9_000, "风吹过你的窗"),
        )
        assertTrue(LyricEngine().detectSections(lines).all { it.label == "高潮" })
    }

    @Test
    fun selectedLyricTimeUsesMmSs() {
        assertEquals("00:25", formatTime(25_000))
        assertEquals("01:05", formatTime(65_000))
    }

    @Test
    fun radioProgramWithoutLyricsUsesItsActualDescriptionAsPlayerText() {
        val text = playerTextFallback(QueueItem(9L, "真实节目", "主播", description = "本期节目真实简介"))

        assertEquals("节目简介\n本期节目真实简介", text)
    }
}
