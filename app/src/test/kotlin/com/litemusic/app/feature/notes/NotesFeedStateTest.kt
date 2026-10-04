package com.litemusic.app.feature.notes

import com.litemusic.app.data.NotesRepository
import com.litemusic.app.data.eventActionResult
import com.litemusic.app.data.commentPageFrom
import com.litemusic.app.data.eventPageFrom
import com.litemusic.app.data.eventPageCursor
import com.litemusic.shared.api.EventItem
import com.litemusic.shared.api.EventResponse
import com.litemusic.shared.api.EventPost
import com.litemusic.shared.model.CommentResponse
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.model.CommentActionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesFeedStateTest {
    @Test
    fun loadMoreGateAllowsOnlyOneCursorOwnerUntilReleased() {
        val gate = NotesLoadMoreGate()

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun businessErrorResponses_areNotConvertedIntoEmptyFeedOrCommentPages() {
        val events = eventPageFrom(EventResponse(code = 301))
        val comments = commentPageFrom(CommentResponse(code = 403, total = 0), requestedOffset = 20)

        assertTrue(events is AppResult.Failure)
        assertTrue(comments is AppResult.Failure)
    }

    @Test
    fun repeatedServerCursorFallsBackToOlderEventTime() {
        val response = EventResponse(
            code = 200,
            lasttime = 1000L,
            events = listOf(EventItem(id = 1L, eventTime = 900L)),
            more = true,
        )

        assertEquals(900L, eventPageCursor(response, requestedLastTime = 1000L))
    }

    @Test
    fun followingAndRecommendedUseDifferentLayouts() {
        assertEquals(NotesLayout.SINGLE_COLUMN, notesLayoutFor(NotesTab.FOLLOWING))
        assertEquals(NotesLayout.TWO_COLUMN, notesLayoutFor(NotesTab.RECOMMENDED))
    }

    @Test
    fun mergeEventPosts_keepsUniqueIdsAndLatestVisibleOptimisticState() {
        val optimistic = post("optimistic", id = 1L).copy(
            followed = false,
            liked = true,
            likeCount = 8,
        )
        val staleServer = post("server copy", id = 1L).copy(
            followed = true,
            liked = false,
            likeCount = 7,
        )
        val newPost = post("new", id = 2L)

        val merged = mergeEventPosts(listOf(optimistic), listOf(staleServer, newPost))

        assertEquals(listOf(1L, 2L), merged.map { it.id })
        assertEquals("server copy", merged.first().content)
        assertEquals(false, merged.first().followed)
        assertTrue(merged.first().liked)
        assertEquals(8, merged.first().likeCount)
    }

    @Test
    fun selectingRecommendedFeed_replacesFollowingPosts() {
        val state = NotesFeedState(
            tab = NotesTab.FOLLOWING,
            posts = listOf(post("关注动态")),
        )

        val next = state.replaceFeed(NotesTab.RECOMMENDED, listOf(post("推荐帖子")), lastTime = 9L, more = true)

        assertEquals(NotesTab.RECOMMENDED, next.tab)
        assertEquals(listOf("推荐帖子"), next.posts.map { it.content })
        assertEquals(9L, next.lastTime)
        assertEquals(true, next.more)
    }

    @Test
    fun replacingFeed_keepsAuthorFollowRelationsForTheVisiblePosts() {
        val next = NotesFeedState().replaceFeed(
            tab = NotesTab.RECOMMENDED,
            posts = listOf(post("recommended").copy(followed = true)),
            lastTime = 4L,
            more = false,
        )

        assertEquals(true, next.followedAuthors[1L])
    }

    @Test
    fun refreshingWithoutALocalFollowChoiceUsesTheLatestServerRelation() {
        val current = NotesFeedState().replaceFeed(
            tab = NotesTab.RECOMMENDED,
            posts = listOf(post("previous").copy(followed = true)),
            lastTime = 3L,
            more = true,
        )

        val refreshed = current.replaceFeed(
            tab = NotesTab.RECOMMENDED,
            posts = listOf(post("server copy").copy(followed = false)),
            lastTime = 4L,
            more = false,
        )

        assertEquals(false, refreshed.followedAuthors[1L])
    }

    @Test
    fun switchingFromFollowingDoesNotCarryItsRelationIntoRecommended() {
        val following = NotesFeedState().replaceFeed(
            tab = NotesTab.FOLLOWING,
            posts = listOf(post("following post").copy(followed = true)),
            lastTime = 3L,
            more = true,
        )

        val recommended = following.replaceFeed(
            tab = NotesTab.RECOMMENDED,
            posts = listOf(post("recommended post").copy(followed = false)),
            lastTime = 4L,
            more = false,
        )

        assertEquals(false, recommended.followedAuthors[1L])
    }

    @Test
    fun refreshingPreservesAnExplicitLocalFollowChoiceAgainstStaleServerData() {
        val current = NotesFeedState(followOverrides = mapOf(1L to false))

        val refreshed = current.replaceFeed(
            tab = NotesTab.RECOMMENDED,
            posts = listOf(post("server copy").copy(followed = true)),
            lastTime = 4L,
            more = false,
        )

        assertEquals(false, refreshed.followedAuthors[1L])
    }

    @Test
    fun commentThreadWithoutAnIdentifier_hasAReadableErrorInsteadOfLoading() {
        val state = commentStateForThread("")

        assertEquals(false, state.loading)
        assertEquals("这条动态暂时没有可用评论", state.error)
    }

    @Test
    fun loadedCommentTotal_replacesTheFeedEstimate() {
        val state = EventCommentsState(
            comments = emptyList(),
            total = 3L,
            more = false,
        )

        assertEquals(3L, displayedCommentTotal(post("动态").copy(commentCount = 88), state))
    }

    @Test
    fun failedCommentLoad_keepsTheFeedCount() {
        val post = post("动态").copy(commentCount = 4)
        val failed = EventCommentsState(error = "评论读取失败")

        assertEquals(4L, displayedCommentTotal(post, failed))
    }

    @Test
    fun appendedCommentPage_keepsUniqueRowsAndServerTotal() {
        val first = EventCommentsState(
            comments = listOf(comment(1L)),
            total = 5L,
            more = true,
        )

        val next = first.appendCommentPage(
            NotesRepository.CommentPage(
                comments = listOf(comment(1L), comment(2L)),
                total = 5L,
                more = false,
                nextOffset = 3,
            ),
        )

        assertEquals(listOf(1L, 2L), next.comments.map { it.commentId })
        assertEquals(5L, next.total)
        assertEquals(false, next.more)
        assertEquals(3, next.nextOffset)
    }

    @Test
    fun fortyFourCommentThread_advancesToTheSecondCommentPage() {
        val result = commentPageFrom(
            CommentResponse(
                code = 200,
                comments = (0 until 20).map { comment(it.toLong() + 1L) },
                total = 44L,
                hasMore = true,
            ),
            requestedOffset = 20,
        )

        val page = (result as AppResult.Success).data
        assertEquals(44L, page.total)
        assertEquals(true, page.more)
        assertEquals(40, page.nextOffset)
    }

    @Test
    fun firstCommentPageIncludesHotCommentsWithoutAdvancingPastRegularRows() {
        val page = commentPageFrom(
            CommentResponse(
                code = 200,
                hotComments = listOf(comment(1L)),
                comments = listOf(comment(1L), comment(2L)),
                total = 9L,
            ),
            requestedOffset = 0,
        ) as AppResult.Success

        assertEquals(listOf(1L, 2L), page.data.comments.map { it.commentId })
        assertEquals(2, page.data.nextOffset)
        assertTrue(page.data.more)
    }

    @Test
    fun firstCommentPageUsesServerTotalWhenFeedEstimateIsStale() {
        val page = commentPageFrom(
            CommentResponse(code = 200, comments = listOf(comment(1L)), total = 5L),
            requestedOffset = 0,
            expectedTotal = 9L,
        ) as AppResult.Success

        assertEquals(5L, page.data.total)
        assertEquals(false, page.data.serverTotalMissing)
    }

    @Test
    fun hotOnlyFirstCommentPageKeepsSafeCursorAndCanRecoverOnRetry() {
        val first = commentPageFrom(
            CommentResponse(
                code = 200,
                hotComments = listOf(comment(1L)),
                comments = emptyList(),
                total = 9L,
                hasMore = true,
            ),
            requestedOffset = 0,
        ) as AppResult.Success

        assertEquals(listOf(1L), first.data.comments.map { it.commentId })
        assertTrue(first.data.more)
        assertEquals(0, first.data.nextOffset)
        val stalled = EventCommentsState().appendCommentPage(first.data)
        assertEquals("普通评论尚未返回，点击重试", stalled.error)
        assertTrue(stalled.autoLoadBlocked)

        val second = commentPageFrom(
            CommentResponse(code = 200, comments = listOf(comment(2L)), total = 0L),
            requestedOffset = first.data.nextOffset,
        ) as AppResult.Success
        val state = stalled.appendCommentPage(second.data)
        assertEquals(listOf(1L, 2L), state.comments.map { it.commentId })
        assertEquals(1, state.nextOffset)
        assertEquals(null, state.error)
        assertTrue(state.more)
        assertFalse(state.autoLoadBlocked)
    }

    @Test
    fun hotOnlyPageKeepsFeedCommentCountWhenResponseOmitsTotalAndMore() {
        val first = commentPageFrom(
            CommentResponse(code = 200, hotComments = listOf(comment(1L))),
            requestedOffset = 0,
            expectedTotal = 9L,
        ) as AppResult.Success

        assertEquals(9L, first.data.total)
        assertEquals(0, first.data.nextOffset)
        assertTrue(first.data.more)
        val stalled = EventCommentsState().appendCommentPage(first.data)
        assertEquals("普通评论尚未返回，点击重试", stalled.error)
        assertTrue(stalled.autoLoadBlocked)
    }

    @Test
    fun commentTotalKeepsPagingWhenTheServerOmitsMoreFlag() {
        val first = commentPageFrom(
            CommentResponse(
                code = 200,
                comments = (0 until 20).map { comment(it.toLong() + 1L) },
                total = 44L,
            ),
            requestedOffset = 0,
        ) as AppResult.Success
        assertEquals(true, first.data.more)
        assertEquals(20, first.data.nextOffset)

        val final = commentPageFrom(
            CommentResponse(code = 200, comments = (0 until 4).map { comment(it.toLong() + 41L) }, total = 44L),
            requestedOffset = 40,
        ) as AppResult.Success
        assertEquals(false, final.data.more)
    }

    @Test
    fun repeatedEventPageWithAnAdvancedCursorKeepsPaging() {
        val current = NotesFeedState(
            posts = listOf(post("already shown", id = 1L)),
            lastTime = 100L,
            more = true,
            loadingMore = true,
        )

        val next = current.appendPage(
            posts = listOf(post("already shown again", id = 1L)),
            lastTime = 99L,
            more = true,
        )

        assertEquals(false, next.loadingMore)
        assertEquals(true, next.more)
        assertEquals(1, next.consecutiveEmptyPages)
        assertTrue(shouldContinuePastDuplicatePage(current, next))
    }

    @Test
    fun freshRecommendedPageAtTheEnd_appendsOnlyUnseenPostsAndDoesNotResetTheFeed() {
        val current = NotesFeedState().replaceFeed(
            tab = NotesTab.RECOMMENDED,
            posts = listOf(post("already visible", id = 1L).copy(liked = true, likeCount = 9)),
            lastTime = 100L,
            more = false,
        )

        val next = current.appendFreshRecommendedPage(
            posts = listOf(
                post("stale server copy", id = 1L),
                post("new recommendation", id = 2L),
            ),
            lastTime = 90L,
            more = false,
        )

        assertEquals(listOf(1L, 2L), next.posts.map { it.id })
        assertTrue(next.posts.first().liked)
        assertEquals(9, next.posts.first().likeCount)
        assertEquals("已追加 1 条新推荐", next.toast)
        assertFalse(next.more)
    }

    @Test
    fun repeatedFreshRecommendedPage_keepsTheVisibleFeedAndExplainsThatThereIsNothingNew() {
        val current = NotesFeedState().replaceFeed(
            tab = NotesTab.RECOMMENDED,
            posts = listOf(post("already visible", id = 1L)),
            lastTime = 100L,
            more = false,
        )

        val next = current.appendFreshRecommendedPage(
            posts = listOf(post("same page", id = 1L)),
            lastTime = 100L,
            more = false,
        )

        assertEquals(listOf(1L), next.posts.map { it.id })
        assertEquals("暂未获取到新的推荐，已保留当前列表", next.toast)
        assertFalse(next.more)
    }

    @Test
    fun repeatedEventPagesKeepLoadingWhileTheCursorKeepsAdvancing() {
        val first = NotesFeedState(
            posts = listOf(post("already shown", id = 1L)),
            lastTime = 100L,
            more = true,
        ).appendPage(listOf(post("duplicate", id = 1L)), lastTime = 99L, more = true)
        val second = first.appendPage(listOf(post("duplicate", id = 1L)), lastTime = 98L, more = true)
        val third = second.appendPage(listOf(post("duplicate", id = 1L)), lastTime = 97L, more = true)

        assertEquals(true, third.more)
        assertEquals(3, third.consecutiveEmptyPages)
        assertFalse(third.autoLoadMoreBlocked)
        assertTrue(shouldContinuePastDuplicatePage(second, third))
    }

    @Test
    fun manualContinuationCanResumeAfterDuplicatePagesWhenCursorKeepsMoving() {
        val paused = NotesFeedState(
            posts = listOf(post("already shown", id = 1L)),
            lastTime = 97L,
            more = true,
            consecutiveEmptyPages = 3,
            autoLoadMoreBlocked = true,
        )

        val resumed = paused.copy(autoLoadMoreBlocked = false).appendPage(
            posts = listOf(post("new row", id = 2L)),
            lastTime = 96L,
            more = true,
        )

        assertEquals(listOf(1L, 2L), resumed.posts.map { it.id })
        assertFalse(resumed.autoLoadMoreBlocked)
        assertTrue(resumed.more)
    }

    @Test
    fun eventPageWithUnchangedCursor_stopsFurtherPaginationAndExplainsWhy() {
        val current = NotesFeedState(
            posts = listOf(post("already shown", id = 1L)),
            lastTime = 100L,
            more = true,
            loadingMore = true,
        )

        val next = current.appendPage(
            posts = listOf(post("new row", id = 2L)),
            lastTime = 100L,
            more = true,
        )

        assertEquals(false, next.loadingMore)
        assertEquals(false, next.more)
        assertTrue(next.toast?.contains("游标") == true)
    }

    @Test
    fun eventPageWithNewerCursorDoesNotLoopBackwardIntoTheSameRows() {
        val current = NotesFeedState(
            posts = listOf(post("already shown", id = 1L)),
            lastTime = 100L,
            more = true,
        )

        val next = current.appendPage(
            posts = listOf(post("duplicate", id = 1L)),
            lastTime = 101L,
            more = true,
        )

        assertEquals(false, next.more)
    }

    @Test
    fun failedCommentAppendBlocksAutomaticRetryButKeepsManualRetryAvailable() {
        val failed = commentPageFailure(
            EventCommentsState(comments = listOf(comment(1L)), more = true, nextOffset = 20),
            "下一页失败",
        )

        assertFalse(canAutoLoadMoreComments(failed))
        assertEquals(true, failed.more)
        assertEquals(listOf(1L), failed.comments.map { it.commentId })
        assertEquals("下一页失败", failed.error)
    }

    @Test
    fun newRefreshStopsThePreviousPageRequestAndKeepsVisiblePosts() {
        val state = NotesFeedState(
            posts = listOf(post("已显示的动态")),
            loadingMore = true,
            loadMoreError = "上一页失败",
        )

        val refreshing = state.beginRefresh()

        assertEquals(false, refreshing.loadingMore)
        assertEquals(null, refreshing.loadMoreError)
        assertEquals(listOf("已显示的动态"), refreshing.posts.map { it.content })
        assertEquals(true, refreshing.refreshing)
    }

    @Test
    fun emptyFeedRefreshStillReportsInFlightStateToThePullIndicator() {
        val refreshing = NotesFeedState().beginRefresh()

        assertTrue(refreshing.loading)
        assertTrue(refreshing.refreshing)
    }

    @Test
    fun refreshClearsTheAutomaticPagingPauseBeforeStartingANewRequest() {
        val refreshing = NotesFeedState(autoLoadMoreBlocked = true).beginRefresh()

        assertFalse(refreshing.autoLoadMoreBlocked)
    }

    @Test
    fun stalePageResponseDoesNotChangeTheNewRequestLoadingState() {
        val stateForNewRequest = NotesFeedState(loadingMore = true)

        val afterStaleResponse = stateForNewRequest.applyPageResult(
            responseVersion = 4L,
            activeVersion = 5L,
            result = AppResult.Failure(code = 500, message = "旧请求失败"),
        )

        assertEquals(true, afterStaleResponse.loadingMore)
    }

    @Test
    fun currentPageResponseClearsOnlyItsOwnLoadingState() {
        val currentRequest = NotesFeedState(loadingMore = true)

        val afterCurrentResponse = currentRequest.applyPageResult(
            responseVersion = 5L,
            activeVersion = 5L,
            result = AppResult.Success(NotesRepository.Page(emptyList(), lastTime = 6L, more = false)),
        )

        assertEquals(false, afterCurrentResponse.loadingMore)
        assertEquals(6L, afterCurrentResponse.lastTime)
    }

    @Test
    fun appendingPageWithoutALocalFollowChoiceUsesTheLatestServerRelation() {
        val current = NotesFeedState().replaceFeed(
            tab = NotesTab.RECOMMENDED,
            posts = listOf(post("current", id = 1L).copy(followed = true)),
            lastTime = 3L,
            more = true,
        )
        val latestServer = post("newer server relation", id = 2L).copy(userId = 1L, followed = false)

        val next = current.appendPage(listOf(latestServer), lastTime = 4L, more = false)

        assertEquals(false, next.followedAuthors[1L])
        assertEquals(listOf(1L, 2L), next.posts.map { it.id })
    }

    @Test
    fun appendingPagePreservesAnExplicitLocalFollowChoiceAgainstStaleServerData() {
        val current = NotesFeedState(followOverrides = mapOf(1L to false)).replaceFeed(
            tab = NotesTab.RECOMMENDED,
            posts = listOf(post("current", id = 2L).copy(userId = 2L)),
            lastTime = 3L,
            more = true,
        )

        val next = current.appendPage(
            posts = listOf(post("server copy", id = 1L).copy(userId = 1L, followed = true)),
            lastTime = 4L,
            more = false,
        )

        assertEquals(false, next.followedAuthors[1L])
    }

    @Test
    fun shareTextIncludesTheOfficialWebEventRoute() {
        val text = eventShareText(post("今晚听歌", id = 7L).copy(nickname = "小云"))

        assertEquals("小云分享的云村动态：今晚听歌\nhttps://music.163.com/event?id=7&uid=1", text)
    }

    @Test
    fun repeatedCommentPageBlocksAutomaticLoadingButKeepsManualRetry() {
        val initial = EventCommentsState(
            comments = listOf(comment(1L)),
            nextOffset = 20,
            more = true,
            loadingMore = true,
        )
        val next = initial.appendCommentPage(
            NotesRepository.CommentPage(listOf(comment(1L)), total = 44, more = true, nextOffset = 21),
        )

        assertFalse(canAutoLoadMoreComments(next))
        assertEquals(true, next.autoLoadBlocked)
        assertEquals(21, next.nextOffset)
        assertEquals(listOf(1L), next.comments.map { it.commentId })
    }

    @Test
    fun likingAnEventUpdatesTheVisibleStateAndNeverShowsANegativeCount() {
        val unliked = post("动态", id = 9L).copy(likeCount = 0)

        val liked = unliked.afterLike(true)
        val removed = liked.afterLike(false)

        assertTrue(liked.liked)
        assertEquals(1, liked.likeCount)
        assertFalse(removed.liked)
        assertEquals(0, removed.likeCount)
    }

    @Test
    fun likingACommentUpdatesOnlyThatCommentAndNeverShowsANegativeCount() {
        val original = comment(3L).copy(likedCount = 0, liked = false)

        val liked = original.afterLike(true)
        val removed = liked.afterLike(false)

        assertTrue(liked.liked)
        assertEquals(1, liked.likedCount)
        assertFalse(removed.liked)
        assertEquals(0, removed.likedCount)
    }

    @Test
    fun eventAuthorFollowCannotStartWhileThatAuthorAlreadyHasARequest() {
        val state = NotesFeedState(followRequestTokens = mapOf(7L to 1L))

        assertFalse(canStartEventFollow(state, 7L))
        assertTrue(canStartEventFollow(state, 8L))
        assertFalse(canStartEventFollow(state, 0L))
    }

    @Test
    fun successfulEventCommentIncrementsTheEventCount() {
        val event = post("动态", id = 9L).copy(commentCount = 3)

        assertEquals(4, event.afterComment().commentCount)
    }

    @Test
    fun replyRefreshUsesTheAuthoritativeThreadInsteadOfPrependingMutationComment() {
        val parent = comment(1L)
        val reply = comment(2L).copy(
            beReplied = listOf(com.litemusic.shared.model.BeReplied(content = "原评论")),
        )
        val refreshed = refreshedEventCommentThread(
            NotesRepository.CommentPage(
                comments = listOf(parent, reply),
                total = 2L,
                more = false,
                nextOffset = 2,
            ),
        )

        assertEquals(listOf(1L, 2L), refreshed.comments.map { it.commentId })
        assertEquals(2L, refreshed.total)
        assertFalse(refreshed.more)
    }

    @Test
    fun replyRefreshFailureKeepsCommentsAndMakesTheThreadRetryable() {
        val original = EventCommentsState(comments = listOf(comment(1L)), total = 1L)
        val stale = original.markRefreshNeeded("回复已发送，评论列表刷新失败")

        assertEquals(listOf(1L), stale.comments.map { it.commentId })
        assertEquals(1L, stale.total)
        assertTrue(stale.needsRefresh)
        assertEquals("回复已发送，评论列表刷新失败", stale.error)
    }

    @Test
    fun pendingReplyWaitsForObservedMutationBeforeFailureCanReleaseIt() {
        assertFalse(shouldReleasePendingReply(hasObservedInFlight = false, replyMutationInFlight = false))
        assertFalse(shouldReleasePendingReply(hasObservedInFlight = true, replyMutationInFlight = true))
        assertTrue(shouldReleasePendingReply(hasObservedInFlight = true, replyMutationInFlight = false))
    }

    @Test
    fun rejectedEventMutationBusinessResponseIsExposedAsFailure() {
        val result = eventActionResult(
            AppResult.Success(CommentActionResponse(code = 301)),
            "点赞操作失败",
        )

        assertTrue(result is AppResult.Failure)
        assertEquals("点赞操作失败", (result as AppResult.Failure).message)
    }

    private fun post(content: String, id: Long = content.hashCode().toLong()) = EventPost(
        id = id,
        userId = 1L,
        nickname = "云村用户",
        avatarUrl = "",
        content = content,
        tags = emptyList(),
        imageUrls = emptyList(),
        createdAt = 0L,
        liked = false,
        likeCount = 0,
        commentCount = 0,
    )

    private fun comment(id: Long) = com.litemusic.shared.model.Comment(commentId = id, content = "评论$id")
}
