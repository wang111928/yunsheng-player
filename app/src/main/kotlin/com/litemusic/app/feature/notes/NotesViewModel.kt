package com.litemusic.app.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.BuildConfig
import com.litemusic.app.data.NotesRepository
import com.litemusic.app.data.SocialRepository
import com.litemusic.app.util.DbgLog
import com.litemusic.shared.api.EventPost
import com.litemusic.shared.model.Comment
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class NotesTab { FOLLOWING, RECOMMENDED }

/** A process-local lease that keeps automatic and manual pagination from sharing one cursor. */
internal class NotesLoadMoreGate {
    private val active = AtomicBoolean(false)

    fun tryAcquire(): Boolean = active.compareAndSet(false, true)
    fun release() {
        active.set(false)
    }
}

data class EventCommentsState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val comments: List<Comment> = emptyList(),
    val total: Long = 0L,
    val more: Boolean = false,
    val nextOffset: Int = 0,
    val error: String? = null,
    val autoLoadBlocked: Boolean = false,
    /** A successful write needs a first-page reload before this cache is authoritative again. */
    val needsRefresh: Boolean = false,
)

internal fun canAutoLoadMoreComments(state: EventCommentsState?): Boolean =
    state != null && state.more && !state.loading && !state.loadingMore && !state.autoLoadBlocked

internal fun commentPageFailure(current: EventCommentsState, message: String): EventCommentsState =
    current.copy(
        loading = false,
        loadingMore = false,
        error = message,
        autoLoadBlocked = true,
    )

internal fun EventCommentsState.markRefreshNeeded(message: String): EventCommentsState = copy(
    loading = false,
    loadingMore = false,
    error = message,
    needsRefresh = true,
)

/** The comment endpoint is authoritative once it has completed; the feed number is only an estimate. */
internal fun displayedCommentTotal(post: EventPost, state: EventCommentsState?): Long =
    if (state?.error != null && state.total == 0L && state.comments.isEmpty()) {
        post.commentCount.toLong()
    } else {
        state?.total ?: post.commentCount.toLong()
    }

internal fun EventCommentsState.appendCommentPage(page: NotesRepository.CommentPage): EventCommentsState {
    val seen = mutableSetOf<Long>()
    val merged = (comments + page.comments).filter { comment ->
        comment.commentId <= 0L || seen.add(comment.commentId)
    }
    val incomplete = merged.size < maxOf(total, page.total)
    val countSuggestsMore = page.serverTotalMissing && incomplete
    val missingRemainder = !page.more && page.comments.isEmpty() && incomplete
    val noProgress = (page.more || countSuggestsMore || missingRemainder) &&
        (page.nextOffset <= nextOffset || merged.size == comments.size)
    val hotOnlyWithoutCursor = noProgress && comments.isEmpty() && merged.isNotEmpty()
    return copy(
        loading = false,
        loadingMore = false,
        comments = merged,
        total = maxOf(total, page.total, merged.size.toLong()),
        more = page.more || countSuggestsMore || missingRemainder,
        nextOffset = maxOf(nextOffset, page.nextOffset),
        error = when {
            hotOnlyWithoutCursor -> "普通评论尚未返回，点击重试"
            missingRemainder ->
                "接口未返回其余评论，请稍后重试"
            noProgress -> "评论页没有新内容，已暂停自动加载"
            else -> null
        },
        autoLoadBlocked = noProgress || missingRemainder,
        needsRefresh = false,
    )
}

/**
 * A reply is returned by the mutation endpoint as a standalone comment, while
 * its parent relationship is only present in the comment-thread response.
 * Replacing the first page from that authoritative response keeps replies
 * attached to their parent instead of rendering a false top-level row.
 */
internal fun refreshedEventCommentThread(page: NotesRepository.CommentPage): EventCommentsState =
    EventCommentsState().appendCommentPage(page)

