package com.litemusic.app.feature.social

import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Comment
import com.litemusic.shared.api.EventPost
import com.litemusic.app.data.NotesRepository
import com.litemusic.app.data.UserEventPage
import com.litemusic.app.feature.notes.EventCommentsState
import com.litemusic.app.feature.notes.afterComment
import com.litemusic.app.feature.notes.afterLike
import com.litemusic.shared.util.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfilePresentationTest {
    @Test
    fun profileLoadFailureRetainsTheMostRecentSuccessfulProfile() {
        val retained = UserProfileViewModel.UiState(
            loading = false,
            userId = 9L,
            nickname = "仍可展示的资料",
            avatar = "https://example.invalid/avatar.jpg",
            follows = 12,
        )

        val next = profileStateAfterLoadFailure(retained, 9L, "网络错误", isSelf = true)

        assertFalse(next.loading)
        assertEquals("仍可展示的资料", next.nickname)
        assertEquals(12, next.follows)
        assertEquals("网络错误", next.error)
        assertTrue(next.isSelf)
    }

    @Test
    fun profileLoadFailureDoesNotCarrySelfStateToAnotherUser() {
        val retained = UserProfileViewModel.UiState(
            loading = false,
            userId = 9L,
            nickname = "我的资料",
            isSelf = true,
        )

        val next = profileStateAfterLoadFailure(
            previous = retained,
            userId = 10L,
            message = "网络错误",
            isSelf = false,
        )

        assertEquals(10L, next.userId)
        assertEquals("", next.nickname)
        assertFalse(next.isSelf)
    }

    @Test
    fun followCannotStartAgainWhileThePreviousRequestIsInFlight() {
        assertFalse(canStartProfileFollow(UserProfileViewModel.UiState(userId = 9L, followInFlight = true)))
        assertFalse(canStartProfileFollow(UserProfileViewModel.UiState(userId = 0L)))
        assertTrue(canStartProfileFollow(UserProfileViewModel.UiState(userId = 9L)))
    }

    @Test
    fun availableNetEaseProfileFieldsAreRenderedAsFacts() {
        val facts = profileFactRows(
            level = 8,
            listenSongs = 1234,
            eventCount = 6,
            gender = 2,
            birthday = 946684800000L,
            playlistCount = 4,
        )

        assertTrue(facts.contains("等级 8"))
        assertTrue(facts.contains("听歌 1234 首"))
        assertTrue(facts.contains("动态 6 条"))
        assertTrue(facts.contains("女"))
        assertTrue(facts.contains("生日 2000-01-01"))
        assertTrue(facts.contains("歌单 4 个"))
    }

    @Test
    fun profileHeaderKeepsFollowFanAndLevelAsSeparateReadableStatistics() {
        val stats = profileHeaderStats(
            follows = 68,
            followeds = 640_461,
            level = 8,
            listenSongs = 5_891,
        )

        assertEquals(
            listOf("关注" to "68", "粉丝" to "640461", "等级" to "Lv.8", "听歌" to "5891"),
            stats.map { it.label to it.value },
        )
    }

    @Test
    fun longSignatureStartsCollapsedAndCanBeExpandedWithoutLosingText() {
        val signature = "喜欢收集现场音乐和黑胶唱片，也喜欢把旅行中听到的旋律写进歌单。欢迎来听我的收藏，愿每一首歌都能陪你度过一个安静的夜晚。"

        val collapsed = profileSignaturePresentation(signature, expanded = false, collapsedCharacterLimit = 24)
        val expanded = profileSignaturePresentation(signature, expanded = true, collapsedCharacterLimit = 24)

        assertTrue(collapsed.canExpand)
        assertTrue(collapsed.text.endsWith("…"))
        assertEquals(signature, expanded.text)
        assertTrue(expanded.expanded)
    }

    @Test
    fun shortSignatureDoesNotOfferAnEmptyExpandControl() {
        val presentation = profileSignaturePresentation("普通人的生活", expanded = true)

        assertFalse(presentation.canExpand)
        assertFalse(presentation.expanded)
        assertEquals("普通人的生活", presentation.text)
    }

    @Test
    fun largeProfileCountsUseTheSameCompactUnitSeenInTheHeader() {
        assertEquals("68", compactProfileCount(68))
        assertEquals("64.0万", compactProfileCount(640_461))
    }

    @Test
    fun playlistLoadFailureIsVisibleWithoutDiscardingTheProfile() {
        assertEquals(
            "服务暂时不可用",
            profilePlaylistLoadError(AppResult.Failure(code = 500, message = "服务暂时不可用")),
        )
        assertEquals(
            "歌单读取失败，请稍后重试",
            profilePlaylistLoadError(AppResult.Failure(code = 500, message = "")),
        )
        assertEquals(null, profilePlaylistLoadError(AppResult.Success(emptyList())))
    }

    @Test
    fun failedPlaylistRefreshRetainsVisibleRowsAndCount() {
        val retained = listOf(Playlist(id = 7L, name = "原有歌单"))
        val result = AppResult.Failure(code = 500, message = "服务暂时不可用")

        assertEquals(retained, profilePlaylistsAfterLoad(result, retained))
        assertEquals(3, profilePlaylistCount(serverCount = 0, playlists = emptyList(), retainedCount = 3))
        assertEquals(1, profilePlaylistCount(serverCount = 0, playlists = retained, retainedCount = 0))
    }

    @Test
    fun profilePlaylistPagesAppendDistinctServerRows() {
        val first = listOf(Playlist(id = 1L), Playlist(id = 2L))
        val second = listOf(Playlist(id = 2L), Playlist(id = 3L))

        assertEquals(listOf(1L, 2L, 3L), appendProfilePlaylists(first, second).map { it.id })
    }

    @Test
    fun profileEventPaginationDeduplicatesRowsAndStopsWhenTheCursorRepeats() {
        val first = listOf(profileEvent(1L), profileEvent(2L))
        val second = listOf(profileEvent(2L), profileEvent(3L))

        assertEquals(listOf(1L, 2L, 3L), appendProfileEvents(first, second).map { it.id })
        assertTrue(canLoadMoreProfileEvents(more = true, currentTime = -1L, nextTime = 400L))
        assertFalse(canLoadMoreProfileEvents(more = true, currentTime = 400L, nextTime = 400L))
        assertFalse(canLoadMoreProfileEvents(more = true, currentTime = 400L, nextTime = 401L))
        assertFalse(canLoadMoreProfileEvents(more = true, currentTime = 400L, nextTime = 0L))
    }

    @Test
    fun repeatedProfileEventPagesPauseAutomaticLoadingButKeepManualContinuation() {
        val initial = UserProfileViewModel.UiState(
            events = listOf(profileEvent(1L)),
            eventTime = 100L,
            eventsMore = true,
        )
        val first = initial.appendProfileEventPage(UserEventPage(listOf(profileEvent(1L)), more = true, nextTime = 99L))
        val second = first.appendProfileEventPage(UserEventPage(listOf(profileEvent(1L)), more = true, nextTime = 98L))
        val paused = second.appendProfileEventPage(UserEventPage(listOf(profileEvent(1L)), more = true, nextTime = 97L))

        assertTrue(paused.eventsMore)
        assertTrue(paused.eventsAutoLoadBlocked)
        assertEquals(3, paused.eventConsecutiveEmptyPages)

        val resumed = paused.copy(eventsAutoLoadBlocked = false).appendProfileEventPage(
            UserEventPage(listOf(profileEvent(2L)), more = true, nextTime = 96L),
        )
        assertEquals(listOf(1L, 2L), resumed.events.map { it.id })
        assertFalse(resumed.eventsAutoLoadBlocked)
    }

    @Test
    fun profileEventDetailKeepsLoadedCommentsForTheSelectedEvent() {
        val page = NotesRepository.CommentPage(
            comments = listOf(Comment(commentId = 41L, content = "可见评论")),
            total = 4L,
            more = false,
            nextOffset = 0,
        )

        val state = profileCommentStateAfterPage(EventCommentsState(loading = true), page)

        assertFalse(state.loading)
        assertEquals(4L, state.total)
        assertEquals(listOf(41L), state.comments.map { it.commentId })
    }

    @Test
    fun profileEventActionsKeepTheDetailAndListPostInSync() {
        val event = profileEvent(2L).copy(likeCount = 7, commentCount = 4)

        val updated = event.afterLike(true).afterComment()

        assertTrue(updated.liked)
        assertEquals(8, updated.likeCount)
        assertEquals(5, updated.commentCount)
    }

    private fun profileEvent(id: Long) = EventPost(
        id = id,
        userId = 9L,
        nickname = "小云",
        avatarUrl = "",
        content = "动态$id",
        tags = emptyList(),
        imageUrls = emptyList(),
        createdAt = 0L,
        liked = false,
        likeCount = 0,
        commentCount = 0,
    )
}
