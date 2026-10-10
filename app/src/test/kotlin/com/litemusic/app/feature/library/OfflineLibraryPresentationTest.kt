package com.litemusic.app.feature.library

import com.litemusic.shared.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineLibraryPresentationTest {
    @Test
    fun offlineDirectoryShowsOnlyPlaylistsWithDurableDetails() {
        val playlists = listOf(Playlist(id = 1L), Playlist(id = 2L), Playlist(id = 3L))

        assertEquals(listOf(2L), offlinePlaylistDirectory(playlists, setOf(2L, 9L)).map { it.id })
    }
}
