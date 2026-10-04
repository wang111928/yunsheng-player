package com.litemusic.app.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCookieParserTest {
    @Test fun keepsOnlyCookiesUsedToCreateTheMusicSession() {
        val cookies = musicLoginCookies(
            "MUSIC_U=music-token; __csrf=csrf-token; NTES_YD_SESS=session-token; other=value",
        )

        assertEquals(
            mapOf(
                "MUSIC_U" to "music-token",
                "__csrf" to "csrf-token",
                "NTES_YD_SESS" to "session-token",
            ),
            cookies,
        )
    }

    @Test fun acceptsMissingOrUnrelatedCookieHeaders() {
        assertTrue(musicLoginCookies(null).isEmpty())
        assertTrue(musicLoginCookies("foo=bar; blank").isEmpty())
    }
}
