package com.litemusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.litemusic.design.theme.LocalNmlThemeKind
import com.litemusic.design.theme.NmThemeKind

@Composable
internal fun pageTopColor(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.background == Color.Black) Color.Black
        else lerp(colors.background, colors.primaryContainer, 0.26f)
}

/** One opaque page backdrop avoids stacking different translucent gradients in each tab. */
@Composable
internal fun Modifier.nmlPageBackground(): Modifier {
    val colors = MaterialTheme.colorScheme
    val themeKind = LocalNmlThemeKind.current
    val art = artSkinDrawable(themeKind)
    if (art != null) {
        val darkArt = themeKind == NmThemeKind.STARRY_NIGHT || themeKind == NmThemeKind.DREAM
        // The painting remains a real page skin. The gentle top/bottom veil protects app-bar and
        // long-list text while leaving the central composition visibly present.
        val veil = remember(colors.background, darkArt) {
            Brush.verticalGradient(
                listOf(
                    colors.background.copy(alpha = if (darkArt) 0.22f else 0.12f),
                    Color.Transparent,
                    colors.background.copy(alpha = if (darkArt) 0.54f else 0.38f),
                ),
            )
        }
        return background(colors.background)
            .paint(painter = painterResource(art), alpha = 0.64f, contentScale = ContentScale.Crop)
            .background(veil)
    }
    if (colors.background == Color.Black) return background(Color.Black)
    val top = pageTopColor()
    val base = colors.background
    val bottom = lerp(base, colors.surfaceVariant, 0.22f)
    val brush = remember(top, base, bottom) { Brush.verticalGradient(listOf(top, base, bottom)) }
    return background(brush)
}

