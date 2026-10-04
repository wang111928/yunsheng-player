package com.litemusic.app.data

import com.litemusic.app.BuildConfig
import com.litemusic.app.util.DbgLog
import com.litemusic.shared.api.EventMapper
import com.litemusic.shared.api.EventPost
import com.litemusic.shared.api.EventResponse
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.Comment
import com.litemusic.shared.model.CommentResponse
import com.litemusic.shared.model.CommentActionResponse
import com.litemusic.shared.util.AppResult
import com.litemusic.app.feature.notes.NotesTab

/**
 * The HTTP request itself can succeed while the NetEase business response
 * rejects it.  Keep that response a failure: an empty `total` from it must
 * never replace an already displayed comment count.
 */
internal fun eventPageFrom(response: EventResponse, requestedLastTime: Long = -1L): AppResult<NotesRepository.Page> =
    if (response.code != 200) {
        AppResult.Failure(response.code, "动态读取失败(${response.code})")
    } else {
        AppResult.Success(
            NotesRepository.Page(
                posts = response.allEvents.mapNotNull(EventMapper::map),
                lastTime = eventPageCursor(response, requestedLastTime),
                more = response.more,
            ),
        )
    }

/** The RN recommendation stream paginates with an opaque cursor, not `lasttime`. */
internal fun squarePageFrom(response: EventResponse, requestedCursor: String = ""): AppResult<NotesRepository.Page> =
    if (response.code != 200) {
        AppResult.Failure(response.code, "笔记推荐读取失败(${response.code})")
    } else {
        AppResult.Success(
            NotesRepository.Page(
                posts = response.allEvents.mapNotNull(EventMapper::map),
                lastTime = -1L,
                more = response.more && response.nextSquareCursor.isNotBlank() &&
                    response.nextSquareCursor != requestedCursor,
                squareCursor = response.nextSquareCursor,
            ),
        )
    }

/** Some event feeds omit or repeat `lasttime` while their rows still move backward. */
internal fun eventPageCursor(response: EventResponse, requestedLastTime: Long): Long {
    val server = response.nextLastTime
    val oldestRow = response.allEvents.map { it.eventTime }.filter { it > 0L }.minOrNull() ?: 0L
    return when {
        server > 0L && (requestedLastTime <= 0L || server < requestedLastTime) -> server
        oldestRow > 0L && (requestedLastTime <= 0L || oldestRow < requestedLastTime) -> oldestRow
        else -> server
    }
}

internal fun commentPageFrom(
    response: CommentResponse,
    requestedOffset: Int,
    expectedTotal: Long = 0L,
): AppResult<NotesRepository.CommentPage> =
    if (response.code != 200) {
        AppResult.Failure(response.code, "评论读取失败(${response.code})")
    } else {
        val visible = if (requestedOffset == 0) {
            (response.hotComments + response.comments).distinctBy { it.commentId }
        } else response.comments
        // A positive server total corrects a stale count from the feed. Only a missing
        // total needs the feed estimate so a hot-only first page remains retryable.
        val reportedTotal = if (response.total > 0L) response.total
            else if (requestedOffset == 0) expectedTotal else 0L
        val hotOnlyFirstPage = requestedOffset == 0 && visible.isNotEmpty() && response.comments.isEmpty()
        val more = if (hotOnlyFirstPage) {
            reportedTotal > visible.size || (reportedTotal <= 0L && response.hasMore)
        } else {
            response.comments.isNotEmpty() &&
                (response.hasMore || requestedOffset + response.comments.size < reportedTotal)
        }
        AppResult.Success(
            NotesRepository.CommentPage(
                comments = visible,
                total = maxOf(reportedTotal, visible.size.toLong()),
                serverTotalMissing = response.total <= 0L,
                more = more,
                // Only ordinary rows advance the server offset. Skipping by the requested
                // limit after a hot-only response could jump past every ordinary comment.
                nextOffset = requestedOffset + response.comments.size,
            ),
        )
    }

