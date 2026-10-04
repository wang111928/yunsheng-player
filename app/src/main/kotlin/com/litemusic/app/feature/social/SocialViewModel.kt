package com.litemusic.app.feature.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.SocialRepository
import com.litemusic.shared.model.Profile
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SocialLoadRequest(
    val generation: Long,
    val userId: Long,
    val tab: SocialViewModel.Tab,
)

/** Identifies one selected follow-list tab so older responses cannot repaint it. */
internal class SocialLoadTracker {
    private var nextGeneration = 0L
    private var active: SocialLoadRequest? = null

    fun begin(uid: Long, tab: SocialViewModel.Tab): SocialLoadRequest =
        SocialLoadRequest(++nextGeneration, uid, tab).also { active = it }

    fun isCurrent(request: SocialLoadRequest): Boolean = active == request
}

/** Retains a loaded follow/follower tab so switching tabs never paints an empty list first. */
internal class SocialTabCache {
    private val entries = mutableMapOf<Pair<Long, SocialViewModel.Tab>, SocialTabSnapshot>()

    fun get(uid: Long, tab: SocialViewModel.Tab): SocialTabSnapshot? = entries[uid to tab]
    fun put(uid: Long, tab: SocialViewModel.Tab, snapshot: SocialTabSnapshot) {
        entries[uid to tab] = snapshot
    }

    fun updateFollowed(uid: Long, userId: Long, followed: Boolean) {
        SocialViewModel.Tab.entries.forEach { tab ->
            val key = uid to tab
            val snapshot = entries[key] ?: return@forEach
            entries[key] = snapshot.copy(
                users = snapshot.users.map { user ->
                    if (user.userId == userId) user.copy(followed = followed) else user
                },
            )
        }
    }
}

internal data class SocialTabSnapshot(
    val users: List<Profile>,
    val hasMore: Boolean,
    val nextOffset: Int,
)

internal fun mergeSocialUsers(current: List<Profile>, incoming: List<Profile>): List<Profile> =
    (current + incoming).filter { it.userId > 0L }.distinctBy { it.userId }

/** A repeated server page is terminal; otherwise the near-end observer would request it forever. */
internal fun shouldContinueSocialPaging(currentSize: Int, mergedSize: Int, serverMore: Boolean): Boolean =
    serverMore && mergedSize > currentSize

