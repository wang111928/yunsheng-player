package com.litemusic.lyric

import com.litemusic.shared.model.LyricResponse

/**
 * 歌词引擎：多源合并（网易云 lrc/tlyric/yrc）+ 双语 + 当前行/当前字定位。
 * 逐字优先 YRC；无则 LRC；翻译与原文按时间窗（±300ms）对齐。
 */
class LyricEngine {

    private val lyricPunctuation = Regex("[\\p{Punct}\\s]+")

    fun build(response: LyricResponse?): LyricDoc {
        if (response == null) return LyricDoc()
        val yrc = response.yrc?.lyric
        val lrc = response.lrc?.lyric
        val tlyric = response.tlyric?.lyric

        if (!yrc.isNullOrBlank()) {
            val yrcLines = YrcParser.parse(yrc)
            if (yrcLines.isNotEmpty()) {
                val lines = yrcLines.map { LyricLine(it.timeMs, it.text) }
                val translations = matchTranslations(lines, LrcParser.parse(tlyric.orEmpty()))
                val wordLines = mutableMapOf<Int, List<Word>>()
                yrcLines.forEach { yl ->
                    val idx = lines.indexOfFirst { it.timeMs == yl.timeMs && it.text == yl.text }
                    if (idx >= 0) wordLines[idx] = yl.words
                }
                return LyricDoc(
                    lines = lines,
                    translations = translations,
                    wordLines = wordLines,
                    source = LyricSource.YRC,
                )
            }
        }

        if (!lrc.isNullOrBlank()) {
            val lines = LrcParser.parse(lrc)
            if (lines.isNotEmpty()) {
                val translations = matchTranslations(lines, LrcParser.parse(tlyric.orEmpty()))
                return LyricDoc(lines = lines, translations = translations, source = LyricSource.LRC)
            }
        }
        return LyricDoc()
    }

    private fun matchTranslations(original: List<LyricLine>, tr: List<LyricLine>): Map<Int, String> {
        if (tr.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, String>()
        val sortedTr = tr.filter { it.text.isNotBlank() }
        original.forEachIndexed { i, line ->
            val match = sortedTr.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
            if (match != null && kotlin.math.abs(match.timeMs - line.timeMs) <= 300) {
                result[i] = match.text
            }
        }
        return result
    }

    /** 当前行索引（二分查找） */
    fun currentLine(doc: LyricDoc, positionMs: Long): Int {
        val lines = doc.lines
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 当前字索引 */
    fun currentWord(doc: LyricDoc, lineIndex: Int, positionMs: Long): Int {
        if (lineIndex < 0) return -1
        val words = doc.wordLines[lineIndex] ?: return -1
        var idx = -1
        words.forEachIndexed { i, w ->
            if (w.timeMs <= positionMs) idx = i
        }
        return idx
    }

    /** 组装 UI 行（原文 + 翻译 + 逐字） */
    fun toUiLines(doc: LyricDoc): List<LyricUiLine> = doc.lines.mapIndexed { i, line ->
        LyricUiLine(
            timeMs = line.timeMs,
            text = line.text,
            translation = doc.translations[i] ?: "",
            words = doc.wordLines[i] ?: emptyList(),
        )
    }

    /** 锁屏歌词：输出到 MediaSession 的单行文本 */
    fun currentText(doc: LyricDoc, positionMs: Long): String {
        val i = currentLine(doc, positionMs)
        return if (i < 0) "" else doc.lines[i].text
    }

    /**
     * 使用歌词文本的重复段推断高潮候选。服务端没有明确分段元数据时，结果只能作为本地提示。
     * 只标记后一次出现的至少两行重复段，避免把普通单句重复误报为高潮。
     */
    fun detectSections(lines: List<LyricLine>): List<LyricSection> {
        if (lines.size < 4) return emptyList()

        val normalized = lines.map { lyricPunctuation.replace(it.text.lowercase(), "") }
        val candidates = mutableListOf<LyricSection>()

        for (firstStart in 0 until lines.lastIndex) {
            if (normalized[firstStart].isBlank()) continue
            for (secondStart in firstStart + 2 until lines.lastIndex) {
                if (normalized[firstStart] != normalized[secondStart]) continue

                var length = 0
                while (
                    firstStart + length < secondStart &&
                    secondStart + length < lines.size &&
                    normalized[firstStart + length].isNotBlank() &&
                    normalized[firstStart + length] == normalized[secondStart + length]
                ) {
                    length++
                }
                if (length < 2) continue

                val endLine = secondStart + length - 1
                candidates += LyricSection(
                    startLine = secondStart,
                    endLine = endLine,
                    startTimeMs = lines[secondStart].timeMs,
                    endTimeMs = lines[endLine].timeMs,
                )
            }
        }

        if (candidates.isEmpty()) return emptyList()
        return candidates
            .sortedBy { it.startLine }
            .fold(mutableListOf()) { merged, section ->
                val previous = merged.lastOrNull()
                if (previous != null && section.startLine <= previous.endLine + 1) {
                    merged[merged.lastIndex] = previous.copy(
                        endLine = maxOf(previous.endLine, section.endLine),
                        endTimeMs = maxOf(previous.endTimeMs, section.endTimeMs),
                    )
                } else {
                    merged += section
                }
                merged
            }
    }
}
