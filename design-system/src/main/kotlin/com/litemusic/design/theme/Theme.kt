package com.litemusic.design.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Theme choices persisted by SettingsStore. */
object NmThemeKind {
    const val DYNAMIC = "dynamic"
    const val LIGHT = "light"
    const val DARK = "dark"
    const val NETEASE = "netease"
    const val AMOLED = "amoled"
    const val MIST_LILAC = "mist_lilac"
    const val MINT = "mint"
    const val SEA_SALT_BLUE = "sea_salt_blue"
    const val APRICOT = "apricot"
    const val WARM_CREAM = "warm_cream"
    const val STARRY_NIGHT = "starry_night"
    const val SUNRISE = "sunrise"
    const val LANDSCAPE = "landscape"
    const val DREAM = "dream"
}

/** A persisted theme identifier with its small, UI-safe preview colour. */
data class NmlThemeOption(
    val id: String,
    val label: String,
    val previewColor: Color,
    val isArtSkin: Boolean = false,
)

val NmlThemeOptions = listOf(
    NmlThemeOption(NmThemeKind.DYNAMIC, "跟随系统", Color(0xFF7E8CA3)),
    NmlThemeOption(NmThemeKind.LIGHT, "经典红白", NmlLightColors.primary),
    NmlThemeOption(NmThemeKind.DARK, "午夜深蓝", NmlDarkColors.primary),
    NmlThemeOption(NmThemeKind.AMOLED, "AMOLED", NmlAmoledColors.surfaceVariant),
    NmlThemeOption(NmThemeKind.MIST_LILAC, "雾紫", NmlMistLilacColors.primary),
    NmlThemeOption(NmThemeKind.MINT, "薄荷", NmlMintColors.primary),
    NmlThemeOption(NmThemeKind.SEA_SALT_BLUE, "海盐蓝", NmlSeaSaltBlueColors.primary),
    NmlThemeOption(NmThemeKind.APRICOT, "暖杏米白", NmlApricotColors.primary),
    NmlThemeOption(NmThemeKind.STARRY_NIGHT, "星空", NmlStarryNightColors.primary, isArtSkin = true),
    NmlThemeOption(NmThemeKind.SUNRISE, "日出·印象", NmlSunriseColors.primary, isArtSkin = true),
    NmlThemeOption(NmThemeKind.LANDSCAPE, "千里江山", NmlLandscapeColors.primary, isArtSkin = true),
    NmlThemeOption(NmThemeKind.DREAM, "梦", NmlDreamColors.primary, isArtSkin = true),
)

/** Pure selection result, kept Android-free so system-dark fallbacks are JVM-testable. */
internal enum class NmlThemePalette { DYNAMIC, LIGHT, DARK, AMOLED, MIST_LILAC, MINT, SEA_SALT_BLUE, APRICOT, STARRY_NIGHT, SUNRISE, LANDSCAPE, DREAM }

/**
 * The two retired keys remain readable so an existing installation never falls back to dark.
 * New selections always store the canonical merged identifier.
 */
fun canonicalNmlThemeKind(themeKind: String): String = when (themeKind) {
    NmThemeKind.NETEASE -> NmThemeKind.LIGHT
    NmThemeKind.WARM_CREAM -> NmThemeKind.APRICOT
    else -> themeKind
}

fun isNmlArtTheme(themeKind: String): Boolean = when (canonicalNmlThemeKind(themeKind)) {
    NmThemeKind.STARRY_NIGHT, NmThemeKind.SUNRISE, NmThemeKind.LANDSCAPE, NmThemeKind.DREAM -> true
    else -> false
}

val LocalNmlThemeKind = staticCompositionLocalOf { NmThemeKind.LIGHT }

internal fun resolveNmlThemePalette(themeKind: String, systemDark: Boolean, dynamicSupported: Boolean): NmlThemePalette = when (canonicalNmlThemeKind(themeKind)) {
    NmThemeKind.DYNAMIC -> if (dynamicSupported) NmlThemePalette.DYNAMIC else if (systemDark) NmlThemePalette.DARK else NmlThemePalette.LIGHT
    NmThemeKind.LIGHT -> NmlThemePalette.LIGHT
    NmThemeKind.AMOLED -> NmlThemePalette.AMOLED
    NmThemeKind.MIST_LILAC -> NmlThemePalette.MIST_LILAC
    NmThemeKind.MINT -> NmlThemePalette.MINT
    NmThemeKind.SEA_SALT_BLUE -> NmlThemePalette.SEA_SALT_BLUE
    NmThemeKind.APRICOT -> NmlThemePalette.APRICOT
    NmThemeKind.STARRY_NIGHT -> NmlThemePalette.STARRY_NIGHT
    NmThemeKind.SUNRISE -> NmlThemePalette.SUNRISE
    NmThemeKind.LANDSCAPE -> NmlThemePalette.LANDSCAPE
    NmThemeKind.DREAM -> NmlThemePalette.DREAM
    NmThemeKind.DARK -> NmlThemePalette.DARK
    else -> NmlThemePalette.DARK
}

