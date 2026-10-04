package com.litemusic.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.HomeRepository
import com.litemusic.app.data.mergeHomeSongPage
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** An overlap-only page is still useful when its server cursor moved to an unseen page. */
internal fun canContinueHomeSongPaging(
    serverHasMore: Boolean,
    previousOffset: Int,
    nextOffset: Int,
    previousSongCount: Int,
    mergedSongCount: Int,
): Boolean = serverHasMore && (
    mergedSongCount > previousSongCount || nextOffset != previousOffset
)

/**
 * 首页状态：顶部频道 + 每日推荐 / 推荐歌单 / 排行榜。
 *
 * 推荐歌单不写当天磁盘缓存（见 [HomeRepository]），因此：
 * - 冷启动 / 应用重开：init 里的 [load] 必定重新拉取真实推荐歌单；
 * - 后台回到前台：[onForeground] 经 [shouldRefreshPlaylists] 节流后再拉一次。
 * 网络失败时沿用仓库内上一次真实结果或保留当前数据，绝不把固定数据当成功。
 */
class HomeViewModel(
    private val repo: HomeRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val error: String? = null,
        val data: HomeRepository.HomeData? = null,
        val artistsRefreshing: Boolean = false,
        val channel: HomeChannel = defaultHomeChannel(),
        val homeSongsLoadingMore: Boolean = false,
        val homeSongsHasMore: Boolean = false,
        val homeSongsOffset: Int = 0,
        val homeSongsLoadMoreError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Non-recommend channels used to live in composable `remember` state.  A player navigation
     * disposes that composition, so returning always invoked the heart-throb endpoint again.
     * Keep the cache in this destination-scoped ViewModel instead.
     */
    private val _channelSession = MutableStateFlow(ChannelFeedSession())
    internal val channelSession: StateFlow<ChannelFeedSession> = _channelSession.asStateFlow()

    /** 上次成功刷新推荐歌单的时间；0 表示尚未刷新，首次必然刷新。 */
    private var lastPlaylistRefreshAtMs: Long = 0L
    private val generations = HomeRefreshGenerations()
    private var loadJob: Job? = null
    private var homeSongsLoadMoreJob: Job? = null
    private var artistsRefreshJob: Job? = null

    init {
        load()
    }

    fun load(force: Boolean = false) {
        val generation = generations.nextHomeRequest()
        // Invalidate an in-flight append before replacing the list with a fresh feed.
        generations.nextHomeSongRequest()
        loadJob?.cancel()
        homeSongsLoadMoreJob?.cancel()
        // A cancelled append may deliberately ignore its stale completion.  Clear its visible
        // progress state here so a failed refresh cannot leave the recommendation feed stuck in
        // an endless bottom loading indicator.
        _state.update { it.copy(homeSongsLoadingMore = false, homeSongsLoadMoreError = null) }
        loadJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = force, loading = it.data == null, error = null) }
            when (val r = repo.load(force)) {
                is AppResult.Success -> {
                    if (!generations.isCurrentHomeRequest(generation)) return@launch
                    lastPlaylistRefreshAtMs = System.currentTimeMillis()
                    _state.update { current ->
                        current.copy(
                            loading = false,
                            refreshing = false,
                            error = r.data.warning,
                            data = r.data,
                            homeSongsLoadingMore = false,
                            homeSongsHasMore = r.data.homeSongs.isNotEmpty(),
                            homeSongsOffset = 0,
                            homeSongsLoadMoreError = null)
                    }
                }
                is AppResult.Failure -> {
                    if (!generations.isCurrentHomeRequest(generation)) return@launch
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = r.message,
                            homeSongsLoadingMore = false)
                    }
                }
            }
        }
    }

    /** Refresh only the artist rail; it should not restart every home source. */
    fun refreshArtists() {
        if (artistsRefreshJob?.isActive == true) return
        artistsRefreshJob = viewModelScope.launch {
            _state.update { it.copy(artistsRefreshing = true) }
            when (val result = repo.topArtists(refresh = true)) {
                is AppResult.Success -> _state.update { current ->
                    current.copy(
                        data = current.data?.copy(artists = result.data, artistsError = null),
                        artistsRefreshing = false,
                    )
                }
                is AppResult.Failure -> _state.update { current ->
                    current.copy(
                        data = current.data?.copy(artistsError = result.message),
                        artistsRefreshing = false,
                    )
                }
            }
        }
    }

    /** 切换顶部频道。非推荐频道由自己的独立数据流加载，不能触发推荐页旧请求。 */
    fun selectChannel(channel: HomeChannel) {
        if (_state.value.channel == channel) return
        _state.update { it.copy(channel = channel) }
    }

    internal fun refreshChannel(channel: HomeChannel) {
        _channelSession.update { it.requestRefresh(channel) }
    }

    internal fun loadMoreChannel(channel: HomeChannel) {
        _channelSession.update { session ->
            session.copy(
                loadMoreRequests = session.loadMoreRequests +
                    (channel to ((session.loadMoreRequests[channel] ?: 0) + 1)),
            )
        }
    }

    internal fun nextChannelRequest(channel: HomeChannel): Long = generations.nextChannelRequest(channel)

    internal fun isCurrentChannelRequest(channel: HomeChannel, generation: Long): Boolean =
        generations.isCurrentChannelRequest(channel, generation)

    internal fun markChannelRequestStarted(channel: HomeChannel, feed: ChannelFeedState) {
        _channelSession.update { session ->
            session.copy(feeds = session.feeds + (channel to feed))
        }
    }

    internal fun commitChannelRequest(
        channel: HomeChannel,
        feed: ChannelFeedState,
        handledRefreshCount: Int,
        handledLoadMoreCount: Int,
    ) {
        _channelSession.update { session ->
            session.copy(
                feeds = session.feeds + (channel to feed),
                handledRefreshes = session.handledRefreshes + (channel to handledRefreshCount),
                handledLoadMoreRequests = session.handledLoadMoreRequests + (channel to handledLoadMoreCount),
            )
        }
    }

    /** Loads a distinct, cursor-backed recommendation page after the visible song list. */
    fun loadMoreHomeSongs() {
        val snapshot = _state.value
        val data = snapshot.data ?: return
        if (snapshot.homeSongsLoadingMore || !snapshot.homeSongsHasMore) return
        val generation = generations.nextHomeSongRequest()
        homeSongsLoadMoreJob?.cancel()
        _state.update { it.copy(homeSongsLoadingMore = true, homeSongsLoadMoreError = null) }
        homeSongsLoadMoreJob = viewModelScope.launch {
            when (val result = repo.moreHomeSongs(snapshot.homeSongsOffset, data.homeSongs.map { it.id }.toSet())) {
                is AppResult.Success -> {
                    if (!generations.isCurrentHomeSongRequest(generation)) return@launch
                    _state.update { current ->
                        val currentData = current.data ?: return@update current
                        val merged = mergeHomeSongPage(currentData.homeSongs, result.data.songs)
                        current.copy(
                            data = currentData.copy(homeSongs = merged),
                            homeSongsLoadingMore = false,
                            // A duplicate-only page can still advance a real server cursor. Keep
                            // paging in that case; only stop when neither the rows nor cursor
                            // moved, which prevents polling a backend that ignores its offset.
                            homeSongsHasMore = canContinueHomeSongPaging(
                                serverHasMore = result.data.hasMore,
                                previousOffset = current.homeSongsOffset,
                                nextOffset = result.data.nextOffset,
                                previousSongCount = currentData.homeSongs.size,
                                mergedSongCount = merged.size,
                            ),
                            homeSongsOffset = result.data.nextOffset,
                            homeSongsLoadMoreError = null,
                        )
                    }
                }
                is AppResult.Failure -> if (generations.isCurrentHomeSongRequest(generation)) {
                    _state.update {
                        it.copy(homeSongsLoadingMore = false, homeSongsLoadMoreError = result.message)
                    }
                }
            }
        }
    }

    /**
     * 应用回到前台时调用：距上次刷新超过节流窗口才重新拉取推荐歌单。
     * 冷启动 [lastPlaylistRefreshAtMs] = 0 必刷；同一次会话内不会反复发请求。
     */
    fun onForeground() {
        val now = System.currentTimeMillis()
        if (loadJob?.isActive == true || !shouldRefreshPlaylists(lastPlaylistRefreshAtMs, now)) return
        // Reuse the same single-flight path as pull-to-refresh so foreground work cannot race
        // against a manual refresh and restore stale content afterwards.
        load()
    }
}
