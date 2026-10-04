package com.litemusic.app.data

import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.PlaylistTrackId
import com.litemusic.shared.model.Song
import com.litemusic.shared.model.SongDetailResponse
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistTrackResolutionTest {
    @Test
    fun importMembershipUsesAllIdsWithoutRequiringExpandedSongMetadata() {
        val result = playlistMembershipForImport(Playlist(
            trackCount = 155,
            tracks = (1L..20L).map { Song(id = it) },
            trackIds = (1L..155L).map { PlaylistTrackId(it) },
        ))
        assertEquals((1L..155L).toSet(), (result as AppResult.Success).data)
    }

    @Test
    fun incompleteTargetMembershipStopsImportInsteadOfAssumingAnEmptyTarget() {
        val result = playlistMembershipForImport(Playlist(
            trackCount = 155, tracks = (1L..20L).map { Song(id = it) },
        ))
        assertEquals(-1, (result as AppResult.Failure).code)
    }

    @Test
    fun completeEmbeddedOnlyOrEmptyTargetCanStillBeUsedForImport() {
        assertEquals(setOf(1L, 2L), (playlistMembershipForImport(Playlist(
            trackCount = 2, tracks = listOf(Song(id = 1), Song(id = 2)),
        )) as AppResult.Success).data)
        assertEquals(emptySet<Long>(), (playlistMembershipForImport(Playlist()) as AppResult.Success).data)
    }

    @Test
    fun embeddedTwentySongPrefixIsExpandedToAll155IdsWithoutRefetchingThePrefix() = runTest {
        val requested = mutableListOf<List<Long>>()
        val result = resolvePlaylistTracks(
            Playlist(
                trackCount = 155,
                tracks = (1L..20L).map { Song(id = it) },
                trackIds = (1L..155L).map { PlaylistTrackId(it) },
            ),
            limit = 500,
        ) { ids ->
            requested += ids
            AppResult.Success(SongDetailResponse(code = 200, songs = ids.reversed().map { Song(id = it) }))
        }

        assertEquals(listOf(100, 35), requested.map { it.size })
        assertEquals((21L..155L).toList(), requested.flatten())
        assertEquals((1L..155L).toList(), (result as AppResult.Success).data.map { it.id })
    }

    @Test
    fun completeEmbeddedSongsStillFollowPlaylistIdsWithoutExtraRequests() = runTest {
        val result = resolvePlaylistTracks(
            Playlist(
                trackCount = 3,
                tracks = listOf(Song(id = 1), Song(id = 2), Song(id = 3)),
                trackIds = listOf(PlaylistTrackId(3), PlaylistTrackId(1), PlaylistTrackId(2)),
            ),
            limit = 500,
        ) { error("Complete embedded metadata must not be fetched again") }

        assertEquals(listOf(3L, 1L, 2L), (result as AppResult.Success).data.map { it.id })
    }

    @Test
    fun partialEmbeddedPreviewResolvesOnly500DistinctValidIds() = runTest {
        val requested = mutableListOf<Long>()
        val result = resolvePlaylistTracks(
            Playlist(
                trackCount = 2000,
                tracks = (1L..20L).map { Song(id = it) },
                trackIds = listOf(PlaylistTrackId(0), PlaylistTrackId(-1), PlaylistTrackId(1)) +
                    (1L..2000L).map { PlaylistTrackId(it) },
            ),
            limit = 500,
        ) { ids ->
            requested += ids
            AppResult.Success(SongDetailResponse(code = 200, songs = ids.map { Song(id = it) }))
        }

        assertEquals((21L..500L).toList(), requested)
        assertEquals((1L..500L).toList(), (result as AppResult.Success).data.map { it.id })
    }

    @Test
    fun embeddedPrefixMustNotHideARequestFailureForTheRemainingSongs() = runTest {
        val result = resolvePlaylistTracks(
            Playlist(
                trackCount = 2,
                tracks = listOf(Song(id = 1)),
                trackIds = listOf(PlaylistTrackId(1), PlaylistTrackId(2)),
            ),
            limit = 500,
        ) { AppResult.Success(SongDetailResponse(code = 405)) }

        assertEquals(405, (result as AppResult.Failure).code)
    }

    @Test
    fun idOnlyPlaylistResolvesSongsInPlaylistOrder() = runTest {
        val requested = mutableListOf<List<Long>>()
        val result = resolvePlaylistTracks(
            playlist = Playlist(
                trackCount = 3,
                trackIds = listOf(PlaylistTrackId(3), PlaylistTrackId(1), PlaylistTrackId(2)),
            ),
            limit = 2,
        ) { ids ->
            requested += ids
            AppResult.Success(SongDetailResponse(code = 200, songs = listOf(Song(id = 1), Song(id = 3))))
        }

        assertEquals(listOf(listOf(3L, 1L)), requested)
        assertEquals(listOf(3L, 1L), (result as AppResult.Success<List<Song>>).data.map { it.id })
    }

    @Test
    fun songDetailBusinessErrorDoesNotBecomeEmptyPlaylist() = runTest {
        val result = resolvePlaylistTracks(
            playlist = Playlist(trackCount = 1, trackIds = listOf(PlaylistTrackId(7))),
            limit = 30,
        ) { AppResult.Success(SongDetailResponse(code = 405)) }

        assertEquals(405, (result as AppResult.Failure).code)
    }

    @Test
    fun largeImportPreviewResolvesOnlyFiveHundredSongs() = runTest {
        val requested = mutableListOf<List<Long>>()
        val result = resolvePlaylistTracks(
            playlist = Playlist(trackCount = 2000, trackIds = (1L..2000L).map { PlaylistTrackId(it) }),
            limit = 500,
        ) { ids ->
            requested += ids
            AppResult.Success(SongDetailResponse(code = 200, songs = ids.map { Song(id = it) }))
        }
        assertEquals(5, requested.size)
        assertEquals((1L..500L).toList(), requested.flatten())
        assertEquals(500, (result as AppResult.Success).data.size)
    }

    @Test
    fun sixHundredSixtyNineLikedSongIdsResolveInLikeOrderAcrossSevenBatches() = runTest {
        val ids = (1L..669L).toList().reversed()
        val requested = mutableListOf<List<Long>>()

        val result = resolveSongIds(ids) { batch ->
            requested += batch
            AppResult.Success(SongDetailResponse(code = 200, songs = batch.reversed().map { Song(id = it) }))
        }

        assertEquals(listOf(100, 100, 100, 100, 100, 100, 69), requested.map { it.size })
        assertEquals(ids, (result as AppResult.Success).data.map { it.id })
    }

    @Test
    fun nonEmptyLikedIdsWithNoDetailResultBecomeAnError() = runTest {
        val result = resolveSongIds(listOf(7L)) {
            AppResult.Success(SongDetailResponse(code = 200))
        }

        assertEquals(-1, (result as AppResult.Failure).code)
    }

    @Test
    fun likedSongDetailBusinessErrorIsPropagatedInsteadOfShowingEmpty() = runTest {
        val result = resolveSongIds(listOf(7L)) {
            AppResult.Success(SongDetailResponse(code = 405))
        }

        assertEquals(405, (result as AppResult.Failure).code)
    }
}
