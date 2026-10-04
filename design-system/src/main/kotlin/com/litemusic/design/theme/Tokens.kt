package com.litemusic.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/** Semantic color tokens shared by the app and Material 3. */
@Immutable
data class NmlColorScheme(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onSurfaceMuted: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

/** Classic red-and-white palette. It intentionally absorbs the former NetEase-red choice. */
val NmlLightColors = NmlColorScheme(
    background = Color(0xFFFFE3E1), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFF7EAEB),
    outline = Color(0xFF806E73), outlineVariant = Color(0xFFEDD0D4), onSurface = Color(0xFF2C2023), onSurfaceVariant = Color(0xFF4F4145),
    onSurfaceMuted = Color(0xFF65555A), primary = Color(0xFFC40C1A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD9), onPrimaryContainer = Color(0xFF4B0006),
)

/** Midnight-blue palette with distinct card layers and high-contrast tertiary text. */
val NmlDarkColors = NmlColorScheme(
    background = Color(0xFF081225), surface = Color(0xFF192946), surfaceVariant = Color(0xFF293D61),
    outline = Color(0xFF91A1BC), outlineVariant = Color(0xFF3B4E6C), onSurface = Color(0xFFF5F7FB), onSurfaceVariant = Color(0xFFD2DAE7),
    onSurfaceMuted = Color(0xFFB9C4D4), primary = Color(0xFFFFB4AB), onPrimary = Color(0xFF680D13),
    primaryContainer = Color(0xFF7A1C24), onPrimaryContainer = Color(0xFFFFDAD6),
)

/** AMOLED black palette. Surfaces use visible tonal steps and borders in place of shadows. */
val NmlAmoledColors = NmlColorScheme(
    background = Color(0xFF000000), surface = Color(0xFF1C1F28), surfaceVariant = Color(0xFF2B3543),
    outline = Color(0xFF9DA8BB), outlineVariant = Color(0xFF465064), onSurface = Color(0xFFF5F7FB), onSurfaceVariant = Color(0xFFD2DAE7),
    onSurfaceMuted = Color(0xFFB9C4D4), primary = Color(0xFFFFB4AB), onPrimary = Color(0xFF680D13),
    primaryContainer = Color(0xFF7A1C24), onPrimaryContainer = Color(0xFFFFDAD6),
)

/** Independent NetEase red palette: a neutral white canvas with the recognizable red accent. */
val NmlNeteaseColors = NmlColorScheme(
    background = Color(0xFFFFE3E1), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFF7EAEB),
    outline = Color(0xFF806E73), outlineVariant = Color(0xFFEDD0D4), onSurface = Color(0xFF2C2023), onSurfaceVariant = Color(0xFF4F4145),
    onSurfaceMuted = Color(0xFF65555A), primary = Color(0xFFC40C1A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD9), onPrimaryContainer = Color(0xFF4B0006),
)

/** Calm, light fixed palettes used by the theme picker. */
val NmlMistLilacColors = NmlColorScheme(
    background = Color(0xFFF0E4F4), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE7D9EC),
    outline = Color(0xFF73617D), outlineVariant = Color(0xFFDBC9E4), onSurface = Color(0xFF292133), onSurfaceVariant = Color(0xFF51475D),
    onSurfaceMuted = Color(0xFF62566E), primary = Color(0xFF7B3FA0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAD7F7), onPrimaryContainer = Color(0xFF2D0D42),
)

val NmlMintColors = NmlColorScheme(
    background = Color(0xFFDBEEE7), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFD1E7DE),
    outline = Color(0xFF546F65), outlineVariant = Color(0xFFB7D4C8), onSurface = Color(0xFF1D3029), onSurfaceVariant = Color(0xFF40594F),
    onSurfaceMuted = Color(0xFF50685E), primary = Color(0xFF0B654F), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC0EBD9), onPrimaryContainer = Color(0xFF003C2E),
)

val NmlSeaSaltBlueColors = NmlColorScheme(
    background = Color(0xFFDCEAF7), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFD1E1F0),
    outline = Color(0xFF516D82), outlineVariant = Color(0xFFB5CCDF), onSurface = Color(0xFF1C2D3B), onSurfaceVariant = Color(0xFF40586B),
    onSurfaceMuted = Color(0xFF4D6578), primary = Color(0xFF0E6388), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC5E7F8), onPrimaryContainer = Color(0xFF003B56),
)

val NmlApricotColors = NmlColorScheme(
    background = Color(0xFFF9E6DA), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFF0DCD0),
    outline = Color(0xFF7F6255), outlineVariant = Color(0xFFE4C5B5), onSurface = Color(0xFF38251E), onSurfaceVariant = Color(0xFF60483D),
    onSurfaceMuted = Color(0xFF71584B), primary = Color(0xFF9A421B), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9C5), onPrimaryContainer = Color(0xFF3A0B00),
)

