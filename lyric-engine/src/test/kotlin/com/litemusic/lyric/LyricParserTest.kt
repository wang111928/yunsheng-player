package com.litemusic.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricParserTest {

    @Test
    fun lrcParseBasic() {
        val lrc = "[ti:test]\n[00:01.50]第一行\n[00:05.75]第二行\n"
        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)
        assertEquals(1500, lines[0].timeMs)
        assertEquals("第二行", lines[1].text)
    }

    @Test
    fun lrcMultiTag() {
        val lrc = "[00:01.00][00:03.00]重复行\n"
        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.text == "重复行" })
    }

    @Test
    fun yrcParseWords() {
        val yrc = """
            {"yrc":true,"lyric":"[[0,0,1000][0,500,<00:00.00>你<00:00.25>好<00:00.50>世<00:00.75>界]]"}
        """.trimIndent()
        val lines = YrcParser.parse(yrc)
        assertEquals(1, lines.size)
        assertEquals(4, lines[0].words.size)
        assertEquals("你好世界", lines[0].text)
    }

    @Test
    fun engineCurrentLine() {
        val doc = LyricDoc(
            lines = listOf(LyricLine(0, "a"), LyricLine(3000, "b"), LyricLine(6000, "c")),
        )
        val engine = LyricEngine()
        assertEquals(0, engine.currentLine(doc, 2999))
        assertEquals(1, engine.currentLine(doc, 3000))
        assertEquals(2, engine.currentLine(doc, 99999))
    }
}