class SocialViewModel(
    private val repo: SocialRepository,
) : ViewModel() {

    enum class Tab { FOLLOWS, FOLLOWEDS }

    data class UiState(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val loadMoreError: String? = null,
        val tab: Tab = Tab.FOLLOWS,
        val users: List<Profile> = emptyList(),
        val hasMore: Boolean = false,
        val nextOffset: Int = 0,
        val followedMap: Map<Long, Boolean> = emptyMap(),
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var uid: Long = 0
    private var loadJob: kotlinx.coroutines.Job? = null
    private var loadMoreJob: kotlinx.coroutines.Job? = null
    private val loads = SocialLoadTracker()
    private val tabCache = SocialTabCache()

    fun load(uid: Long, tab: Tab? = null, forceRefresh: Boolean = false) {
        this.uid = uid
        val selectedTab = tab ?: _state.value.tab
        tabCache.get(uid, selectedTab)?.takeUnless { forceRefresh }?.let { cached ->
            loadJob?.cancel()
            loadMoreJob?.cancel()
            _state.update { current ->
                current.copy(
                    loading = false,
                    refreshing = false,
                    loadingMore = false,
                    error = null,
                    loadMoreError = null,
                    tab = selectedTab,
                    users = cached.users,
                    hasMore = cached.hasMore,
                    nextOffset = cached.nextOffset,
                    followedMap = cached.users.associate { it.userId to it.followed },
                )
            }
            return
        }
        val request = loads.begin(uid, selectedTab)
        loadJob?.cancel()
        loadMoreJob?.cancel()
        _state.update { current ->
            val retainRows = current.tab == selectedTab && current.users.isNotEmpty()
            current.copy(
                tab = selectedTab,
                loading = !retainRows,
                refreshing = retainRows,
                loadingMore = false,
                error = null,
                loadMoreError = null,
                users = if (retainRows) current.users else emptyList(),
                hasMore = if (retainRows) current.hasMore else false,
                nextOffset = if (retainRows) current.nextOffset else 0,
            )
        }
        loadJob = viewModelScope.launch {
            val r = if (selectedTab == Tab.FOLLOWS) repo.follows(uid) else repo.followeds(uid)
            when (r) {
                is AppResult.Success -> _state.update { s ->
                    // 用接口返回的 followed 初始化按钮状态，否则「已关注」的人会显示成「关注」
                    if (!loads.isCurrent(request)) s else s.copy(
                        loading = false,
                        refreshing = false,
                        users = r.data.users.distinctBy { it.userId },
                        hasMore = r.data.more,
                        nextOffset = r.data.nextOffset,
                        followedMap = r.data.users.associate { it.userId to it.followed },
                    ).also { next ->
                        tabCache.put(
                            uid,
                            selectedTab,
                            SocialTabSnapshot(next.users, next.hasMore, next.nextOffset),
                        )
                    }
                }
                is AppResult.Failure -> _state.update { state ->
                    if (loads.isCurrent(request)) state.copy(
                        loading = false,
                        refreshing = false,
                        error = r.message,
                    ) else state
                }
            }
        }
    }

    fun loadMore() {
        val snapshot = _state.value
        if (uid <= 0L || snapshot.loading || snapshot.refreshing || snapshot.loadingMore || !snapshot.hasMore) return
        val selectedTab = snapshot.tab
        val request = loads.begin(uid, selectedTab)
        loadMoreJob?.cancel()
        _state.update { it.copy(loadingMore = true, loadMoreError = null) }
        loadMoreJob = viewModelScope.launch {
            val result = if (selectedTab == Tab.FOLLOWS) {
                repo.follows(uid, snapshot.nextOffset)
            } else {
                repo.followeds(uid, snapshot.nextOffset)
            }
            when (result) {
                is AppResult.Success -> _state.update { current ->
                    if (!loads.isCurrent(request) || current.tab != selectedTab) current else {
                        val merged = mergeSocialUsers(current.users, result.data.users)
                        val hasMore = shouldContinueSocialPaging(
                            currentSize = current.users.size,
                            mergedSize = merged.size,
                            serverMore = result.data.more,
                        )
                        current.copy(
                            users = merged,
                            followedMap = current.followedMap + result.data.users.associate { it.userId to it.followed },
                            loadingMore = false,
                            hasMore = hasMore,
                            nextOffset = result.data.nextOffset,
                        ).also { next ->
                            tabCache.put(uid, selectedTab, SocialTabSnapshot(next.users, next.hasMore, next.nextOffset))
                        }
                    }
                }
                is AppResult.Failure -> _state.update { current ->
                    if (!loads.isCurrent(request) || current.tab != selectedTab) current else current.copy(
                        loadingMore = false,
                        loadMoreError = result.message,
                    )
                }
            }
        }
    }

    fun toggleFollow(user: Profile) {
        val target = !(_state.value.followedMap[user.userId] ?: false)
        viewModelScope.launch {
            when (val r = repo.follow(user.userId, target)) {
                is AppResult.Success -> if (r.data.code == 200) {
                    tabCache.updateFollowed(uid, user.userId, target)
                    _state.update { s ->
                        s.copy(followedMap = s.followedMap + (user.userId to target), toast = "Follow state updated")
                    }
                } else {
                    _state.update { s ->
                        s.copy(toast = r.data.message.ifBlank { "Follow operation failed" })
                    }
                }
                // 失败时不要改按钮状态（例如风控 -462），只提示用户
                is AppResult.Failure -> _state.update { s ->
                    s.copy(toast = r.message.ifBlank { "操作失败" })
                }
            }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }

    fun setTab(tab: Tab) {
        load(uid, tab)
    }
}