val NmlWarmCreamColors = NmlColorScheme(
    background = Color(0xFFF2E6D0), surface = Color(0xFFFFFDF9), surfaceVariant = Color(0xFFE9DCC5),
    outline = Color(0xFF75654B), outlineVariant = Color(0xFFD7C6A5), onSurface = Color(0xFF31291A), onSurfaceVariant = Color(0xFF564B37),
    onSurfaceMuted = Color(0xFF665B46), primary = Color(0xFF775815), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF2DFB0), onPrimaryContainer = Color(0xFF2B2000),
)

/** Palettes that sit below the four bundled painting backdrops. Cards remain opaque for readability. */
val NmlStarryNightColors = NmlColorScheme(
    background = Color(0xFF091B38), surface = Color(0xFF163058), surfaceVariant = Color(0xFF213A61),
    outline = Color(0xFFB6C8E6), outlineVariant = Color(0xFF40577C), onSurface = Color(0xFFF5F7FF), onSurfaceVariant = Color(0xFFD9E3F5),
    onSurfaceMuted = Color(0xFFC5D1E6), primary = Color(0xFF9ED8FF), onPrimary = Color(0xFF003353),
    primaryContainer = Color(0xFF174A73), onPrimaryContainer = Color(0xFFD1ECFF),
)

val NmlSunriseColors = NmlColorScheme(
    background = Color(0xFFF5E0D3), surface = Color(0xFFFFFCF8), surfaceVariant = Color(0xFFF0DED1),
    outline = Color(0xFF806358), outlineVariant = Color(0xFFE4C9BB), onSurface = Color(0xFF35231E), onSurfaceVariant = Color(0xFF5D443B),
    onSurfaceMuted = Color(0xFF6E5549), primary = Color(0xFFA5422E), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9CF), onPrimaryContainer = Color(0xFF471007),
)

val NmlLandscapeColors = NmlColorScheme(
    background = Color(0xFFD9E9E3), surface = Color(0xFFFFFEFA), surfaceVariant = Color(0xFFD5E5DF),
    outline = Color(0xFF4C6D6A), outlineVariant = Color(0xFFBBD4CC), onSurface = Color(0xFF173531), onSurfaceVariant = Color(0xFF385752),
    onSurfaceMuted = Color(0xFF4A6861), primary = Color(0xFF076B74), onPrimary = Color.White,
    primaryContainer = Color(0xFFB7E6E3), onPrimaryContainer = Color(0xFF003E45),
)

val NmlDreamColors = NmlColorScheme(
    background = Color(0xFF0D302C), surface = Color(0xFF17443C), surfaceVariant = Color(0xFF26594E),
    outline = Color(0xFFB4D6C8), outlineVariant = Color(0xFF416F63), onSurface = Color(0xFFF1FFF8), onSurfaceVariant = Color(0xFFD2EBDD),
    onSurfaceMuted = Color(0xFFBCD8CA), primary = Color(0xFFFFB3C3), onPrimary = Color(0xFF5C1025),
    primaryContainer = Color(0xFF803045), onPrimaryContainer = Color(0xFFFFD9E1),
)

/** WCAG contrast ratio after alpha-compositing [foreground] over an opaque [background]. */
internal fun contrastRatio(foreground: Color, background: Color): Double {
    val alpha = foreground.alpha + background.alpha * (1f - foreground.alpha)
    if (alpha == 0f) return 1.0
    fun composite(foregroundChannel: Float, backgroundChannel: Float): Double =
        ((foregroundChannel * foreground.alpha + backgroundChannel * background.alpha * (1f - foreground.alpha)) / alpha).toDouble()
    fun linear(channel: Double): Double =
        if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    fun luminance(red: Double, green: Double, blue: Double): Double =
        0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    val foregroundLuminance = luminance(
        composite(foreground.red, background.red), composite(foreground.green, background.green), composite(foreground.blue, background.blue),
    )
    val backgroundLuminance = luminance(background.red.toDouble(), background.green.toDouble(), background.blue.toDouble())
    return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) / (minOf(foregroundLuminance, backgroundLuminance) + 0.05)
}

val LocalNmlColors = staticCompositionLocalOf { NmlLightColors }

object NmlTheme {
    val colors: NmlColorScheme
        @Composable @ReadOnlyComposable get() = LocalNmlColors.current
}

object NmlDim {
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space20 = 20.dp
    val space24 = 24.dp
    val space32 = 32.dp

    val radius6 = 8.dp
    val radius10 = 12.dp
    val radius14 = 18.dp
    val radius20 = 22.dp
    val radius28 = 30.dp

    val cover = 112.dp
    val coverL = 134.dp
    val avatar = 66.dp
    val disc = 284.dp
    val discArt = 186.dp
    val playBtn = 66.dp

    val touchMin = 44.dp
    val topBar = 56.dp
    val navBar = 56.dp
    val miniPlayer = 54.dp
}