data class NotesFeedState(
    val tab: NotesTab = NotesTab.FOLLOWING,
    val posts: List<EventPost> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val lastTime: Long = -1L,
    val squareCursor: String = "",
    val more: Boolean = false,
    val followedAuthors: Map<Long, Boolean> = emptyMap(),
    val serverFollowedAuthors: Map<Long, Boolean> = emptyMap(),
    val followOverrides: Map<Long, Boolean> = emptyMap(),
    val followRequestTokens: Map<Long, Long> = emptyMap(),
    val commentsByPost: Map<Long, EventCommentsState> = emptyMap(),
    val likingPostIds: Set<Long> = emptySet(),
    val postingCommentIds: Set<Long> = emptySet(),
    /** Comment ids are globally unique in NetEase comment resources. */
    val mutatingCommentIds: Set<Long> = emptySet(),
    /** Incremented only after a successful post so the detail composer can safely clear its draft. */
    val commentSubmissionVersions: Map<Long, Long> = emptyMap(),
    val error: String? = null,
    val loadMoreError: String? = null,
    /** Duplicate-only pages pause scroll-triggered paging; the user may explicitly continue. */
    val autoLoadMoreBlocked: Boolean = false,
    val toast: String? = null,
    val consecutiveEmptyPages: Int = 0,
) {
    fun replaceFeed(tab: NotesTab, posts: List<EventPost>, lastTime: Long, more: Boolean, squareCursor: String = ""): NotesFeedState {
        val serverRelations = serverFollowRelations(posts)
        val activeOverrides = activeFollowOverrides(serverRelations, followOverrides)
        return copy(
            tab = tab,
            posts = posts,
            lastTime = lastTime,
            squareCursor = squareCursor,
            more = more,
            followedAuthors = effectiveFollowRelations(serverRelations, activeOverrides),
            serverFollowedAuthors = serverRelations,
            followOverrides = activeOverrides,
            loading = false,
            refreshing = false,
            loadingMore = false,
            error = null,
            loadMoreError = null,
            autoLoadMoreBlocked = false,
            consecutiveEmptyPages = 0,
        )
    }
}

internal fun EventPost.afterLike(like: Boolean): EventPost = copy(
    liked = like,
    likeCount = (likeCount + if (like) 1 else -1).coerceAtLeast(0),
)

internal fun EventPost.afterComment(): EventPost = copy(
    commentCount = (commentCount + 1).coerceAtLeast(0),
)

internal fun Comment.afterLike(like: Boolean): Comment = copy(
    liked = like,
    likedCount = (likedCount + if (like) 1 else -1).coerceAtLeast(0),
)

private fun serverFollowRelations(posts: List<EventPost>): Map<Long, Boolean> =
    posts.associate { it.userId to it.followed }

/** Keep a local choice only until a fresh server response confirms it. */
private fun activeFollowOverrides(
    serverRelations: Map<Long, Boolean>,
    followOverrides: Map<Long, Boolean>,
): Map<Long, Boolean> = followOverrides.filter { (userId, followed) ->
    serverRelations[userId] != followed
}

private fun effectiveFollowRelations(
    serverRelations: Map<Long, Boolean>,
    followOverrides: Map<Long, Boolean>,
): Map<Long, Boolean> = serverRelations + followOverrides.filterKeys { it in serverRelations }

internal fun commentStateForThread(commentThreadId: String): EventCommentsState =
    if (commentThreadId.isBlank()) EventCommentsState(error = "这条动态暂时没有可用评论")
    else EventCommentsState(loading = true)

internal fun NotesFeedState.beginRefresh(): NotesFeedState = copy(
    loading = posts.isEmpty(),
    // PullToRefreshBox needs an explicit in-flight signal even for an empty/error feed.  Without
    // it, a retry after an empty first page looks like a tap that did nothing.
    refreshing = true,
    loadingMore = false,
    error = null,
    loadMoreError = null,
    autoLoadMoreBlocked = false,
)

