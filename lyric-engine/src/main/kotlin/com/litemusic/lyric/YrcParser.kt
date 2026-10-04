package com.litemusic.lyric

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * YRC 解析器：网易云逐字歌词（yrc 字段）。
 * 官方行格式（双括号）：[[行起始,行结束,行时长][字偏移,字结束,<mm:ss.mmm>字<mm:ss.mmm>字...]]
 * 逐字时间戳：<mm:ss.mmm>（兼容两位小数）。
 */
object YrcParser {
    private val WORD = Regex("<(\\d{1,3}):(\\d{1,2})(?:[\\.:](\\d{1,3}))?>");

    fun parse(raw: String): List<YrcLine> {
        val lyricText = extractLyric(raw) ?: return emptyList()
        val lines = mutableListOf<YrcLine>()
        var pos = 0
        while (pos < lyricText.length) {
            val open = lyricText.indexOf('[', pos)
            if (open < 0) break
            val close = lyricText.indexOf(']', open)
            if (close < 0) break
            val header1 = lyricText.substring(open + 1, close)
            val beginMs = header1.split(',')[0].trim().toLongOrNull() ?: 0L

            // 收集同一行的连续头部（第二个头部含逐字时间轴）
            val headers = mutableListOf(header1)
            var cursor = close + 1
            while (cursor < lyricText.length && lyricText[cursor] == '[') {
                val close2 = lyricText.indexOf(']', cursor)
                if (close2 < 0) break
                headers += lyricText.substring(cursor + 1, close2)
                cursor = close2 + 1
            }

            // 行体：到下一个 '[' 或行尾，去掉多余的尾部 ']'
            val nextOpen = lyricText.indexOf('[', cursor)
            val end = if (nextOpen < 0) lyricText.length else nextOpen
            val body = lyricText.substring(cursor, end).trim().trimEnd(']').trim()

            // 逐字数据：取最后一个头部的最后一个逗号之后的内容 + 行体
            val wordSource = if (headers.size >= 2) headers.last().substringAfterLast(',') else body
            val words = parseWords(wordSource)
            if (words.isNotEmpty() || body.isNotBlank()) {
                lines += YrcLine(beginMs, words)
            }
            pos = end
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun parseWords(body: String): List<Word> {
        val words = mutableListOf<Word>()
        var cursor = 0
        var pending = StringBuilder()
        WORD.findAll(body).forEach { m ->
            if (pending.isNotEmpty()) {
                words += Word(0, 0, pending.toString())
                pending = StringBuilder()
            }
            val timeMs = toMs(m)
            val nextTag = body.indexOf('<', m.range.last + 1)
            val textEnd = if (nextTag < 0) body.length else nextTag
            val text = body.substring(m.range.last + 1, textEnd)
            words += Word(timeMs = timeMs, durMs = 0, text = text)
            cursor = textEnd
        }
        if (cursor < body.length) {
            words += Word(0, 0, body.substring(cursor))
        }
        // 计算每个词的持续时间
        for (i in 0 until words.size) {
            val next = words.getOrNull(i + 1)?.timeMs ?: (words[i].timeMs + 500)
            words[i] = words[i].copy(durMs = (next - words[i].timeMs).coerceAtLeast(0))
        }
        return words.filter { it.text.isNotBlank() }
    }

    private fun toMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLongOrNull() ?: 0L
        val sec = m.groupValues[2].toLongOrNull() ?: 0L
        val ms = m.groupValues[3].let { g ->
            when (g.length) {
                3 -> g.toLongOrNull() ?: 0L
                2 -> (g.toLongOrNull() ?: 0L) * 10
                1 -> (g.toLongOrNull() ?: 0L) * 100
                else -> 0L
            }
        }
        return min * 60_000 + sec * 1000 + ms
    }

    /** yrc 接口返回 {yrc:true, lyric:"...", ...}，提取 lyric 字段 */
    private fun extractLyric(raw: String): String? {
        val t = raw.trim()
        return if (t.startsWith("{")) {
            runCatching {
                Json { ignoreUnknownKeys = true }.parseToJsonElement(t).jsonObject["lyric"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        } else t
    }
}