class NotesRepository(
    private val api: NMApi,
) {
    data class Page(
        val posts: List<EventPost>,
        val lastTime: Long,
        val more: Boolean,
        val squareCursor: String = "",
    )

    data class CommentPage(
        val comments: List<Comment>,
        val total: Long,
        /** Keep the feed count only while the comment endpoint omits its own total. */
        val serverTotalMissing: Boolean = false,
        val more: Boolean,
        /** Raw server offset advances by received rows, even if local dedupe drops a row. */
        val nextOffset: Int,
    )

    suspend fun load(tab: NotesTab, lastTime: Long = -1L, limit: Int = 20, squareCursor: String = ""): AppResult<Page> {
        pageLog("event request tab=$tab cursor=$lastTime limit=$limit")
        val result = if (tab == NotesTab.RECOMMENDED) api.getSquareEvents(squareCursor)
        else api.getEvents(recommended = false, lastTime = lastTime, limit = limit)
        return when (result) {
            is AppResult.Failure -> {
                pageLog("event failure tab=$tab cursor=$lastTime code=${result.code}")
                result
            }
            is AppResult.Success -> (if (tab == NotesTab.RECOMMENDED) squarePageFrom(result.data, squareCursor)
                else eventPageFrom(result.data, lastTime)).also { page ->
                when (page) {
                    is AppResult.Success -> pageLog(
                        "event response tab=$tab cursor=$lastTime nextCursor=${page.data.lastTime} " +
                            "more=${page.data.more} received=${result.data.allEvents.size} mapped=${page.data.posts.size} " +
                            "authors=${page.data.posts.map { it.userId }.distinct().size} " +
                            "followedRows=${page.data.posts.count { it.followed }}",
                    )
                    is AppResult.Failure -> pageLog(
                        "event business-failure tab=$tab cursor=$lastTime code=${page.code}",
                    )
                }
            }
        }
    }

    suspend fun loadComments(post: EventPost, offset: Int = 0, limit: Int = 20): AppResult<CommentPage> {
        if (post.commentThreadId.isBlank()) {
            pageLog("comment skipped cursor=$offset reason=missing-thread")
            return AppResult.Failure(code = 400, message = "这条动态没有可用的评论标识")
        }
        pageLog("comment request cursor=$offset limit=$limit")
        return when (val result = api.getEventComments(post.commentThreadId, offset = offset, limit = limit)) {
            is AppResult.Failure -> {
                pageLog("comment failure cursor=$offset code=${result.code}")
                result
            }
            is AppResult.Success -> commentPageFrom(
                result.data,
                offset,
                expectedTotal = if (offset == 0) post.commentCount.toLong() else 0L,
            ).let { page ->
                if (offset == 0 && post.commentCount > 0 && page is AppResult.Success &&
                    page.data.comments.isEmpty()) {
                    AppResult.Failure(-1, "动态显示 ${post.commentCount} 条评论，但接口没有返回评论，请重试")
                } else page
            }.also { page ->
                when (page) {
                    is AppResult.Success -> pageLog(
                        "comment response cursor=$offset nextCursor=${page.data.nextOffset} " +
                            "more=${page.data.more} received=${page.data.comments.size}",
                    )
                    is AppResult.Failure -> pageLog("comment business-failure cursor=$offset code=${page.code}")
                }
            }
        }
    }

    suspend fun likeEvent(post: EventPost, like: Boolean): AppResult<CommentActionResponse> {
        if (post.commentThreadId.isBlank()) {
            return AppResult.Failure(400, "这条动态没有可用的互动标识")
        }
        return eventActionResult(api.likeEvent(post.commentThreadId, like), "点赞操作失败")
    }

    suspend fun postEventComment(post: EventPost, content: String): AppResult<CommentActionResponse> {
        if (post.commentThreadId.isBlank()) {
            return AppResult.Failure(400, "这条动态没有可用的评论标识")
        }
        return eventActionResult(api.postEventComment(post.commentThreadId, content), "评论发布失败")
    }

    suspend fun replyEventComment(
        post: EventPost,
        commentId: Long,
        content: String,
    ): AppResult<CommentActionResponse> {
        if (post.commentThreadId.isBlank() || commentId <= 0L) {
            return AppResult.Failure(400, "这条评论暂时不能回复")
        }
        return eventActionResult(
            api.replyEventComment(post.commentThreadId, commentId, content),
            "回复发布失败",
        )
    }

    suspend fun likeEventComment(
        post: EventPost,
        commentId: Long,
        like: Boolean,
    ): AppResult<CommentActionResponse> {
        if (post.commentThreadId.isBlank() || commentId <= 0L) {
            return AppResult.Failure(400, "这条评论暂时不能点赞")
        }
        return eventActionResult(
            api.likeEventComment(post.commentThreadId, commentId, like),
            "评论点赞操作失败",
        )
    }

    /** Pagination evidence is written only by debug builds and never includes event/comment content. */
    private fun pageLog(message: String) {
        if (BuildConfig.DEBUG) DbgLog.w("NotesPage", message)
    }
}

/** Mutation endpoints return HTTP success even when the NetEase business action is rejected. */
internal fun eventActionResult(
    result: AppResult<CommentActionResponse>,
    fallbackMessage: String,
): AppResult<CommentActionResponse> = when (result) {
    is AppResult.Failure -> result
    is AppResult.Success -> if (result.data.code == 200) result
    else AppResult.Failure(result.data.code, fallbackMessage)
}
