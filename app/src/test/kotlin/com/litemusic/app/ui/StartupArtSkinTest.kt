package com.litemusic.app.ui

import com.litemusic.design.theme.NmThemeKind
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupArtSkinTest {
    @Test
    fun `maps every art theme to its dedicated startup artwork`() {
        assertEquals(StartupArtSkin.STARRY_NIGHT, startupArtSkin(NmThemeKind.STARRY_NIGHT))
        assertEquals(StartupArtSkin.SUNRISE, startupArtSkin(NmThemeKind.SUNRISE))
        assertEquals(StartupArtSkin.LANDSCAPE, startupArtSkin(NmThemeKind.LANDSCAPE))
        assertEquals(StartupArtSkin.DREAM, startupArtSkin(NmThemeKind.DREAM))
    }

    @Test
    fun `non art and unknown themes do not select artwork`() {
        assertEquals(StartupArtSkin.NONE, startupArtSkin(NmThemeKind.LIGHT))
        assertEquals(StartupArtSkin.NONE, startupArtSkin("unknown-theme"))
    }
}
