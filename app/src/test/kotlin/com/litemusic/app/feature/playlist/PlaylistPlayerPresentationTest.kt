package com.litemusic.app.feature.playlist

import com.litemusic.shared.model.Album
import com.litemusic.shared.model.RadioProgram
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.Quality
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistPlayerPresentationTest {
    @Test
    fun playlistShareUsesThePublicOfficialLink() {
        assertEquals("https://music.163.com/playlist?id=123", playlistPublicLink(123L))
    }

    @Test
    fun newlyStartedQueueUsesTheConfiguredDefaultQuality() {
        val items = queueItemsForQuality(listOf(Song(id = 1L)), Quality.LOSSLESS)

        assertEquals(listOf(Quality.LOSSLESS), items.map { it.quality })
    }

    @Test
    fun programSummaryIsRetainedForThePlayerIntroduction() {
        val program = RadioProgram(id = 7L, programDesc = "这是一段节目简介")

        assertEquals("这是一段节目简介", program.text)
    }

    @Test
    fun programUsesItsOwnCommentThreadInsteadOfTheAttachedSongThread() {
        assertEquals("A_DJ_1_7", programCommentThreadId(7L))
    }

    @Test
    fun missingQueueArtworkIsHydratedWithoutReplacingExistingArtwork() {
        val existing = Song(id = 1L, name = "原歌曲", al = Album(picUrl = "https://old/cover.jpg"))
        val missing = Song(id = 2L, name = "缺封面")
        val merged = mergeSongDetails(
            original = listOf(existing, missing),
            details = listOf(
                Song(id = 1L, name = "详情一", al = Album(picUrl = "https://new/cover.jpg")),
                Song(id = 2L, name = "详情二", al = Album(picUrl = "https://detail/cover.jpg")),
            ),
        )

        assertEquals("https://old/cover.jpg", merged[0].coverUrl)
        assertEquals("https://detail/cover.jpg", merged[1].coverUrl)
        assertEquals("详情二", merged[1].name)
    }

    @Test
    fun programWithoutInlineSongStillExposesItsServerTrackId() {
        val program = RadioProgram(id = 7L, name = "节目", mainTrackId = 12345L)

        assertEquals(12345L, program.playableSongId)
    }

    @Test
    fun radioProgramUsesStationArtworkWhenNeitherProgramNorSongHasArtwork() {
        val program = RadioProgram(
            id = 7L,
            name = "节目",
            stationCoverUrl = "http://p3.music.126.net/station.jpg",
        )
        val song = Song(id = 123L, name = "主曲")

        assertEquals(
            "https://p3.music.126.net/station.jpg",
            programSongWithArtwork(program, song).coverUrl,
        )
    }

    @Test
    fun radioProgramUsesItsOwnArtworkBeforeStationArtworkWhenSongHasNone() {
        val program = RadioProgram(
            id = 7L,
            name = "节目",
            coverUrl = "http://p3.music.126.net/program.jpg",
            stationCoverUrl = "https://p3.music.126.net/station.jpg",
        )

        assertEquals(
            "https://p3.music.126.net/program.jpg",
            programSongWithArtwork(program, Song(id = 123L, name = "主曲")).coverUrl,
        )
    }

    @Test
    fun radioProgramNeverReplacesAvailableMainSongArtwork() {
        val program = RadioProgram(
            id = 7L,
            name = "节目",
            coverUrl = "https://p3.music.126.net/program.jpg",
            stationCoverUrl = "https://p3.music.126.net/station.jpg",
        )

        assertEquals(
            "https://p3.music.126.net/song.jpg",
            programSongWithArtwork(
                program,
                Song(id = 123L, name = "主曲", al = Album(picUrl = "https://p3.music.126.net/song.jpg")),
            ).coverUrl,
        )
    }

    @Test
    fun staleProgramRequestCannotReportItsSongLoadFailure() {
        assertEquals(false, canReportProgramFailure(requestGeneration = 3L, currentGeneration = 4L))
    }

    @Test
    fun playlistSearchAndSortKeepPlaybackInTheVisibleOrder() {
        val songs = listOf(
            Song(id = 1L, name = "乙", ar = listOf(com.litemusic.shared.model.Artist(name = "周"))),
            Song(id = 2L, name = "甲", ar = listOf(com.litemusic.shared.model.Artist(name = "王"))),
        )

        assertEquals(listOf(2L), playlistVisibleSongs(songs, "甲", PlaylistTrackOrder.ORIGINAL).map { it.id })
        assertEquals(listOf(2L, 1L), playlistVisibleSongs(songs, "", PlaylistTrackOrder.TITLE).map { it.id })
        assertEquals(listOf(2L, 1L), playlistVisibleSongs(songs, "", PlaylistTrackOrder.ARTIST).map { it.id })
    }

    @Test
    fun offlinePlaylistFilterKeepsOnlyFullyCachedSongsAfterSearchAndSort() {
        val tracks = listOf(Song(id = 1L, name = "甲"), Song(id = 2L, name = "乙"))

        assertEquals(
            listOf(2L),
            playlistVisibleSongs(tracks, "", PlaylistTrackOrder.ORIGINAL, offlineOnly = true, cachedSongIds = setOf(2L)).map { it.id },
        )
    }
}
