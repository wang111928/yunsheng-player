package com.litemusic.app.data

import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.PlaylistTrackId
import com.litemusic.shared.model.Song
import org.junit.Assert.*
import org.junit.Test

class PlaylistOfflineMutationTest {
    @Test fun confirmedRemovalPreservesRemainingMetadataAndCountsMembersOnce() {
        val playlist = Playlist(id = 77, name = "自己的歌单", userId = 9, description = "保留描述", trackCount = 3,
            trackIds = listOf(PlaylistTrackId(1), PlaylistTrackId(2), PlaylistTrackId(3)),
            tracks = listOf(Song(id = 1, name = "一"), Song(id = 2, name = "二"), Song(id = 3, name = "三")))
        val updated = playlistAfterRemovingTracks(playlist, listOf(2, 2, 999, -1))
        assertEquals(2, updated.trackCount)
        assertEquals(listOf(1L, 3L), updated.trackIds.map { it.id })
        assertEquals(listOf("一", "三"), updated.tracks.map { it.name })
        assertEquals(playlist.description, updated.description)
        assertEquals(playlist.userId, updated.userId)
    }
    @Test fun catalogOnlyHeaderReflectsConfirmedDeletionWithoutGoingNegative() {
        val header = Playlist(id = 77, trackCount = 2)
        assertEquals(1, playlistAfterRemovingTracks(header, listOf(2, 2)).trackCount)
        assertEquals(0, playlistAfterRemovingTracks(header, listOf(1, 2, 3)).trackCount)
    }
}
