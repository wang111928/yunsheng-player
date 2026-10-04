package com.litemusic.app.feature.playlist.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistScreenshotReaderTest {
    @Test
    fun repeatedConfirmedArtistAndAlbumPairsWhenGlyphBoundsHaveTheSameHeight() {
        assertEquals("晴天 - 周杰伦\n夜曲 - 周杰伦\n花海 - 周杰伦", orderedOcrText(listOf(
            OcrLine("晴天", 280, 1000, 300, 40),
            OcrLine("周杰伦 -测试专辑", 280, 1052, 500, 32),
            OcrLine("夜曲", 280, 1300, 300, 40),
            OcrLine("周杰伦-测试专辑", 280, 1352, 500, 32),
            OcrLine("花海", 280, 1600, 300, 32),
            OcrLine("周杰伦- 测试专辑", 280, 1652, 500, 32),
        )))
    }

    @Test
    fun knownArtistPrefixAloneDoesNotTurnAnotherSongTitleIntoAlbumMetadata() {
        assertEquals("曲目甲 - 周杰伦\n曲目乙 - 周杰伦\n夜曲\n周杰伦—晴天", orderedOcrText(listOf(
            OcrLine("曲目甲", 280, 1000, 300, 40),
            OcrLine("周杰伦 - 测试专辑", 280, 1052, 500, 32),
            OcrLine("曲目乙", 280, 1300, 300, 40),
            OcrLine("周杰伦 - 测试专辑", 280, 1352, 500, 32),
            OcrLine("夜曲", 280, 1600, 300, 40),
            OcrLine("周杰伦—晴天", 280, 1652, 300, 40),
        )))
    }

    @Test
    fun repeatedArtistEvidenceDoesNotCollapseUnrelatedCompactSongTitles() {
        assertEquals("曲目甲 - 歌手甲\n曲目乙 - 歌手甲\n夜曲\n你—我", orderedOcrText(listOf(
            OcrLine("曲目甲", 280, 1000, 300, 40),
            OcrLine("歌手甲 - 专辑甲", 280, 1052, 500, 32),
            OcrLine("曲目乙", 280, 1300, 300, 40),
            OcrLine("歌手甲 - 专辑乙", 280, 1352, 500, 32),
            OcrLine("夜曲", 280, 1600, 300, 40),
            OcrLine("你—我", 280, 1652, 300, 40),
        )))
    }

    @Test
    fun chineseDashInsideATitleDoesNotMakeItAnArtistLine() {
        assertEquals("夜曲\n你—我\n晴天\n稻香", orderedOcrText(listOf(
            OcrLine("夜曲", 280, 1000, 300, 40),
            OcrLine("你—我", 280, 1052, 300, 40),
            OcrLine("晴天", 280, 1300, 300, 40),
            OcrLine("稻香", 280, 1352, 300, 40),
        )))
    }

    @Test
    fun chineseDashInsideWrappedTitleDoesNotStartTheAlbumMetadata() {
        assertEquals("一个很长的歌名 你—我 - 歌手甲", orderedOcrText(listOf(
            OcrLine("一个很长的歌名", 280, 1000, 500, 40),
            OcrLine("你—我", 280, 1045, 300, 40),
            OcrLine("歌手甲 - 专辑甲", 280, 1097, 500, 32),
        )))
    }

    @Test
    fun separatedCompactTitleOnlyBlocksKeepEverySong() {
        assertEquals("Song A\nSong B\nSong C\nSong D", orderedOcrText(listOf(
            OcrLine("Song A", 280, 1000, 300, 40),
            OcrLine("Song B", 280, 1052, 300, 40),
            OcrLine("Song C", 280, 1300, 300, 40),
            OcrLine("Song D", 280, 1352, 300, 40),
        )))
    }

    @Test
    fun ocrWhitespaceAroundAlbumSeparatorDoesNotBreakOverlapDeduplication() {
        fun page(subtitle: String) = orderedOcrText(listOf(
            OcrLine("双截棍", 280, 1000, 300, 40),
            OcrLine(subtitle, 280, 1052, 500, 32),
        ))
        val text = joinOcrScreenshots(listOf(
            page("周杰伦 -测试专辑"), page("周杰伦-测试专辑"), page("周杰伦- 测试专辑"),
        ))
        assertEquals(listOf(ImportedSongQuery("双截棍", "周杰伦")), parseImportedSongText(text))
    }

    @Test
    fun hyphenInsideArtistNameIsPreservedWhenRemovingAlbum() {
        assertEquals("曲目 - G-DRAGON", orderedOcrText(listOf(
            OcrLine("曲目", 280, 1000, 300, 40),
            OcrLine("G-DRAGON -专辑", 280, 1052, 500, 32),
        )))
    }

    @Test
    fun wrappedTitleStaysOneSongWithItsArtist() {
        val text = orderedOcrText(listOf(
            OcrLine("一个很长的歌名（", 280, 1000, 600, 40),
            OcrLine("现场版）", 280, 1045, 250, 40),
            OcrLine("歌手甲 - 专辑甲", 280, 1097, 450, 32),
            OcrLine("下一首", 280, 1300, 300, 40),
            OcrLine("歌手乙 - 专辑乙", 280, 1352, 450, 32),
        ))
        assertEquals("一个很长的歌名（ 现场版） - 歌手甲\n下一首 - 歌手乙", text)
    }

    @Test
    fun wrappedAlbumDoesNotBecomePartOfTheSongTitleOrAnotherSong() {
        val text = orderedOcrText(listOf(
            OcrLine("异想天开", 280, 1000, 300, 40),
            OcrLine("肖战 - 一个很长的专辑名", 280, 1052, 600, 32),
            OcrLine("专辑名的换行部分", 280, 1094, 500, 32),
            OcrLine("下一首", 280, 1300, 300, 40),
            OcrLine("歌手乙 - 专辑乙", 280, 1352, 450, 32),
        ))
        assertEquals("异想天开 - 肖战\n下一首 - 歌手乙", text)
    }

    @Test
    fun twentyEightStackedTitlesAndArtistAlbumLinesProduceTwentyEightSongs() {
        val lines = listOf(
            OcrLine("收藏歌单", 40, 200, 200, 40),
            OcrLine("28 首 · 65.1万次播放", 40, 600, 400, 40),
            OcrLine("播放全部", 800, 600, 200, 40),
        ) + (1..28).flatMap { n ->
            listOf(
                OcrLine("曲目$n", 280, 800 + n * 210, 400, 40),
                OcrLine("歌手$n - 专辑$n", 280, 852 + n * 210, 450, 32),
            )
        }

        assertEquals(
            (1..28).map { ImportedSongQuery("曲目$it", "歌手$it") },
            parseImportedSongText(orderedOcrText(lines.shuffled())),
        )
    }

    @Test
    fun verticalPairingPreservesNumericTitlesAndIsInvariantToImageScale() {
        val lines = listOf(
            OcrLine("25", 280, 1000, 60, 40),
            OcrLine("歌手甲", 280, 1052, 200, 32),
            OcrLine("4", 280, 1210, 40, 40),
            OcrLine("歌手乙", 280, 1262, 200, 32),
        )
        for (scale in listOf(0.5, 1.0, 2.0)) {
            val scaled = lines.map { line -> line.copy(
                left = (line.left * scale).toInt(), top = (line.top * scale).toInt(),
                width = (line.width * scale).toInt(), height = (line.height * scale).toInt(),
            ) }
            assertEquals("25 - 歌手甲\n4 - 歌手乙", orderedOcrText(scaled))
        }
    }

    @Test
    fun consecutiveRankColumnDoesNotBecomeTheTitleOfAStackedSong() {
        val lines = (1..2).flatMap { n -> listOf(
            OcrLine(n.toString(), 40, 1000 + n * 210, 30, 40),
            OcrLine("曲目$n", 280, 1000 + n * 210, 300, 40),
            OcrLine("歌手$n - 专辑", 280, 1052 + n * 210, 400, 32),
        ) }
        assertEquals("曲目1 - 歌手1\n曲目2 - 歌手2", orderedOcrText(lines))
    }

    @Test
    fun equallySpacedTitleOnlyLinesAreNotBlindlyPaired() {
        assertEquals("夜曲\n晴天\n稻香", orderedOcrText(listOf(
            OcrLine("夜曲", 280, 1000, 200, 40),
            OcrLine("晴天", 280, 1070, 200, 40),
            OcrLine("稻香", 280, 1140, 200, 40),
        )))
    }

    @Test
    fun overlappingScreenshotsDoNotDoubleCountTheSameTitleAndArtist() {
        fun page(range: IntRange) = orderedOcrText(range.flatMap { n -> listOf(
            OcrLine("曲目$n", 280, 1000 + (n - range.first) * 210, 300, 40),
            OcrLine("歌手$n - 专辑", 280, 1052 + (n - range.first) * 210, 400, 32),
        ) })
        assertEquals((1..4).map { ImportedSongQuery("曲目$it", "歌手$it") },
            parseImportedSongText(joinOcrScreenshots(listOf(page(1..3), page(3..4)))))
    }

    @Test
    fun readsLinesTopToBottomThenLeftToRightInsteadOfBlockOrder() {
        val text = orderedOcrText(
            listOf(
                OcrLine("1", left = 40, top = 100),
                OcrLine("2", left = 40, top = 126),
                OcrLine("歌名乙", left = 90, top = 126),
                OcrLine("歌手乙", left = 380, top = 126),
                OcrLine("歌名甲", left = 90, top = 100),
                OcrLine("下一首", left = 40, top = 174),
                OcrLine("歌手甲", left = 380, top = 102),
            ),
        )

        assertEquals("歌名甲 - 歌手甲\n歌名乙 - 歌手乙\n下一首", text)
    }

    @Test
    fun rejectsBlankFragmentsAndKeepsScreenshotsSeparatedForEditablePreview() {
        assertEquals("第一张\n\n第二张", joinOcrScreenshots(listOf("  第一张  \n\n", "\n第二张\n")))
        assertTrue(orderedOcrText(listOf(OcrLine("  ", 0, 0))).isEmpty())
    }

    @Test
    fun numericSongTitleIsNotMistakenForARank() {
        assertEquals("25 - 歌手", orderedOcrText(listOf(OcrLine("25", 40, 100), OcrLine("歌手", 380, 100))))
        assertEquals("25", orderedOcrText(listOf(OcrLine("25", 40, 100))))
        assertEquals("25 - 歌手 - 专辑", orderedOcrText(listOf(OcrLine("25", 40, 100), OcrLine("歌手", 380, 100), OcrLine("专辑", 580, 100))))
    }
}
