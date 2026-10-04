package com.litemusic.app.feature.playlist.importing

import org.junit.Assert.*
import org.junit.Test

class PlaylistOcrRefinementTest {
    @Test fun separateReadingsKeepOneSongAndPreserveIndependentFieldEvidence() {
        val query = screenshotRowQuery(
            OcrLine("F YOU (温柔女声 cover)", 250, 1000, 400, 40),
            OcrLine("「沉浸声风雪夜归人-1F YOU", 250, 1065, 500, 34),
            listOf("IF YOU (温柔女声 cover)"), listOf("风雪夜归人 - IF YOU"),
        )
        assertTrue(query.fromScreenshot)
        assertEquals("IF YOU (温柔女声 cover)", query.titleAlternatives.single())
        assertEquals("风雪夜归人", query.artist)
        assertTrue(query.albumAlternatives.contains("IF YOU"))
        assertFalse(query.artistAlternatives.any { it.contains("沉浸声") })
    }

    @Test fun emptyRefinementNeverDiscardsAnOriginalTitle() {
        val query = screenshotRowQuery(OcrLine("银河与星斗", 250, 1000, 220, 40), null, listOf(""), emptyList())
        assertEquals("银河与星斗", query.title)
        assertTrue(query.titleAlternatives.isEmpty())
    }

    @Test fun recoveredMetadataBelongsToTheExistingRowRatherThanBecomingAnotherSong() {
        val query = screenshotRowQuery(OcrLine("阿拉斯加海湾", 250, 1000, 300, 40), null,
            emptyList(), listOf("蓝心羽 - 阿拉斯加海湾"))
        assertEquals("蓝心羽", query.artist)
        assertEquals("阿拉斯加海湾", query.album)
    }

    @Test fun jsonKeepsArtistAndAlbumFromEachReadingTogether() {
        val query = screenshotRowQuery(OcrLine("歌曲", 250, 1000, 300, 40),
            OcrLine("歌手甲 - 专辑甲", 250, 1060, 500, 34), emptyList(),
            listOf("歌手乙 - 专辑乙", "歌手丙 - 专辑丙"))
        assertEquals(listOf(ImportedSongMetadataAlternative("歌手乙", "专辑乙"),
            ImportedSongMetadataAlternative("歌手丙", "专辑丙")), query.metadataAlternatives)
        assertEquals(listOf(query), parseImportedText(encodeScreenshotSongs(listOf(query))))
    }
}
