package com.litemusic.lyric

import androidx.compose.runtime.Immutable

@Immutable
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

@Immutable
data class Word(
    val timeMs: Long,
    val durMs: Long,
    val text: String,
)

@Immutable
data class YrcLine(
    val timeMs: Long,
    val words: List<Word>,
) {
    val text: String get() = words.joinToString("") { it.text }
}

enum class LyricSource { NONE, LRC, YRC }

@Immutable
data class LyricDoc(
    val lines: List<LyricLine> = emptyList(),
    val translations: Map<Int, String> = emptyMap(),
    val wordLines: Map<Int, List<Word>> = emptyMap(),
    val offsetMs: Long = 0L,
    val source: LyricSource = LyricSource.NONE,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

@Immutable
data class LyricUiLine(
    val timeMs: Long,
    val text: String,
    val translation: String = "",
    val words: List<Word> = emptyList(),
)

enum class LyricSectionSource { HEURISTIC }

@Immutable
data class LyricSection(
    val startLine: Int,
    val endLine: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val label: String = "高潮",
    val source: LyricSectionSource = LyricSectionSource.HEURISTIC,
)
