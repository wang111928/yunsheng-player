package com.litemusic.design.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.litemusic.lyric.LyricUiLine

/**
 * 逐字歌词渲染：当前字变色，保持字宽与行高稳定。
 * @param currentLine 是否当前行
 * @param currentWord 当前字索引（-1 表示无逐字数据）
 */
@Composable
fun WordLyricText(
    line: LyricUiLine,
    isCurrent: Boolean,
    currentWord: Int,
    modifier: Modifier = Modifier,
    highlight: Color,
    normal: Color = Color(0x99FFFFFF),
) {
    val style = MaterialTheme.typography.titleLarge.copy(
        color = if (isCurrent) normal else normal.copy(alpha = 0.6f),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    if (line.words.isEmpty() || currentWord < 0) {
        BasicText(line.text, modifier = modifier, style = style)
        return
    }
    val annotated = buildAnnotatedString {
        line.words.forEachIndexed { i, w ->
            withStyle(
                SpanStyle(
                    color = if (isCurrent && i <= currentWord) highlight else if (isCurrent) normal else normal.copy(alpha = 0.6f),
                    fontSize = style.fontSize,
                )
            ) {
                append(w.text)
            }
        }
    }
    BasicText(annotated, modifier = modifier, style = style)
}
