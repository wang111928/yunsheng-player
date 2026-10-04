package com.litemusic.shared.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileModelTest {
    @Test
    fun userDetailProfileKeepsTheBackgroundAndPlaylistCountReturnedByTheServer() {
        val profile = Json { ignoreUnknownKeys = true }.decodeFromString<Profile>(
            """{"userId":7,"backgroundUrl":"https://example.com/header.jpg","playlistCount":12,"description":"资料"}""",
        )

        assertEquals("https://example.com/header.jpg", profile.backgroundUrl)
        assertEquals(12, profile.playlistCount)
        assertEquals("资料", profile.description)
    }
}
