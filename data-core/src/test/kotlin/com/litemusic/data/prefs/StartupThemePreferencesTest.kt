package com.litemusic.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupThemePreferencesTest {
    @Test
    fun `keeps a valid stored theme value`() {
        assertEquals("starry_night", StartupThemePreferences.sanitize(" starry_night "))
    }

    @Test
    fun `falls back safely for absent or malformed stored values`() {
        assertEquals("light", StartupThemePreferences.sanitize(null))
        assertEquals("light", StartupThemePreferences.sanitize("   "))
        assertEquals("light", StartupThemePreferences.sanitize("x".repeat(65)))
    }
}
