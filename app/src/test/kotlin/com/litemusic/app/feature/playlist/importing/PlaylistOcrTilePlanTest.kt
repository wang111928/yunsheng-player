package com.litemusic.app.feature.playlist.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOcrTilePlanTest {
    @Test
    fun longPlaylistUsesOriginalWidthOverlappingTilesWithinPixelBudget() {
        val width = 1260
        val height = 8246
        val tiles = ocrTilePlan(width, height)

        assertEquals(0, tiles.first().top)
        assertEquals(height, tiles.last().bottom)
        assertTrue(tiles.all { tile -> tile.height.toLong() * width <= 4_000_000L })
        assertTrue(tiles.zipWithNext().all { (first, second) -> first.bottom - second.top >= 200 })
    }

    @Test
    fun overlappingTilesCoverRowsAtEveryBoundaryWithoutGaps() {
        val tiles = ocrTilePlan(width = 1260, height = 8246)
        val rowsAtAndAroundBoundaries = buildList {
            add(0)
            add(8245)
            tiles.dropLast(1).forEach { tile ->
                add(tile.bottom - 1)
                add(tile.bottom)
                add(tile.bottom + 199)
            }
        }

        assertTrue(rowsAtAndAroundBoundaries.all { row ->
            tiles.any { tile -> row in tile.top until tile.bottom }
        })
    }

    @Test
    fun smallScreenshotStaysASingleTile() {
        assertEquals(listOf(OcrTile(top = 0, bottom = 2400)), ocrTilePlan(1260, 2400))
    }

    @Test
    fun hundredPlusSongScreenshotKeepsEveryTileWithinBudgetAndCovered() {
        val width = 1260
        val height = 40_000
        val tiles = ocrTilePlan(width, height)

        assertTrue(tiles.size > 10)
        assertEquals(0, tiles.first().top)
        assertEquals(height, tiles.last().bottom)
        assertTrue(tiles.all { it.height.toLong() * width <= 4_000_000L })
        assertTrue(tiles.zipWithNext().all { (first, second) ->
            first.bottom - second.top >= 200 && second.top <= first.bottom
        })
    }

    @Test
    fun onlyCrossTileGeometryIsDeduplicatedAndTheLessCroppedLineWins() {
        val firstTile = OcrTile(0, 3174)
        val secondTile = OcrTile(2974, 6148)
        val sameTileTitle = TiledOcrLine(OcrLine("歌名", 100, 3000, 200, 40), 0, firstTile)
        val sameTileBadge = TiledOcrLine(OcrLine("VIP", 110, 3002, 200, 40), 0, firstTile)
        val overlapDuplicate = TiledOcrLine(OcrLine("截断歌名", 100, 3000, 200, 40), 1, secondTile)

        assertEquals(
            listOf("歌名", "VIP"),
            mergeOverlappingTileLines(listOf(sameTileTitle, sameTileBadge, overlapDuplicate)).map(OcrLine::text),
        )
    }
}
