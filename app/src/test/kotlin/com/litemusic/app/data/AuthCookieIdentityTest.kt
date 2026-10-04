package com.litemusic.app.data

import com.litemusic.data.prefs.AuthStore
import org.junit.Assert.*
import org.junit.Test

class AuthCookieIdentityTest {
    @Test fun credentialReplacementClearsOldAccountProfile() {
        val before = AuthStore.Session(musicU = "accountA", userId = 42, nickname = "A", vip = true, vipLevel = 7)
        val after = sessionWithCookies(before, mapOf("MUSIC_U" to "accountB"))
        assertEquals("accountB", after.musicU)
        assertEquals(0L, after.userId); assertEquals("", after.nickname); assertFalse(after.vip)
    }
    @Test fun csrfRefreshPreservesSameAccount() {
        val before = AuthStore.Session(musicU = "accountA", userId = 42, nickname = "A")
        assertEquals(before.copy(csrf = "new"), sessionWithCookies(before, mapOf("__csrf" to "new")))
    }
}
