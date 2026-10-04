package com.litemusic.app.feature.social

import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialRequestPresentationTest {
    @Test
    fun followTabCacheKeepsEachTabAndUserSeparate() {
        val cache = SocialTabCache()
        cache.put(
            1L,
            SocialViewModel.Tab.FOLLOWS,
            SocialTabSnapshot(listOf(Profile(userId = 10L)), hasMore = true, nextOffset = 30),
        )
        cache.put(
            1L,
            SocialViewModel.Tab.FOLLOWEDS,
            SocialTabSnapshot(listOf(Profile(userId = 20L)), hasMore = false, nextOffset = 1),
        )

        assertEquals(listOf(10L), cache.get(1L, SocialViewModel.Tab.FOLLOWS)?.users?.map { it.userId })
        assertEquals(listOf(20L), cache.get(1L, SocialViewModel.Tab.FOLLOWEDS)?.users?.map { it.userId })
        assertEquals(null, cache.get(2L, SocialViewModel.Tab.FOLLOWS))
    }

    @Test
    fun appendedFollowPageRemovesDuplicatesAndStopsWhenServerAddsNothing() {
        val current = listOf(Profile(userId = 10L), Profile(userId = 11L))
        val repeated = mergeSocialUsers(current, listOf(Profile(userId = 11L)))

        assertEquals(listOf(10L, 11L), repeated.map { it.userId })
        assertFalse(shouldContinueSocialPaging(current.size, repeated.size, serverMore = true))

        val extended = mergeSocialUsers(current, listOf(Profile(userId = 11L), Profile(userId = 12L)))
        assertEquals(listOf(10L, 11L, 12L), extended.map { it.userId })
        assertTrue(shouldContinueSocialPaging(current.size, extended.size, serverMore = true))
    }

    @Test
    fun ownProfileCannotFollowOrMessageItself() {
        assertTrue(isOwnProfile(profileUserId = 7L, accountUserId = 7L))
        assertFalse(isOwnProfile(profileUserId = 7L, accountUserId = 8L))
        assertFalse(isOwnProfile(profileUserId = 7L, accountUserId = 0L))
    }

    @Test
    fun followMutationUpdatesTheCachedTabInsteadOfRestoringTheOldButtonState() {
        val cache = SocialTabCache()
        cache.put(
            1L,
            SocialViewModel.Tab.FOLLOWS,
            SocialTabSnapshot(listOf(Profile(userId = 10L, followed = false)), hasMore = false, nextOffset = 1),
        )

        cache.updateFollowed(uid = 1L, userId = 10L, followed = true)

        assertTrue(cache.get(1L, SocialViewModel.Tab.FOLLOWS)!!.users.single().followed)
    }

    @Test
    fun staleFollowsResponseCannotReplaceTheNewlySelectedFollowersTab() {
        val tracker = SocialLoadTracker()
        val follows = tracker.begin(uid = 1L, tab = SocialViewModel.Tab.FOLLOWS)
        val followers = tracker.begin(uid = 1L, tab = SocialViewModel.Tab.FOLLOWEDS)

        assertFalse(tracker.isCurrent(follows))
        assertTrue(tracker.isCurrent(followers))
    }

    @Test
    fun followerRouteStartsOnTheFollowersTabWhileUnknownValuesUseFollows() {
        assertEquals(SocialViewModel.Tab.FOLLOWEDS, followsInitialTab(1))
        assertEquals(SocialViewModel.Tab.FOLLOWS, followsInitialTab(0))
        assertEquals(SocialViewModel.Tab.FOLLOWS, followsInitialTab(9))
    }

    @Test
    fun summaryPlaylistPlayOpensTheDetailRouteBeforeStartingPlayback() {
        val summary = Playlist(id = 123L, name = "摘要歌单", trackCount = 42)

        val action = profilePlaylistAction(summary)

        assertEquals(ProfilePlaylistAction.OPEN_DETAIL, action)
    }

    @Test
    fun detailedPlaylistCanStartPlaybackImmediately() {
        val detailed = Playlist(id = 123L, tracks = listOf(com.litemusic.shared.model.Song(id = 7L)))

        assertEquals(ProfilePlaylistAction.PLAY_LOADED_TRACKS, profilePlaylistAction(detailed))
    }
}
