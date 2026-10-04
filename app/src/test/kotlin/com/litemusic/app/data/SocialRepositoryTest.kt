package com.litemusic.app.data

import com.litemusic.shared.api.FollowListResponse
import com.litemusic.shared.api.EventItem
import com.litemusic.shared.api.EventResponse
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Profile
import com.litemusic.shared.model.UserPlaylistResponse
import com.litemusic.shared.util.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialRepositoryTest {
    @Test
    fun followsBusinessFailureIsExposedInsteadOfBeingRenderedAsAnEmptyPage() {
        val result = followsPageForUi(
            AppResult.Success(FollowListResponse(code = 401, follow = listOf(Profile(userId = 88L)))),
            offset = 0,
        )

        val failure = result as AppResult.Failure
        assertEquals(401, failure.code)
        assertEquals("关注列表读取失败(401)", failure.message)
    }

    @Test
    fun followersBusinessFailureIsExposedInsteadOfBeingRenderedAsAnEmptyPage() {
        val result = followedsPageForUi(
            AppResult.Success(FollowListResponse(code = 500, followeds = listOf(Profile(userId = 88L)))),
            offset = 30,
        )

        val failure = result as AppResult.Failure
        assertEquals(500, failure.code)
        assertEquals("粉丝列表读取失败(500)", failure.message)
    }

    @Test
    fun profilePlaylistBusinessFailureIsNotSilentlyChangedToAnEmptyList() {
        val result = userPlaylistsForUi(
            AppResult.Success(UserPlaylistResponse(code = 301, playlist = listOf(Playlist(id = 7L)))),
        )

        val failure = result as AppResult.Failure
        assertEquals(301, failure.code)
        assertEquals("用户歌单读取失败(301)", failure.message)
    }

    @Test
    fun successfulFollowPageRetainsItsUsersAndAdvancesFromTheActualPageSize() {
        val result = followsPageForUi(
            AppResult.Success(
                FollowListResponse(
                    code = 200,
                    follow = listOf(Profile(userId = 10L), Profile(userId = 11L)),
                    more = true,
                ),
            ),
            offset = 30,
        )

        val page = (result as AppResult.Success<SocialPage>).data
        assertEquals(listOf(10L, 11L), page.users.map { it.userId })
        assertEquals(32, page.nextOffset)
        assertTrue(page.more)
    }

    @Test
    fun userEventPageMapsThePublicEventsAndKeepsTheServerCursor() {
        val result = userEventsForUi(
            AppResult.Success(
                EventResponse(
                    code = 200,
                    alternateEvents = listOf(
                        EventItem(
                            id = 7L,
                            user = Profile(userId = 8L, nickname = "小云"),
                            json = """{"msg":"晚上好"}""",
                        ),
                    ),
                    alternateLastTime = 456L,
                    more = true,
                ),
            ),
        )

        val page = (result as AppResult.Success<UserEventPage>).data
        assertEquals(listOf(7L), page.posts.map { it.id })
        assertEquals(456L, page.nextTime)
        assertTrue(page.more)
    }

    @Test
    fun userEventBusinessFailureIsNotRenderedAsAnEmptyTimeline() {
        val result = userEventsForUi(AppResult.Success(EventResponse(code = 405)))

        val failure = result as AppResult.Failure
        assertEquals(405, failure.code)
        assertEquals("用户动态读取失败(405)", failure.message)
    }

    @Test
    fun userEventPageFallsBackToOldestRowWhenServerRepeatsCursor() {
        val result = userEventsForUi(
            AppResult.Success(
                EventResponse(
                    code = 200,
                    lasttime = 1000L,
                    events = listOf(EventItem(id = 1L, eventTime = 900L)),
                    more = true,
                ),
            ),
            requestedTime = 1000L,
        )
        assertEquals(900L, (result as AppResult.Success<UserEventPage>).data.nextTime)
    }
}
