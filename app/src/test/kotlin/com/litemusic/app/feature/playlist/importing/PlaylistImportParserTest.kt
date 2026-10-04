package com.litemusic.app.feature.playlist.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import com.litemusic.shared.model.Artist
import com.litemusic.shared.model.Song

class PlaylistImportParserTest {
    @Test
    fun extractsPlaylistIdFromOfficialLinksAndBareIdsOnly() {
        assertEquals(123456L, officialPlaylistId("https://music.163.com/playlist?id=123456"))
        assertEquals(123456L, officialPlaylistId("https://y.music.163.com/m/playlist?id=123456&foo=bar"))
        assertEquals(123456L, officialPlaylistId("123456"))
        assertNull(officialPlaylistId("https://music.163.com/song?id=123456"))
        assertNull(officialPlaylistId("12345678901234567890"))
        assertEquals(
            "https://music.163.com/playlist?id=654321",
            extractedImportUrl("分享歌单《测试》\nhttps://music.163.com/playlist?id=654321。"),
        )
        assertEquals(654321L, officialPlaylistId("分享歌单《测试》\nhttps://music.163.com/#/playlist?id=654321。"))
    }

    @Test
    fun textParserKeepsArtistAndDeduplicatesLines() {
        val parsed = parseImportedSongText(
            "1. 起风了 - 买辣椒也用券\n" +
                "起风了 - 买辣椒也用券\n" +
                "晴天 / 周杰伦\n" +
                "只写歌名\n",
        )

        assertEquals(
            listOf(
                ImportedSongQuery("起风了", "买辣椒也用券"),
                ImportedSongQuery("晴天", "周杰伦"),
                ImportedSongQuery("只写歌名"),
            ),
            parsed,
        )
    }

    @Test
    fun normalizesCsvAndM3uWithoutTreatingMetadataAsSongs() {
        val csv = "title,artist\n夜曲,周杰伦\n晴天,周杰伦"
        val m3u = "#EXTM3U\n#EXTINF:205,周杰伦 - 夜曲\nNight.mp3\n#EXTINF:270,周杰伦 - 晴天\nSunny.mp3"

        assertEquals(
            listOf(ImportedSongQuery("夜曲", "周杰伦"), ImportedSongQuery("晴天", "周杰伦")),
            parseImportedText(csv),
        )
        assertEquals(
            listOf(ImportedSongQuery("夜曲", "周杰伦"), ImportedSongQuery("晴天", "周杰伦")),
            parseImportedText(m3u),
        )
    }

    @Test
    fun m3uWithoutArtistRetainsItsTrackTitle() {
        assertEquals(
            listOf(ImportedSongQuery("纯音乐")),
            parseImportedText("#EXTM3U\n#EXTINF:180,纯音乐\nInstrumental.mp3"),
        )
    }

    @Test
    fun parsesJsonAndBoundsWritesIntoDeduplicatedBatches() {
        assertEquals(
            listOf(ImportedSongQuery("稻香", "周杰伦")),
            parseImportedText("{\"songs\":[{\"name\":\"稻香\",\"artist\":\"周杰伦\"}]}"),
        )
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), importBatches(listOf(1L, 2L, 1L, 0L, 3L), 2))
    }

    @Test
    fun onlyExactTitleAndArtistMatchIsSelectedByDefault() {
        val exact = matchImportedSong(
            ImportedSongQuery("晴天", "周杰伦"),
            listOf(Song(id = 1L, name = "晴天", ar = listOf(Artist(name = "周杰伦")))),
        )
        val vague = matchImportedSong(
            ImportedSongQuery("晴"),
            listOf(Song(id = 2L, name = "晴天", ar = listOf(Artist(name = "周杰伦")))),
        )
        val titleOnly = matchImportedSong(
            ImportedSongQuery("晴天"),
            listOf(Song(id = 3L, name = "晴天", ar = listOf(Artist(name = "周杰伦")))),
        )

        assertTrue(exact.selectedByDefault)
        assertFalse(vague.selectedByDefault)
        assertFalse(titleOnly.selectedByDefault)
    }

    @Test
    fun staleOrCancelledPreviewCannotReplaceNewerInput() {
        assertTrue(shouldApplyImportPreview(responseGeneration = 4L, activeGeneration = 4L))
        assertFalse(shouldApplyImportPreview(responseGeneration = 4L, activeGeneration = 5L))
    }

    @Test
    fun differentVersionFromSameArtistRequiresManualSelection() {
        val live = matchImportedSong(
            ImportedSongQuery("晴天", "周杰伦"),
            listOf(Song(id = 4L, name = "晴天 (Live)", ar = listOf(Artist(name = "周杰伦")))),
        )
        assertFalse(live.selectedByDefault)
    }

    @Test
    fun overflowCountsSongsRatherThanJsonFormattingOrCsvHeaders() {
        val raw = (1..501).joinToString(prefix = "[", postfix = "]") { "{\"title\":\"歌曲$it\",\"artist\":\"歌手\"}" }
        assertEquals(500, parseImportedText(raw).size)
        assertEquals(1, importOverflowCount(raw))
        val csv = "title,artist\n" + (1..500).joinToString("\n") { "歌曲$it,歌手" }
        assertEquals(0, importOverflowCount(csv))
    }

    @Test
    fun importPlanHonorsManualChoiceAndDoesNotResubmitExistingSongs() {
        val matches = listOf(
            ImportedSongMatch(ImportedSongQuery("甲", "甲歌手"), Song(id = 1), 100),
            ImportedSongMatch(ImportedSongQuery("乙", "乙歌手"), Song(id = 2), 100),
            ImportedSongMatch(ImportedSongQuery("丙", "丙歌手"), Song(id = 3), 100),
        )

        assertEquals(listOf(listOf(2L)), importBatchPlan(matches, setOf(1L, 2L), setOf(1L)))
    }
}