internal fun NotesFeedState.appendPage(
    posts: List<EventPost>,
    lastTime: Long,
    more: Boolean,
    squareCursor: String = "",
): NotesFeedState {
    val mergedPosts = mergeEventPosts(this.posts, posts)
    val noNewPosts = mergedPosts.size == this.posts.size
    val cursorDidNotAdvance = if (tab == NotesTab.RECOMMENDED) {
        squareCursor.isBlank() || squareCursor == this.squareCursor
    } else lastTime <= 0L || (this.lastTime > 0L && lastTime >= this.lastTime)
    // The server can return many overlapping pages while its cursor still moves.  A
    // moving cursor is safe progress, so keep walking it until the server says there
    // is no more data; only a stuck/non-monotonic cursor is terminal.
    val emptyPageCount = if (noNewPosts) consecutiveEmptyPages + 1 else 0
    val terminalCursor = more && cursorDidNotAdvance
    val serverRelations = serverFollowedAuthors + serverFollowRelations(posts)
    val activeOverrides = activeFollowOverrides(serverRelations, followOverrides)
    return copy(
        posts = mergedPosts,
        lastTime = lastTime,
        squareCursor = squareCursor,
        more = if (terminalCursor) false else more,
        autoLoadMoreBlocked = false,
        followedAuthors = effectiveFollowRelations(serverRelations, activeOverrides),
        serverFollowedAuthors = serverRelations,
        followOverrides = activeOverrides,
        loadingMore = false,
        loadMoreError = null,
        consecutiveEmptyPages = emptyPageCount,
        toast = if (terminalCursor) "动态分页游标未推进，已停止继续加载" else toast,
    )
}

/**
 * The recommended-event endpoint has no reliable "next" cursor after it reports
 * `more = false`.  A bottom retry therefore asks for a new recommendation batch,
 * while retaining every visible post and appending only unseen events.
 */
internal fun NotesFeedState.appendFreshRecommendedPage(
    posts: List<EventPost>,
    lastTime: Long,
    more: Boolean,
    squareCursor: String = "",
): NotesFeedState {
    val mergedPosts = mergeEventPosts(this.posts, posts)
    val addedCount = mergedPosts.size - this.posts.size
    val serverRelations = serverFollowedAuthors + serverFollowRelations(posts)
    val activeOverrides = activeFollowOverrides(serverRelations, followOverrides)
    return copy(
        posts = mergedPosts,
        lastTime = lastTime,
        squareCursor = squareCursor,
        more = more,
        followedAuthors = effectiveFollowRelations(serverRelations, activeOverrides),
        serverFollowedAuthors = serverRelations,
        followOverrides = activeOverrides,
        loadingMore = false,
        loadMoreError = null,
        autoLoadMoreBlocked = false,
        consecutiveEmptyPages = 0,
        toast = if (addedCount > 0) "已追加 $addedCount 条新推荐" else "暂未获取到新的推荐，已保留当前列表",
    )
}

internal fun shouldContinuePastDuplicatePage(
    before: NotesFeedState,
    after: NotesFeedState,
): Boolean = after.more &&
    !after.autoLoadMoreBlocked &&
    after.posts.size == before.posts.size &&
    (if (before.tab == NotesTab.RECOMMENDED) after.squareCursor != before.squareCursor
     else after.lastTime != before.lastTime) &&
    after.consecutiveEmptyPages > before.consecutiveEmptyPages

internal fun NotesFeedState.startFollowRequest(
    userId: Long,
    followed: Boolean,
    requestToken: Long,
): NotesFeedState {
    val overrides = activeFollowOverrides(
        serverFollowedAuthors,
        followOverrides + (userId to followed),
    )
    val effective = effectiveFollowRelations(serverFollowedAuthors, overrides) +
        if (userId !in serverFollowedAuthors) mapOf(userId to followed) else emptyMap()
    return copy(
        followedAuthors = effective,
        followOverrides = overrides,
        followRequestTokens = followRequestTokens + (userId to requestToken),
    )
}

internal fun canStartEventFollow(state: NotesFeedState, userId: Long): Boolean =
    userId > 0L && userId !in state.followRequestTokens

internal fun NotesFeedState.finishFollowRequest(
    userId: Long,
    requestToken: Long,
): NotesFeedState = if (followRequestTokens[userId] != requestToken) this
else copy(followRequestTokens = followRequestTokens - userId)

internal fun NotesFeedState.rollbackFollowRequest(
    userId: Long,
    previousOverride: Boolean?,
    requestToken: Long,
): NotesFeedState {
    if (followRequestTokens[userId] != requestToken) return this
    val restoredOverrides = if (previousOverride == null) followOverrides - userId
    else followOverrides + (userId to previousOverride)
    val activeOverrides = activeFollowOverrides(serverFollowedAuthors, restoredOverrides)
    return copy(
        followedAuthors = effectiveFollowRelations(serverFollowedAuthors, activeOverrides),
        followOverrides = activeOverrides,
        followRequestTokens = followRequestTokens - userId,
    )
}

