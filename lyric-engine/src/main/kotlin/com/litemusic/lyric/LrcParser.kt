package com.litemusic.lyric

/**
 * LRC 解析器：支持 [mm:ss.xx] 时间标签、一行多时间标签、元信息（ti/ar/al/by/offset）。
 */
object LrcParser {
    private val TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[\\.:](\\d{1,3}))?]")
    private val META = Regex("^\\[([a-zA-Z]+):(.*)]$")

    fun parse(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val result = mutableListOf<LyricLine>()
        lrc.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val meta = META.find(line)
            if (meta != null) return@forEach // 元信息行（ti/ar/by/offset）跳过
            val tags = TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = line.substring(tags.last().range.last + 1).trim()
            tags.forEach { m ->
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
                result += LyricLine(min * 60_000 + sec * 1000 + ms, text)
            }
        }
        return result.sortedBy { it.timeMs }
    }
}
