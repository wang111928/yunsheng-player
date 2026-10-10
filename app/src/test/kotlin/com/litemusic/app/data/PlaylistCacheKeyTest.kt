package com.litemusic.app.data

import com.litemusic.data.prefs.AuthStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaylistCacheKeyTest {
    @Test
    fun privatePlaylistDetailCacheIsSeparatedByLoginIdentity() {
        val accountA = AuthStore.Session(userId = 1L, musicU = "account-a", loginAt = 10L)
        val accountB = AuthStore.Session(userId = 2L, musicU = "account-b", loginAt = 20L)

        assertNotEquals(
            playlistDetailCacheKey(77L, accountA),
            playlistDetailCacheKey(77L, accountB),
        )
    }

    @Test
    fun sameLoginKeepsItsDetailCacheAcrossProfileRefresh() {
        val before = AuthStore.Session(userId = 1L, musicU = "account-a", loginAt = 10L, nickname = "旧昵称")
        val after = before.copy(nickname = "新昵称")

        assertEquals(playlistDetailCacheKey(77L, before), playlistDetailCacheKey(77L, after))
    }

    @Test
    fun offlinePlaylistCacheUsesStableAccountIdInsteadOfLoginCredential() {
        val before = AuthStore.Session(userId = 1L, musicU = "old-cookie", loginAt = 10L)
        val relogin = AuthStore.Session(userId = 1L, musicU = "new-cookie", loginAt = 20L)

        assertEquals(
            offlinePlaylistDetailCacheKey(77L, before.userId),
            offlinePlaylistDetailCacheKey(77L, relogin.userId),
        )
    }
}