/** A page response belongs to the version that started it; older responses leave newer state untouched. */
internal fun NotesFeedState.applyPageResult(
    responseVersion: Long,
    activeVersion: Long,
    result: AppResult<NotesRepository.Page>,
): NotesFeedState {
    if (responseVersion != activeVersion) return this
    return when (result) {
        is AppResult.Success -> appendPage(
            posts = result.data.posts,
            lastTime = result.data.lastTime,
            more = result.data.more,
            squareCursor = result.data.squareCursor,
        )
        is AppResult.Failure -> copy(
            loadingMore = false,
            loadMoreError = result.message.ifBlank { "更多动态加载失败" },
        )
    }
}

/** The official web client's event route is /event?id=<eventId>&uid=<authorId>. */
internal fun eventShareUrl(post: EventPost): String? =
    if (post.id > 0L && post.userId > 0L)
        "https://music.163.com/event?id=${post.id}&uid=${post.userId}"
    else null

internal fun eventShareText(post: EventPost): String = buildString {
    append(post.nickname.ifBlank { "云村用户" })
    append("分享的云村动态")
    if (post.content.isNotBlank()) append("：${post.content}")
    if (!post.songTitle.isNullOrBlank()) {
        append("\n正在听《${post.songTitle}》")
        post.songArtist?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
    }
    eventShareUrl(post)?.let { append("\n$it") }
}