private fun NmlThemePalette.tokens(): NmlColorScheme = when (this) {
    NmlThemePalette.LIGHT -> NmlLightColors
    NmlThemePalette.DARK -> NmlDarkColors
    NmlThemePalette.AMOLED -> NmlAmoledColors
    NmlThemePalette.MIST_LILAC -> NmlMistLilacColors
    NmlThemePalette.MINT -> NmlMintColors
    NmlThemePalette.SEA_SALT_BLUE -> NmlSeaSaltBlueColors
    NmlThemePalette.APRICOT -> NmlApricotColors
    NmlThemePalette.STARRY_NIGHT -> NmlStarryNightColors
    NmlThemePalette.SUNRISE -> NmlSunriseColors
    NmlThemePalette.LANDSCAPE -> NmlLandscapeColors
    NmlThemePalette.DREAM -> NmlDreamColors
    NmlThemePalette.DYNAMIC -> NmlLightColors
}

private fun lightOf(c: NmlColorScheme) = lightColorScheme(
    primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
    inversePrimary = c.primary, secondary = c.primary, onSecondary = c.onPrimary, secondaryContainer = c.primaryContainer,
    onSecondaryContainer = c.onPrimaryContainer, tertiary = c.onSurfaceVariant, onTertiary = c.surface,
    tertiaryContainer = c.surfaceVariant, onTertiaryContainer = c.onSurface, background = c.background, onBackground = c.onSurface,
    surface = c.surface, onSurface = c.onSurface, surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.onSurfaceVariant,
    surfaceTint = c.primary, inverseSurface = Color(0xFF20242D), inverseOnSurface = Color(0xFFF6F6FA), error = Color(0xFFBA1A1A),
    onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002), outline = c.outline,
    outlineVariant = c.outlineVariant, scrim = Color.Black, surfaceBright = c.surface, surfaceDim = c.surfaceVariant,
    surfaceContainer = c.surfaceVariant, surfaceContainerHigh = c.surfaceVariant, surfaceContainerHighest = c.surfaceVariant,
    surfaceContainerLow = c.surface, surfaceContainerLowest = c.surface,
)

private fun darkOf(c: NmlColorScheme) = darkColorScheme(
    primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
    inversePrimary = c.primary, secondary = c.primary, onSecondary = c.onPrimary, secondaryContainer = c.primaryContainer,
    onSecondaryContainer = c.onPrimaryContainer, tertiary = c.onSurfaceVariant, onTertiary = c.surface,
    tertiaryContainer = c.surfaceVariant, onTertiaryContainer = c.onSurface, background = c.background, onBackground = c.onSurface,
    surface = c.surface, onSurface = c.onSurface, surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.onSurfaceVariant,
    surfaceTint = c.primary, inverseSurface = Color(0xFFF5F6FA), inverseOnSurface = Color(0xFF151923), error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6), outline = c.outline,
    outlineVariant = c.outlineVariant, scrim = Color.Black, surfaceBright = c.surfaceVariant, surfaceDim = c.background,
    surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceVariant, surfaceContainerHighest = c.surfaceVariant,
    surfaceContainerLow = c.surface, surfaceContainerLowest = c.background,
)

private fun ColorScheme.toNmlTokens() = NmlColorScheme(
    background = background, surface = surface, surfaceVariant = surfaceVariant, outline = outline, outlineVariant = outlineVariant, onSurface = onSurface,
    onSurfaceVariant = onSurfaceVariant, onSurfaceMuted = onSurfaceVariant, primary = primary, onPrimary = onPrimary,
    primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
)

@Composable
fun NmTheme(themeKind: String = NmThemeKind.DARK, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val palette = remember(themeKind, systemDark, dynamicSupported) { resolveNmlThemePalette(themeKind, systemDark, dynamicSupported) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val colorScheme = remember(palette, systemDark, context, configuration) {
        when (palette) {
            NmlThemePalette.DYNAMIC -> if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            NmlThemePalette.LIGHT, NmlThemePalette.MIST_LILAC,
            NmlThemePalette.MINT, NmlThemePalette.SEA_SALT_BLUE, NmlThemePalette.APRICOT,
            NmlThemePalette.SUNRISE, NmlThemePalette.LANDSCAPE -> lightOf(palette.tokens())
            NmlThemePalette.DARK, NmlThemePalette.AMOLED, NmlThemePalette.STARRY_NIGHT,
            NmlThemePalette.DREAM -> darkOf(palette.tokens())
        }
    }
    val tokens = remember(colorScheme, palette) { if (palette == NmlThemePalette.DYNAMIC) colorScheme.toNmlTokens() else palette.tokens() }
    CompositionLocalProvider(
        LocalNmlColors provides tokens,
        LocalNmlThemeKind provides canonicalNmlThemeKind(themeKind),
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = NmTypography,
            shapes = NmlShapes, content = content)
    }
}

private val NmlShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
