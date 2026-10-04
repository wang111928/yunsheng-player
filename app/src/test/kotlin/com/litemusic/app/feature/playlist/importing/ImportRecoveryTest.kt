package com.litemusic.app.feature.playlist.importing

import com.litemusic.shared.model.Album
import com.litemusic.shared.model.Artist
import com.litemusic.shared.model.Song
import org.junit.Assert.*
import org.junit.Test

class ImportRecoveryTest {
    private val query = ImportedSongQuery("晴天", "周杰伦", "叶惠美", fromScreenshot = true)
    private fun song(id: Long, name: String = "晴天", artist: String = "周杰伦", album: String = "叶惠美") =
        Song(id = id, name = name, ar = listOf(Artist(name = artist)), al = Album(name = album))

    @Test fun partialCandidatePlusFailedFallbackIsStillRetryable() {
        val result = finishImportSearch(query, listOf(song(1, album = "其他专辑")), incomplete = true)
        assertNotNull(result.song)
        assertTrue(result.searchIncomplete)
        assertFalse(result.selectedByDefault)
    }

    @Test fun exactEvidenceRemainsConfirmedDespiteAnEarlierFailedKeyword() {
        val result = finishImportSearch(query, listOf(song(1)), incomplete = true)
        assertTrue(result.selectedByDefault)
        assertFalse(result.searchIncomplete)
    }

    @Test fun retryKeepsAnExplicitlySelectedCandidateEvenIfRecallChanges() {
        val old = ImportedSongMatch(query, song(1), 70, searchIncomplete = true)
        val refreshed = ImportedSongMatch(query, song(2), 100)
        val result = preserveRetriedImportChoice(old, refreshed, setOf(1))
        assertEquals(1L, result.song?.id)
        assertFalse(result.searchIncomplete)
    }

    @Test fun unselectedPartialCandidateCanBeReplacedByTheConfirmedRecording() {
        val old = ImportedSongMatch(query, song(1), 70, searchIncomplete = true)
        val refreshed = ImportedSongMatch(query, song(2), 100)
        assertEquals(refreshed, preserveRetriedImportChoice(old, refreshed, emptySet()))
    }

    @Test fun everyOcrTitleReadingIsSearchedIncludingTheLastCorrectOne() {
        val keywords = importSearchKeywords(ImportedSongQuery("俗應", "灼天/小田音乐社", "俗愿",
            titleAlternatives = listOf("俗恩", "俗愿"), fromScreenshot = true))
        assertEquals("俗愿 灼天", keywords.first())
        assertTrue("俗愿" in keywords)
        assertTrue("俗愿 小田音乐社" in keywords)
        assertTrue(keywords.size <= 9)
    }

    @Test fun albumRecallRequiresBothArtistAndAlbumAndRejectsDjAlbums() {
        val source = ImportedSongQuery("四季予你", "程响", "四手予你", albumAlternatives = listOf("四季予你"), fromScreenshot = true)
        assertEquals("四季予你 程响", importAlbumSearchKeyword(source))
        assertTrue(importAlbumMatches(source, Album(1, "四季予你", artist = Artist(name = "程响"))))
        assertFalse(importAlbumMatches(source, Album(2, "四季予你（DJ动感版）", artist = Artist(name = "程响"))))
        assertFalse(importAlbumMatches(source, Album(3, "四季予你", artist = Artist(name = "其他歌手"))))
    }

    @Test fun albumOcrCorrectionDoesNotCrossCombineArtistAndAlbumReadings() {
        val source = ImportedSongQuery("心似烟火", "坏读数", "其他专辑", fromScreenshot = true,
            metadataAlternatives = listOf(ImportedSongMetadataAlternative("陈壹千", "心似個火")),
            artistAlternatives = listOf("陈壹千"), albumAlternatives = listOf("心似個火"))
        assertTrue(matchImportedSong(source, listOf(song(1, "心似烟火", "陈壹千", "心似烟火"))).selectedByDefault)
        assertFalse(matchImportedSong(source.copy(fromScreenshot = false), listOf(song(1, "心似烟火", "陈壹千", "心似烟火"))).selectedByDefault)
    }

    @Test fun anchoredArtistCorrectionRequiresAnExactLatinPrefixAndPairedAlbum() {
        val source = ImportedSongQuery("致你(女声版)", "yihuik故慧", "致你", fromScreenshot = true)
        val candidate = song(1, "致你", "yihuik苡慧", "致你").copy(alia = listOf("女声版"))
        assertTrue(matchImportedSong(source, listOf(candidate)).selectedByDefault)
        assertFalse(matchImportedSong(source.copy(album = "另一个专辑"), listOf(candidate)).selectedByDefault)
        assertFalse(matchImportedSong(source.copy(artist = "yihui故慧"), listOf(candidate)).selectedByDefault)
    }

    @Test fun coverLabelsUseTheSameSemanticInChineseAndEnglish() {
        val source = ImportedSongQuery("晴天（翻唱）", "歌手", "专辑")
        val candidate = song(1, "晴天 (Cover)", "歌手", "专辑")
        assertTrue(matchImportedSong(source, listOf(candidate)).selectedByDefault)
        assertFalse(matchImportedSong(source, listOf(candidate.copy(name = "晴天 (Cover Live)"))).selectedByDefault)
    }
}