class NotesViewModel(
    private val repo: NotesRepository,
    private val social: SocialRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NotesFeedState())
    val state: StateFlow<NotesFeedState> = _state.asStateFlow()
    private var requestVersion = 0L
    private val loadMoreGate = NotesLoadMoreGate()
    private var loadMoreJob: Job? = null
    private var refreshJob: Job? = null
    private var followRequestVersion = 0L

    init {
        refresh()
    }

    fun selectTab(tab: NotesTab) {
        if (_state.value.tab == tab && _state.value.posts.isNotEmpty()) return
        requestVersion += 1L
        _state.update {
            it.copy(
                tab = tab,
                posts = emptyList(),
                lastTime = -1L,
                squareCursor = "",
                more = false,
                followedAuthors = emptyMap(),
                serverFollowedAuthors = emptyMap(),
                loading = false,
                refreshing = false,
                loadingMore = false,
                error = null,
                loadMoreError = null,
            )
        }
        refresh()
    }

    fun refresh() {
        loadMoreJob?.cancel()
        val tab = _state.value.tab
        val version = ++requestVersion
        refreshJob?.cancel()
        _state.update { it.beginRefresh() }
        pageLog("refresh start tab=$tab cursor=-1 version=$version")
        refreshJob = viewModelScope.launch {
            when (val result = repo.load(tab)) {
                is AppResult.Success -> {
                    _state.update { state ->
                        if (state.tab != tab || version != requestVersion) state
                        else state.replaceFeed(
                            tab = tab,
                            posts = result.data.posts,
                            lastTime = result.data.lastTime,
                            more = result.data.more,
                            squareCursor = result.data.squareCursor,
                        ).copy(toast = if (state.posts.map { it.id } == result.data.posts.map { it.id })
                            "暂无新笔记，已重新获取" else "笔记已更新")
                    }
                    val applied = _state.value.tab == tab && version == requestVersion
                    pageLog(
                        "refresh result tab=$tab cursor=${result.data.lastTime} more=${result.data.more} " +
                            "received=${result.data.posts.size} added=${if (applied) result.data.posts.size else 0} applied=$applied",
                    )
                }
                is AppResult.Failure -> {
                    _state.update {
                        if (it.tab != tab || version != requestVersion) it
                        else it.copy(
                            loading = false,
                            refreshing = false,
                            error = result.message.ifBlank { "动态加载失败" },
                            toast = "刷新失败：${result.message.ifBlank { "动态加载失败" }}",
                        )
                    }
                    val applied = _state.value.tab == tab && version == requestVersion
                    pageLog("refresh failure tab=$tab cursor=-1 code=${result.code} applied=$applied")
                }
            }
        }
    }

    fun loadMore() = loadMore(resumeAutomaticPaging = true)

    /**
     * This is deliberately separate from pull-to-refresh.  Once a recommended
     * feed has reached the end, the service can only offer a fresh first page;
     * merge it into the visible feed instead of replacing it from the top.
     */
    fun fetchMoreRecommendedAtEnd() {
        var current = _state.value
        if (current.tab != NotesTab.RECOMMENDED || current.loading || current.refreshing || current.loadingMore || current.more) {
            return
        }
        if (!loadMoreGate.tryAcquire()) return
        current = _state.value
        if (current.tab != NotesTab.RECOMMENDED || current.loading || current.refreshing || current.loadingMore || current.more) {
            loadMoreGate.release()
            return
        }
        val version = requestVersion
        _state.update { state ->
            if (state.tab == NotesTab.RECOMMENDED && requestVersion == version) {
                state.copy(loadingMore = true, loadMoreError = null)
            } else state
        }
        loadMoreJob = viewModelScope.launch {
            try {
                when (val result = repo.load(NotesTab.RECOMMENDED)) {
                    is AppResult.Success -> _state.update { state ->
                        if (state.tab != NotesTab.RECOMMENDED || requestVersion != version) state
                        else state.appendFreshRecommendedPage(
                            posts = result.data.posts,
                            lastTime = result.data.lastTime,
                            more = result.data.more,
                            squareCursor = result.data.squareCursor,
                        )
                    }
                    is AppResult.Failure -> _state.update { state ->
                        if (state.tab != NotesTab.RECOMMENDED || requestVersion != version) state
                        else state.copy(
                            loadingMore = false,
                            loadMoreError = result.message.ifBlank { "获取新推荐失败" },
                        )
                    }
                }
            } finally {
                loadMoreGate.release()
                _state.update { state ->
                    if (state.tab == NotesTab.RECOMMENDED && requestVersion == version && state.loadingMore) {
                        state.copy(loadingMore = false)
                    } else state
                }
            }
        }
    }

    fun loadMoreAutomatically() {
        if (_state.value.autoLoadMoreBlocked) return
        loadMore(resumeAutomaticPaging = false)
    }

    private fun loadMore(resumeAutomaticPaging: Boolean) {
        var current = _state.value
        if (current.loading || current.refreshing || current.loadingMore || !current.more) {
            pageLog(
                "load-more skipped cursor=${current.lastTime} more=${current.more} " +
                    "loading=${current.loading || current.refreshing || current.loadingMore}",
            )
            return
        }
        if (!loadMoreGate.tryAcquire()) {
            pageLog("load-more skipped cursor=${current.lastTime} more=${current.more} loading=true gate=true")
            return
        }
        current = _state.value
        if (current.loading || current.refreshing || current.loadingMore || !current.more) {
            pageLog(
                "load-more skipped-after-gate cursor=${current.lastTime} more=${current.more} " +
                    "loading=${current.loading || current.refreshing || current.loadingMore}",
            )
            loadMoreGate.release()
            return
        }
        val tab = current.tab
        val version = requestVersion
        pageLog("load-more start tab=$tab cursor=${current.lastTime} more=${current.more} visible=${current.posts.size}")
        _state.update { state ->
            if (state.tab == tab && requestVersion == version) {
                state.copy(
                    loadingMore = true,
                    loadMoreError = null,
                    autoLoadMoreBlocked = if (resumeAutomaticPaging) false else state.autoLoadMoreBlocked,
                )
            } else state
        }
        loadMoreJob = viewModelScope.launch {
            try {
                var duplicatePagesThisRequest = 0
                while (true) {
                    val before = _state.value
                    if (before.tab != tab || requestVersion != version || !before.more) return@launch
                    _state.update { state ->
                        if (state.tab == tab && requestVersion == version) {
                            state.copy(
                                loadingMore = true,
                                loadMoreError = null,
                                autoLoadMoreBlocked = if (resumeAutomaticPaging) false else state.autoLoadMoreBlocked,
                            )
                        } else state
                    }
                    val result = repo.load(tab, before.lastTime, squareCursor = before.squareCursor)
                    _state.update { state ->
                        if (state.tab != tab) state
                        else state.applyPageResult(
                            responseVersion = version,
                            activeVersion = requestVersion,
                            result = result,
                        )
                    }
                    val after = _state.value
                    when (result) {
                        is AppResult.Success -> pageLog(
                            "load-more result tab=$tab cursor=${before.lastTime} nextCursor=${result.data.lastTime} " +
                                "more=${result.data.more} received=${result.data.posts.size} " +
                                "added=${(after.posts.size - before.posts.size).coerceAtLeast(0)} " +
                                "resultMore=${after.more} duplicates=${after.consecutiveEmptyPages}",
                        )
                        is AppResult.Failure -> pageLog(
                            "load-more failure tab=$tab cursor=${before.lastTime} code=${result.code} " +
                                "resultMore=${after.more}",
                        )
                    }
                    val canSkipDuplicate = result is AppResult.Success &&
                        shouldContinuePastDuplicatePage(before, after)
                    if (canSkipDuplicate) duplicatePagesThisRequest += 1
                    if (canSkipDuplicate && duplicatePagesThisRequest >= MAX_DUPLICATE_EVENT_PAGES_PER_REQUEST) {
                        _state.update { state ->
                            if (state.tab == tab && requestVersion == version) {
                                state.copy(
                                    autoLoadMoreBlocked = true,
                                    toast = "连续收到重复动态，已暂停自动加载，可继续加载",
                                )
                            } else state
                        }
                    }
                    if (result is AppResult.Failure || !canSkipDuplicate ||
                        duplicatePagesThisRequest >= MAX_DUPLICATE_EVENT_PAGES_PER_REQUEST
                    ) {
                        pageLog(
                            "load-more stop tab=$tab cursor=${after.lastTime} more=${after.more} " +
                                "visible=${after.posts.size} duplicates=${after.consecutiveEmptyPages}",
                        )
                        break
                    }
                }
            } finally {
                loadMoreGate.release()
                _state.update { state ->
                    if (state.tab == tab && requestVersion == version && state.loadingMore) {
                        state.copy(loadingMore = false)
                    } else state
                }
            }
        }
    }

    fun toggleFollow(post: EventPost) {
        val current = _state.value
        if (!canStartEventFollow(current, post.userId)) return
        val wasFollowed = current.followedAuthors[post.userId] ?: post.followed
        val previousOverride = current.followOverrides[post.userId]
        val target = !wasFollowed
        val requestToken = ++followRequestVersion
        _state.update { state ->
            state.startFollowRequest(post.userId, target, requestToken)
        }
        viewModelScope.launch {
            when (val result = social.follow(post.userId, target)) {
                is AppResult.Success -> {
                    if (result.data.code != 200) {
                        _state.update { state ->
                            if (state.followRequestTokens[post.userId] != requestToken) state else
                            state.rollbackFollowRequest(post.userId, previousOverride, requestToken).copy(
                                toast = result.data.message.ifBlank { "关注操作失败" },
                            )
                        }
                    } else {
                        _state.update { state ->
                            if (state.followRequestTokens[post.userId] != requestToken) state else
                            state.finishFollowRequest(post.userId, requestToken).copy(
                                toast = if (target) "已关注" else "已取消关注",
                            )
                        }
                    }
                }
                is AppResult.Failure -> _state.update { state ->
                    if (state.followRequestTokens[post.userId] != requestToken) state else
                    state.rollbackFollowRequest(post.userId, previousOverride, requestToken).copy(
                        toast = result.message.ifBlank { "关注操作失败" },
                    )
                }
            }
        }
    }

    fun loadComments(post: EventPost) {
        val existing = _state.value.commentsByPost[post.id]
        if (existing?.loading == true ||
            (existing?.comments?.isNotEmpty() == true && !existing.needsRefresh)
        ) return
        if (post.commentThreadId.isBlank()) {
            _state.update { state ->
                state.copy(
                    commentsByPost = state.commentsByPost + (post.id to commentStateForThread(post.commentThreadId)),
                )
            }
            return
        }
        _state.update { state ->
            state.copy(
                commentsByPost = state.commentsByPost + (
                    post.id to if (existing?.comments?.isNotEmpty() == true) {
                        existing.copy(loading = true, error = null, needsRefresh = false)
                    } else {
                        commentStateForThread(post.commentThreadId)
                    }
                ),
            )
        }
        viewModelScope.launch {
            when (val result = repo.loadComments(post)) {
                is AppResult.Success -> _state.update { state ->
                    state.copy(
                        commentsByPost = state.commentsByPost + (
                            post.id to EventCommentsState().appendCommentPage(result.data)
                        ),
                        posts = state.posts.map { visible ->
                            if (visible.id == post.id) visible.copy(commentCount =
                                result.data.total.takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                                    ?: visible.commentCount)
                            else visible
                        },
                    )
                }
                is AppResult.Failure -> _state.update { state ->
                    val current = state.commentsByPost[post.id] ?: EventCommentsState()
                    state.copy(
                        commentsByPost = state.commentsByPost + (
                            post.id to if (current.comments.isNotEmpty()) {
                                current.markRefreshNeeded(result.message.ifBlank { "评论加载失败" })
                            } else {
                                EventCommentsState(error = result.message.ifBlank { "评论加载失败" })
                            }
                        ),
                    )
                }
            }
        }
    }

    fun loadMoreComments(post: EventPost) {
        val current = _state.value.commentsByPost[post.id] ?: return
        if (current.loading || current.loadingMore || !current.more || post.commentThreadId.isBlank()) return
        val offset = current.nextOffset
        _state.update { state ->
            val latest = state.commentsByPost[post.id] ?: return@update state
            state.copy(
                commentsByPost = state.commentsByPost + (
                    post.id to latest.copy(loadingMore = true, error = null, autoLoadBlocked = false)
                ),
            )
        }
        viewModelScope.launch {
            when (val result = repo.loadComments(post, offset = offset)) {
                is AppResult.Success -> _state.update { state ->
                    val latest = state.commentsByPost[post.id] ?: return@update state
                    state.copy(
                        commentsByPost = state.commentsByPost + (post.id to latest.appendCommentPage(result.data)),
                        posts = state.posts.map { visible ->
                            if (visible.id == post.id) visible.copy(commentCount =
                                result.data.total.takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                                    ?: visible.commentCount)
                            else visible
                        },
                    )
                }
                is AppResult.Failure -> _state.update { state ->
                    val latest = state.commentsByPost[post.id] ?: return@update state
                    state.copy(
                        commentsByPost = state.commentsByPost + (
                            post.id to commentPageFailure(
                                latest,
                                result.message.ifBlank { "更多评论加载失败" },
                            )
                        ),
                    )
                }
            }
        }
    }

    fun toggleLike(post: EventPost) {
        if (post.id <= 0L || post.commentThreadId.isBlank() || post.id in _state.value.likingPostIds) return
        val target = !post.liked
        _state.update { it.copy(likingPostIds = it.likingPostIds + post.id) }
        viewModelScope.launch {
            when (val result = repo.likeEvent(post, target)) {
                is AppResult.Success -> _state.update { state ->
                    state.copy(
                        likingPostIds = state.likingPostIds - post.id,
                        posts = state.posts.map { visible ->
                            if (visible.id == post.id) visible.afterLike(target) else visible
                        },
                    )
                }
                is AppResult.Failure -> _state.update { state ->
                    state.copy(
                        likingPostIds = state.likingPostIds - post.id,
                        toast = result.message.ifBlank { "点赞操作失败" },
                    )
                }
            }
        }
    }

    fun postComment(post: EventPost, content: String) {
        val text = content.trim()
        if (text.isBlank() || post.id <= 0L || post.commentThreadId.isBlank() ||
            post.id in _state.value.postingCommentIds
        ) return
        _state.update { it.copy(postingCommentIds = it.postingCommentIds + post.id) }
        viewModelScope.launch {
            when (val result = repo.postEventComment(post, text)) {
                is AppResult.Success -> {
                    _state.update { state ->
                        val currentComments = state.commentsByPost[post.id] ?: EventCommentsState()
                        val comment = result.data.comment
                        val nextComments = if (comment == null) currentComments else currentComments.copy(
                            comments = listOf(comment) + currentComments.comments,
                            total = maxOf(currentComments.total + 1, (currentComments.comments.size + 1).toLong()),
                        )
                        state.copy(
                            postingCommentIds = state.postingCommentIds - post.id,
                            commentSubmissionVersions = state.commentSubmissionVersions + (
                                post.id to ((state.commentSubmissionVersions[post.id] ?: 0L) + 1L)
                            ),
                            commentsByPost = state.commentsByPost + (post.id to nextComments),
                            posts = state.posts.map { visible ->
                                if (visible.id == post.id) visible.afterComment() else visible
                            },
                            toast = "评论成功",
                        )
                    }
                }
                is AppResult.Failure -> _state.update { state ->
                    state.copy(
                        postingCommentIds = state.postingCommentIds - post.id,
                        toast = result.message.ifBlank { "评论发布失败" },
                    )
                }
            }
        }
    }

    fun toggleCommentLike(post: EventPost, comment: Comment) {
        val commentId = comment.commentId
        if (post.id <= 0L || commentId <= 0L || commentId in _state.value.mutatingCommentIds) return
        val target = !comment.liked
        _state.update { it.copy(mutatingCommentIds = it.mutatingCommentIds + commentId) }
        viewModelScope.launch {
            when (val result = repo.likeEventComment(post, commentId, target)) {
                is AppResult.Success -> _state.update { state ->
                    state.copy(
                        mutatingCommentIds = state.mutatingCommentIds - commentId,
                        commentsByPost = state.commentsByPost.mapValues { (_, comments) ->
                            comments.copy(
                                comments = comments.comments.map { visible ->
                                    if (visible.commentId == commentId) visible.afterLike(target) else visible
                                },
                            )
                        },
                    )
                }
                is AppResult.Failure -> _state.update { state ->
                    state.copy(
                        mutatingCommentIds = state.mutatingCommentIds - commentId,
                        toast = result.message.ifBlank { "评论点赞操作失败" },
                    )
                }
            }
        }
    }

    fun replyToComment(post: EventPost, comment: Comment, content: String) {
        val text = content.trim()
        val commentId = comment.commentId
        if (text.isBlank() || post.id <= 0L || commentId <= 0L ||
            commentId in _state.value.mutatingCommentIds
        ) return
        _state.update { it.copy(mutatingCommentIds = it.mutatingCommentIds + commentId) }
        viewModelScope.launch {
            when (val result = repo.replyEventComment(post, commentId, text)) {
                is AppResult.Success -> {
                    _state.update { state ->
                        state.copy(
                            mutatingCommentIds = state.mutatingCommentIds - commentId,
                            commentSubmissionVersions = state.commentSubmissionVersions + (
                                post.id to ((state.commentSubmissionVersions[post.id] ?: 0L) + 1L)
                            ),
                            posts = state.posts.map { visible ->
                                if (visible.id == post.id) visible.afterComment() else visible
                            },
                            toast = "回复成功",
                        )
                    }
                    refreshCommentsAfterReply(post)
                }
                is AppResult.Failure -> _state.update { state ->
                    state.copy(
                        mutatingCommentIds = state.mutatingCommentIds - commentId,
                        toast = result.message.ifBlank { "回复发布失败" },
                    )
                }
            }
        }
    }

    private fun refreshCommentsAfterReply(post: EventPost) {
        if (post.commentThreadId.isBlank()) return
        viewModelScope.launch {
            when (val result = repo.loadComments(post)) {
                is AppResult.Success -> _state.update { state ->
                    state.copy(
                        commentsByPost = state.commentsByPost + (
                            post.id to refreshedEventCommentThread(result.data)
                        ),
                        posts = state.posts.map { visible ->
                            if (visible.id == post.id) visible.copy(commentCount =
                                result.data.total.takeIf { it > 0L }
                                    ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                                    ?: visible.commentCount,
                            ) else visible
                        },
                    )
                }
                is AppResult.Failure -> _state.update { state ->
                    val current = state.commentsByPost[post.id] ?: EventCommentsState()
                    state.copy(
                        commentsByPost = state.commentsByPost + (
                            post.id to current.markRefreshNeeded(
                                "回复已发送，评论列表刷新失败，点击重新加载",
                            )
                        ),
                    )
                }
            }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }

    /** Pagination evidence is written only by debug builds and never includes dynamic content. */
    private fun pageLog(message: String) {
        if (BuildConfig.DEBUG) DbgLog.w("NotesPage", message)
    }
}

/** A bounded skip keeps one bottom-reach action from turning a broken server cursor into a request loop. */
private const val MAX_DUPLICATE_EVENT_PAGES_PER_REQUEST = 8

