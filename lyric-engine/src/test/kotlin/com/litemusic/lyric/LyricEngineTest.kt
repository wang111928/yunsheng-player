package com.litemusic.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricEngineTest {
    private val engine = LyricEngine()

    @Test
    fun detectSectionsMarksRepeatedLyricBlockAsChorus() {
        val lines = listOf(
            LyricLine(0, "第一句"),
            LyricLine(2_000, "第二句"),
            LyricLine(8_000, "第一句"),
            LyricLine(10_000, "第二句"),
        )

        assertEquals(
            listOf(LyricSection(2, 3, 8_000, 10_000)),
            engine.detectSections(lines),
        )
    }

    @Test
    fun detectSectionsIgnoresShortOrEmptyLyrics() {
        assertTrue(engine.detectSections(emptyList()).isEmpty())
        assertTrue(engine.detectSections(listOf(LyricLine(0, "同一句"))).isEmpty())
    }
}
