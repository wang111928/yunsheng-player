package com.litemusic.design.components

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private val togetherVector = ImageVector.Builder("Together", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(14.7f, 13.4f)
        curveTo(16.1f, 12.2f, 17f, 10.4f, 17f, 8.5f)
        curveTo(17f, 4.9f, 13.6f, 2f, 9.5f, 2f)
        curveTo(5.4f, 2f, 2f, 4.9f, 2f, 8.5f)
        curveTo(2f, 10.6f, 3.1f, 12.5f, 4.8f, 13.7f)
        lineTo(3.8f, 17.4f)
        lineTo(8.1f, 15f)
        moveTo(16f, 9f)
        curveTo(19.4f, 9f, 22f, 11.6f, 22f, 14.7f)
        curveTo(22f, 16.4f, 21.2f, 18f, 19.9f, 19f)
        lineTo(20.5f, 22f)
        lineTo(17.1f, 20.3f)
        curveTo(12.7f, 21f, 9f, 18.4f, 9f, 14.7f)
        curveTo(9f, 11.6f, 11.8f, 9f, 16f, 9f)
    }
}.build()

/** 双对话气泡：一起听入口，与单人资料入口区分。 */
@Composable
fun TogetherIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "一起听",
) {
    Icon(togetherVector, contentDescription, modifier, tint)
}
