package com.litemusic.design.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test fun contrastUsesTheStandardSrgbTransferFunction() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.001)
        assertEquals(3.949439, contrastRatio(Color(0xFF808080), Color.White), 0.0001)
    }

    @Test fun dynamicResolutionUsesSystemPaletteOnlyWhenSupported() {
        assertEquals(NmlThemePalette.DYNAMIC, resolveNmlThemePalette(NmThemeKind.DYNAMIC, systemDark = false, dynamicSupported = true))
        assertEquals(NmlThemePalette.LIGHT, resolveNmlThemePalette(NmThemeKind.DYNAMIC, systemDark = false, dynamicSupported = false))
        assertEquals(NmlThemePalette.DARK, resolveNmlThemePalette(NmThemeKind.DYNAMIC, systemDark = true, dynamicSupported = false))
    }

    @Test fun mergedThemeAliasesResolveToTheCanonicalPalette() {
        assertEquals(NmlThemePalette.LIGHT, resolveNmlThemePalette(NmThemeKind.LIGHT, false, false))
        assertEquals(NmlThemePalette.LIGHT, resolveNmlThemePalette(NmThemeKind.NETEASE, true, true))
        assertEquals(NmThemeKind.LIGHT, canonicalNmlThemeKind(NmThemeKind.NETEASE))
        assertEquals(NmlThemePalette.DARK, resolveNmlThemePalette(NmThemeKind.DARK, false, true))
        assertEquals(NmlThemePalette.APRICOT, resolveNmlThemePalette(NmThemeKind.WARM_CREAM, true, true))
        assertEquals(NmThemeKind.APRICOT, canonicalNmlThemeKind(NmThemeKind.WARM_CREAM))
        assertEquals(NmlThemePalette.AMOLED, resolveNmlThemePalette(NmThemeKind.AMOLED, false, true))
        assertEquals(NmlThemePalette.DARK, resolveNmlThemePalette("unknown", false, true))
    }

    @Test fun additionalFixedThemeKindsAreSelectableAndResolveIndependently() {
        val expected = setOf(
            NmThemeKind.DYNAMIC,
            NmThemeKind.LIGHT,
            NmThemeKind.DARK,
            NmThemeKind.AMOLED,
            NmThemeKind.MIST_LILAC,
            NmThemeKind.MINT,
            NmThemeKind.SEA_SALT_BLUE,
            NmThemeKind.APRICOT,
            NmThemeKind.STARRY_NIGHT,
            NmThemeKind.SUNRISE,
            NmThemeKind.LANDSCAPE,
            NmThemeKind.DREAM,
        )
        assertEquals(expected, NmlThemeOptions.map { it.id }.toSet())
        assertEquals(12, NmlThemeOptions.size)
        assertEquals(NmlThemePalette.MIST_LILAC, resolveNmlThemePalette(NmThemeKind.MIST_LILAC, false, false))
        assertEquals(NmlThemePalette.MINT, resolveNmlThemePalette(NmThemeKind.MINT, false, false))
        assertEquals(NmlThemePalette.SEA_SALT_BLUE, resolveNmlThemePalette(NmThemeKind.SEA_SALT_BLUE, false, false))
        assertEquals(NmlThemePalette.APRICOT, resolveNmlThemePalette(NmThemeKind.APRICOT, false, false))
        assertEquals(NmlThemePalette.STARRY_NIGHT, resolveNmlThemePalette(NmThemeKind.STARRY_NIGHT, false, false))
        assertEquals(NmlThemePalette.SUNRISE, resolveNmlThemePalette(NmThemeKind.SUNRISE, false, false))
        assertEquals(NmlThemePalette.LANDSCAPE, resolveNmlThemePalette(NmThemeKind.LANDSCAPE, false, false))
        assertEquals(NmlThemePalette.DREAM, resolveNmlThemePalette(NmThemeKind.DREAM, false, false))
    }

    @Test fun staticTextAndPrimaryColorsMeetContrastRequirements() {
        allStaticThemes.forEach { colors ->
            listOf(colors.background, colors.surface, colors.surfaceVariant).forEach { background ->
                assertAtLeast("body", colors.onSurface, background, 4.5)
                assertAtLeast("secondary", colors.onSurfaceVariant, background, 4.5)
                assertAtLeast("muted", colors.onSurfaceMuted, background, 4.5)
            }
            assertAtLeast("onPrimary", colors.onPrimary, colors.primary, 4.5)
            assertAtLeast("primary on primaryContainer", colors.primary, colors.primaryContainer, 4.5)
            assertAtLeast("onPrimaryContainer", colors.onPrimaryContainer, colors.primaryContainer, 4.5)
        }
    }

    @Test fun staticSurfaceLayersAndOutlinesRemainVisible() {
        allStaticThemes.forEach { colors ->
            assertTrue("surface must differ from background", contrastRatio(colors.surface, colors.background) >= 1.2)
            assertTrue("surface variant must differ from surface", contrastRatio(colors.surfaceVariant, colors.surface) >= 1.15)
            assertTrue("outline must remain visible over surface", contrastRatio(colors.outline, colors.surface) >= 1.5)
            assertAtLeast("control outline on surface", colors.outline, colors.surface, 3.0)
            assertAtLeast("control outline on surfaceVariant", colors.outline, colors.surfaceVariant, 3.0)
        }
    }

    @Test fun decorativeBordersAreSofterThanControlOutlines() {
        allStaticThemes.forEach { colors ->
            assertTrue("decorative borders must not frame every card like an input",
                contrastRatio(colors.outlineVariant, colors.surface) < contrastRatio(colors.outline, colors.surface))
        }
    }

    @Test fun staticPageGradientBackgroundsKeepSmallTextReadable() {
        allStaticThemes.filter { it != NmlAmoledColors }.forEach { colors ->
            val top = blend(colors.background, colors.primaryContainer, 0.26f)
            val bottom = blend(colors.background, colors.surfaceVariant, 0.22f)
            listOf(top, bottom).forEach { background ->
                assertAtLeast("page body", colors.onSurface, background, 4.5)
                assertAtLeast("page secondary", colors.onSurfaceVariant, background, 4.5)
                assertAtLeast("page small text", colors.onSurfaceMuted, background, 4.5)
            }
        }
        assertAtLeast("AMOLED page small text", NmlAmoledColors.onSurfaceMuted, NmlAmoledColors.background, 4.5)
    }

    private fun assertAtLeast(label: String, foreground: Color, background: Color, minimum: Double) {
        assertTrue("$label contrast was ${contrastRatio(foreground, background)}", contrastRatio(foreground, background) >= minimum)
    }

    private fun blend(start: Color, end: Color, fraction: Float): Color = Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = start.alpha + (end.alpha - start.alpha) * fraction,
    )

    private val allStaticThemes = listOf(
        NmlLightColors,
        NmlDarkColors,
        NmlAmoledColors,
        NmlMistLilacColors,
        NmlMintColors,
        NmlSeaSaltBlueColors,
        NmlApricotColors,
        NmlStarryNightColors,
        NmlSunriseColors,
        NmlLandscapeColors,
        NmlDreamColors,
    )
}
