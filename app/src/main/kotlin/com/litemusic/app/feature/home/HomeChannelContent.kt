package com.litemusic.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.*
import com.litemusic.design.components.NmlButton as FilledTonalButton
import com.litemusic.design.components.NmlIconButton as FilledTonalIconButton
import com.litemusic.design.components.nmlPressable
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.litemusic.app.data.HomeRepository
import com.litemusic.app.feature.player.offlinePlayableQueue
import com.litemusic.app.feature.player.offlineQueueStartIndex
import com.litemusic.app.feature.player.offlineUnavailableLabel
import com.litemusic.app.feature.player.rememberOfflineUnavailableIds
import com.litemusic.app.feature.playlist.rememberPlaylistPlayer
import com.litemusic.app.ui.Routes
import com.litemusic.app.util.NetworkStatusMonitor
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.design.components.LoadingView
import com.litemusic.design.components.SongListItem
import com.litemusic.design.components.VinylDisc
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.RadioProgram
import com.litemusic.shared.model.RadioStation
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.Quality
import com.litemusic.shared.domain.QueueBuilder
import com.litemusic.player.OfflinePlaybackAvailability
import org.koin.compose.koinInject
import kotlinx.coroutines.flow.distinctUntilChanged

private const val PROGRAM_PAGE_SIZE = 30

/** State remains keyed by channel so changing tabs never reuses a previous tab's content. */
internal data class ChannelFeedState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val stations: List<RadioStation> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
)

/**
 * Retained independently from the composable tree. Navigating to the player removes the Home
 * composition, but its Nav destination's [HomeViewModel] survives, so returning cannot turn a
 * populated channel into a cold request.
 */
internal data class ChannelFeedSession(
    val feeds: Map<HomeChannel, ChannelFeedState> = emptyMap(),
    val refreshes: Map<HomeChannel, Int> = emptyMap(),
    val loadMoreRequests: Map<HomeChannel, Int> = emptyMap(),
    val handledRefreshes: Map<HomeChannel, Int> = emptyMap(),
    val handledLoadMoreRequests: Map<HomeChannel, Int> = emptyMap(),
)

internal fun ChannelFeedSession.requestRefresh(channel: HomeChannel): ChannelFeedSession = copy(
    // Keep paging counters monotonic across refreshes: handledLoadMoreRequests is retained.
    refreshes = refreshes + (channel to ((refreshes[channel] ?: 0) + 1)),
)

private fun appendStations(current: List<RadioStation>, page: List<RadioStation>): List<RadioStation> =
    (current + page).distinctBy { it.id }

/** A duplicated radio page is terminal; otherwise the near-end observer reissues it forever. */
internal fun mergeStationPage(
    current: ChannelFeedState,
    page: List<RadioStation>,
): ChannelFeedState {
    val merged = appendStations(current.stations, page)
    val added = merged.size - current.stations.size
    return current.copy(
        stations = merged,
        nextOffset = current.nextOffset + page.size,
        // The radio endpoints do not return a reliable total or `more` flag.  A short page
        // therefore is not proof that it is the final page: keep requesting while the server
        // still advances the feed, and stop only for an empty or fully repeated window.
        hasMore = page.isNotEmpty() && added > 0,
        error = null,
        loading = false,
        refreshing = false,
        loadingMore = false,
    )
}

/** Refreshes replace the first page, so tell the user whether its station identities changed. */
internal fun radioRefreshMessage(
    previous: List<RadioStation>,
    refreshed: List<RadioStation>,
): String = if (previous.map { it.id }.toSet() == refreshed.map { it.id }.toSet()) {
    "暂无新内容，已重新获取"
} else {
    "内容已更新"
}

private fun appendPlaylists(current: List<Playlist>, page: List<Playlist>): List<Playlist> =
    (current + page).distinctBy { it.id }

internal fun mergeCatalogPage(
    current: ChannelFeedState,
    page: List<Playlist>,
    total: Int,
    serverMore: Boolean,
    appending: Boolean,
): ChannelFeedState {
    val merged = if (appending) appendPlaylists(current.playlists, page) else page.distinctBy { it.id }
    val added = merged.size - if (appending) current.playlists.size else 0
    return ChannelFeedState(
        playlists = merged,
        nextOffset = (if (appending) current.nextOffset else 0) + page.size,
        hasMore = added > 0 && hasMoreCatalogRows(
            total = total,
            offset = if (appending) current.nextOffset else 0,
            received = page.size,
            serverMore = serverMore,
        ),
    )
}

internal fun hasMoreCatalogRows(
    total: Int,
    offset: Int,
    received: Int,
    serverMore: Boolean = false,
): Boolean = received > 0 && (serverMore || offset + received < total)

internal fun channelPageFailure(
    current: ChannelFeedState,
    message: String,
    appending: Boolean,
): ChannelFeedState = if (appending) {
    current.copy(loading = false, refreshing = false, loadingMore = false, error = message)
} else {
    current.takeIf { it.songs.isNotEmpty() || it.playlists.isNotEmpty() || it.stations.isNotEmpty() }
        ?.copy(loading = false, refreshing = false, error = message)
        ?: ChannelFeedState(error = message)
}

/** Paging is mutually exclusive with pull refresh so its effect cannot cancel a refresh request. */
internal fun canLoadMoreChannel(feed: ChannelFeedState): Boolean =
    feed.hasMore && !feed.loading && !feed.refreshing && !feed.loadingMore && feed.error == null

/** A pull refresh always supersedes a near-end event from the previously rendered list. */
internal fun shouldAppendChannelRequest(
    refreshCount: Int,
    handledRefreshCount: Int,
    loadMoreCount: Int,
    handledLoadMoreCount: Int,
): Boolean = refreshCount <= handledRefreshCount && loadMoreCount > handledLoadMoreCount

internal fun shouldStartChannelRequest(
    hasCachedFeed: Boolean,
    refreshCount: Int,
    handledRefreshCount: Int,
    loadMoreCount: Int,
    handledLoadMoreCount: Int,
): Boolean = !hasCachedFeed || refreshCount > handledRefreshCount || loadMoreCount > handledLoadMoreCount

/** Raw parser errors are useful to logs, but are not useful or readable in the station sheet. */
internal fun radioProgramLoadErrorMessage(message: String): String =
    if (message.contains("Unexpected JSON", ignoreCase = true) || message.contains("programDesc", ignoreCase = true)) {
        "节目内容读取失败，请稍后重试"
    } else {
        message.ifBlank { "节目读取失败，请稍后重试" }
    }

/** Each non-recommend channel owns a real source, its own cached screen state and pull refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeChannelContent(
    channel: HomeChannel,
    navController: NavController,
    viewModel: HomeViewModel,
) {
    val api: NMApi = koinInject()
    val homeRepository: HomeRepository = koinInject()
    val player = rememberPlaylistPlayer()
    val settings: SettingsStore = koinInject()
    val network: NetworkStatusMonitor = koinInject()
    val quality by settings.quality.collectAsState(initial = Quality.EXHIGH)
    val isOnline by network.isOnline.collectAsState()
    val cacheRevision by OfflinePlaybackAvailability.cacheRevision.collectAsState()
    val context = LocalContext.current
    val queueBuilder = remember { QueueBuilder() }
    val session by viewModel.channelSession.collectAsState()
    var selectedRadio by remember { mutableStateOf<RadioStation?>(null) }
    var programs by remember { mutableStateOf<List<RadioProgram>>(emptyList()) }
    var programLoading by remember { mutableStateOf(false) }
    var programError by remember { mutableStateOf<String?>(null) }
    var programRetry by remember { mutableIntStateOf(0) }
    var programNextOffset by remember { mutableIntStateOf(0) }
    var programHasMore by remember { mutableStateOf(false) }
    var programAppending by remember { mutableStateOf(false) }
    var programLoadMoreRequest by remember { mutableIntStateOf(0) }
    var musicToplists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var toplistsLoading by remember { mutableStateOf(false) }
    var toplistsError by remember { mutableStateOf<String?>(null) }
    var refreshNotice by remember { mutableStateOf<String?>(null) }
    val programListState = rememberLazyListState()
    LaunchedEffect(selectedRadio?.id) {
        programListState.scrollToItem(0)
    }

    fun refresh(target: HomeChannel) {
        viewModel.refreshChannel(target)
    }

    fun loadMore(target: HomeChannel) {
        val current = session.feeds[target] ?: return
        if (!canLoadMoreChannel(current)) return
        viewModel.loadMoreChannel(target)
    }

    fun retryMore(target: HomeChannel) {
        val current = session.feeds[target] ?: return
        if (current.hasMore && !current.loading && !current.refreshing && !current.loadingMore) {
            viewModel.loadMoreChannel(target)
        }
    }

    val refreshCount = session.refreshes[channel] ?: 0
    val loadMoreCount = session.loadMoreRequests[channel] ?: 0
    val feed = session.feeds[channel] ?: ChannelFeedState()
    val unavailableSongIds = rememberOfflineUnavailableIds(
        songs = feed.songs,
        isOnline = isOnline,
        quality = quality,
        cacheRevision = cacheRevision,
        context = context,
        queueBuilder = queueBuilder,
    )
    val playableSongs = remember(feed.songs, isOnline, unavailableSongIds) {
        offlinePlayableQueue(feed.songs, isOnline, unavailableSongIds)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(channel) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(channel, refreshCount) {
        if (channel != HomeChannel.MUSIC) return@LaunchedEffect
        toplistsLoading = true
        toplistsError = null
        when (val result = homeRepository.toplistDetail(forceRefresh = refreshCount > 0)) {
            is AppResult.Success -> musicToplists = result.data
            is AppResult.Failure -> toplistsError = result.message
        }
        toplistsLoading = false
    }

    LaunchedEffect(channel, refreshCount, loadMoreCount) {
        val handledRefreshCount = session.handledRefreshes[channel] ?: 0
        val handledLoadMoreCount = session.handledLoadMoreRequests[channel] ?: 0
        if (!shouldStartChannelRequest(
                hasCachedFeed = channel in session.feeds,
                refreshCount = refreshCount,
                handledRefreshCount = handledRefreshCount,
                loadMoreCount = loadMoreCount,
                handledLoadMoreCount = handledLoadMoreCount,
            )
        ) return@LaunchedEffect
        val generation = viewModel.nextChannelRequest(channel)
        val appending = shouldAppendChannelRequest(
            refreshCount = refreshCount,
            handledRefreshCount = handledRefreshCount,
            loadMoreCount = loadMoreCount,
            handledLoadMoreCount = handledLoadMoreCount,
        )
        val refreshing = refreshCount > handledRefreshCount
        if (!appending) selectedRadio = null
        val current = session.feeds[channel] ?: ChannelFeedState()
        val requestedOffset = if (appending) current.nextOffset else 0
        viewModel.markChannelRequestStarted(channel, current.copy(
            loading = !appending && !refreshing && current.songs.isEmpty() && current.playlists.isEmpty() && current.stations.isEmpty(),
            refreshing = refreshing,
            loadingMore = appending,
            error = null,
        ))
        val next = when (channel) {
            HomeChannel.HEARTTHROB -> when (val r = homeRepository.heartThrob()) {
                is AppResult.Success -> ChannelFeedState(songs = r.data)
                is AppResult.Failure -> ChannelFeedState(error = r.message)
            }
            HomeChannel.MUSIC -> when (val r = api.playlistCatalog(
                offset = requestedOffset,
                limit = CHANNEL_PAGE_SIZE,
            )) {
                is AppResult.Success -> if (r.data.code == 200) {
                    val received = r.data.playlists
                    mergeCatalogPage(current, received, r.data.total, r.data.more, appending)
                } else channelPageFailure(current, "音乐歌单读取失败(${r.data.code})", appending)
                is AppResult.Failure -> channelPageFailure(current, r.message, appending)
            }
            HomeChannel.PODCAST, HomeChannel.AUDIOBOOK -> when (val r = api.radioStations(
                audiobook = channel == HomeChannel.AUDIOBOOK,
                offset = requestedOffset,
                limit = CHANNEL_PAGE_SIZE,
            )) {
                is AppResult.Success -> if (r.data.code == 200) {
                    val received = r.data.stations
                    if (appending) mergeStationPage(current, received)
                    else ChannelFeedState(
                        stations = received.distinctBy { it.id },
                        nextOffset = received.size,
                        // See mergeStationPage: some hot-radio pages are shorter than the
                        // requested size even when a later offset has new rows.
                        hasMore = received.isNotEmpty(),
                    )
                }
                else channelPageFailure(current, "${channel.label}读取失败(${r.data.code})", appending)
                is AppResult.Failure -> channelPageFailure(current, r.message, appending)
            }
            HomeChannel.RECOMMEND -> ChannelFeedState()
        }
        if (viewModel.isCurrentChannelRequest(channel, generation)) {
            if (refreshing && (channel == HomeChannel.PODCAST || channel == HomeChannel.AUDIOBOOK)) {
                refreshNotice = if (next.error != null) {
                    "刷新失败：${next.error}"
                } else {
                    radioRefreshMessage(current.stations, next.stations)
                }
            }
            viewModel.commitChannelRequest(
                channel = channel,
                feed = next,
                handledRefreshCount = refreshCount,
                handledLoadMoreCount = loadMoreCount,
            )
        }
    }

    LaunchedEffect(channel, selectedRadio?.id, programRetry) {
        val radio = selectedRadio ?: return@LaunchedEffect
        programs = emptyList()
        programError = null
        programLoading = true
        programNextOffset = 0
        programHasMore = false
        when (val r = api.radioPrograms(radio.id, limit = PROGRAM_PAGE_SIZE)) {
            is AppResult.Success -> if (r.data.code == 200) {
                programs = r.data.programs.distinctBy { it.id }
                programNextOffset = r.data.programs.size
                programHasMore = r.data.programs.size >= PROGRAM_PAGE_SIZE
            }
            else programError = "节目读取失败(${r.data.code})"
            is AppResult.Failure -> programError = radioProgramLoadErrorMessage(r.message)
        }
        programLoading = false
    }

    LaunchedEffect(selectedRadio?.id, programLoadMoreRequest) {
        val radio = selectedRadio ?: return@LaunchedEffect
        if (programLoadMoreRequest == 0 || !programHasMore || programLoading || programAppending) return@LaunchedEffect
        programAppending = true
        val requestedOffset = programNextOffset
        when (val r = api.radioPrograms(radio.id, limit = PROGRAM_PAGE_SIZE, offset = requestedOffset)) {
            is AppResult.Success -> if (r.data.code == 200) {
                val newRows = r.data.programs.filterNot { row -> programs.any { it.id == row.id } }
                programs = programs + newRows
                programNextOffset = requestedOffset + r.data.programs.size
                programHasMore = r.data.programs.size >= PROGRAM_PAGE_SIZE && newRows.isNotEmpty()
                programError = null
            } else programError = "更多节目读取失败(${r.data.code})"
            is AppResult.Failure -> programError = radioProgramLoadErrorMessage(r.message)
        }
        programAppending = false
    }

    LaunchedEffect(selectedRadio?.id, programs.size, programHasMore, programError) {
        if (selectedRadio == null || !programHasMore || programError != null) return@LaunchedEffect
        snapshotFlow {
            val layout = programListState.layoutInfo
            layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd && !programLoading && !programAppending) programLoadMoreRequest += 1
        }
    }

    selectedRadio?.let { radio ->
        ModalBottomSheet(onDismissRequest = { selectedRadio = null }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(radio.name, style = MaterialTheme.typography.titleLarge)
                programError?.let { message ->
                    TextButton(onClick = { programRetry += 1 }) {
                        Text("$message · 点击重试", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (programLoading) LoadingView()
                else LazyColumn(state = programListState, modifier = Modifier.heightIn(max = 420.dp)) {
                    items(programs, key = { it.id }) { program ->
                        Row(
                            modifier = Modifier.fillMaxWidth().nmlPressable(onClick = {
                                programError = null
                                player.playProgram(
                                    navController,
                                    program.copy(stationCoverUrl = program.stationCoverUrl.ifBlank { radio.coverUrl }),
                                ) { message ->
                                    programError = radioProgramLoadErrorMessage(message)
                                }
                            }).padding(vertical = 8.dp),
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(program.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                program.text.takeIf { it.isNotBlank() }?.let { description ->
                                    Text(
                                        description,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    programError = null
                                    player.playProgram(
                                        navController,
                                        program.copy(stationCoverUrl = program.stationCoverUrl.ifBlank { radio.coverUrl }),
                                    ) { message ->
                                        programError = radioProgramLoadErrorMessage(message)
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "播放节目")
                            }
                        }
                    }
                    if (programHasMore) item(key = "program-load-more") {
                        TextButton(
                            onClick = { if (!programAppending) programLoadMoreRequest += 1 },
                            enabled = !programAppending,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (programAppending) "正在加载更多节目…" else "加载更多节目") }
                    }
                    if (programs.isEmpty()) item { Text(programError ?: "暂无可播放节目", Modifier.padding(20.dp)) }
                }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = feed.refreshing,
        onRefresh = { refresh(channel) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    when (channel) {
                        HomeChannel.HEARTTHROB -> "心动模式"
                        HomeChannel.MUSIC -> "音乐 · 热门歌单"
                        // These are genuinely paged hot-radio feeds. Do not describe
                        // them as personalised recommendations when the API is not one.
                        HomeChannel.PODCAST -> "播客 · 热门电台"
                        HomeChannel.AUDIOBOOK -> "听书 · 热门有声书"
                        HomeChannel.RECOMMEND -> "推荐"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (channel == HomeChannel.MUSIC) {
                item(key = "music-toplists-title") {
                    Text("排行榜", style = MaterialTheme.typography.titleLarge)
                }
                if (toplistsLoading) item(key = "music-toplists-loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                toplistsError?.let { message -> item(key = "music-toplists-error") {
                    Text(
                        "$message · 下拉刷新重试",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } }
                if (musicToplists.isNotEmpty()) item(key = "music-toplists") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(musicToplists.take(12), key = { it.id }) { toplist ->
                            Column(
                                modifier = Modifier.width(132.dp).nmlPressable(onClick = {
                                    navController.navigate(Routes.playlist(toplist.id))
                                }),
                            ) {
                                AsyncImage(
                                    model = toplist.coverThumb,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(132.dp).clip(RoundedCornerShape(12.dp)),
                                )
                                Text(
                                    toplist.name,
                                    modifier = Modifier.padding(top = 6.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                toplist.updateFrequency.takeIf { it.isNotBlank() }?.let { frequency ->
                                    Text(frequency, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            if (channel == HomeChannel.HEARTTHROB && feed.songs.isNotEmpty()) {
                item(key = "heart-hero") {
                    HeartThrobHero(
                        song = feed.songs.first(),
                        enabled = playableSongs.isNotEmpty(),
                        onPlay = { player.playSongs(navController, playableSongs, 0) },
                    )
                }
                item(key = "heart-queue-title") {
                    Text("为你续播", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (channel == HomeChannel.PODCAST && feed.stations.isNotEmpty()) {
                item(key = "podcast-featured") {
                    RadioChannelFeature(
                        title = "热门播客",
                        subtitle = "发现值得反复收听的声音",
                        stations = feed.stations.take(6),
                        onSelect = { selectedRadio = it },
                    )
                }
                item(key = "podcast-all-title") { Text("更多热门电台", style = MaterialTheme.typography.titleMedium) }
            }
            if (channel == HomeChannel.AUDIOBOOK && feed.stations.isNotEmpty()) {
                item(key = "audiobook-benefit") {
                    AudiobookGuideCard()
                }
                item(key = "audiobook-featured") {
                    RadioChannelFeature(
                        title = "热门听书",
                        subtitle = "榜单与精选内容",
                        stations = feed.stations.take(6),
                        onSelect = { selectedRadio = it },
                    )
                }
                item(key = "audiobook-all-title") { Text("更多热门有声书", style = MaterialTheme.typography.titleMedium) }
            }
            if (feed.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            feed.error?.let { message -> item {
                Text(
                    "$message · 点此重试",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().nmlPressable(onClick = { refresh(channel) }).padding(vertical = 8.dp),
                )
            } }
            if (refreshNotice != null && (channel == HomeChannel.PODCAST || channel == HomeChannel.AUDIOBOOK)) {
                item(key = "radio-refresh-notice") {
                    Text(
                        refreshNotice.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (refreshNotice!!.startsWith("刷新失败")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(feed.songs, key = { _, song -> song.id }) { index, song ->
                val queueIndex = offlineQueueStartIndex(
                    songs = feed.songs,
                    sourceIndex = index,
                    isOnline = isOnline,
                    unavailableIds = unavailableSongIds,
                )
                SongListItem(song = song, index = index,
                    enabled = queueIndex != null,
                    disabledReason = offlineUnavailableLabel(isOnline, unavailableSongIds, song.id),
                    onClick = {
                    queueIndex?.let { player.playSongs(navController, playableSongs, it) }
                }, onMv = song.mv.takeIf { it > 0 }?.let { mvId ->
                    { navController.navigate(Routes.mv(mvId)) }
                })
            }
            items(feed.playlists, key = { it.id }) { playlist ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface).nmlPressable(onClick = {
                    navController.navigate(Routes.playlist(playlist.id))
                }).padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    AsyncImage(
                        playlist.cover,
                        null,
                        Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Text(playlist.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            items(feed.stations, key = { it.id }) { radio ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface).nmlPressable(onClick = { selectedRadio = radio }).padding(10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    AsyncImage(
                        radio.coverUrl,
                        null,
                        Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(radio.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(radio.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (feed.loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!feed.loading && !feed.loadingMore && feed.hasMore &&
                (channel == HomeChannel.PODCAST || channel == HomeChannel.AUDIOBOOK)) {
                item(key = "radio-next-page") {
                    TextButton(onClick = { retryMore(channel) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (feed.error == null) "继续加载" else "下一页失败，点击重试")
                    }
                }
            }
            if (!feed.loading && !feed.hasMore && feed.stations.isNotEmpty()) {
                item(key = "radio-end") {
                    Text("已加载当前全部${channel.label} · 下拉可重新获取", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (channel == HomeChannel.MUSIC && !feed.loading && !feed.hasMore && feed.playlists.isNotEmpty()) {
                item { Text("已加载全部歌单", style = MaterialTheme.typography.bodySmall) }
            }
            if (!feed.loading && feed.error == null && feed.songs.isEmpty() && feed.playlists.isEmpty() && feed.stations.isEmpty()) {
                item { Text("${channel.label}暂无内容") }
            }
        }
    }

    LaunchedEffect(
        channel,
        feed.hasMore,
        feed.loading,
        feed.refreshing,
        feed.loadingMore,
        feed.error,
        feed.stations.size,
        feed.playlists.size,
    ) {
        if (!canLoadMoreChannel(feed)) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= listState.layoutInfo.totalItemsCount - 4) loadMore(channel)
            }
    }
}

private const val CHANNEL_PAGE_SIZE = 30

@Composable
private fun HeartThrobHero(song: Song, enabled: Boolean, onPlay: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text("此刻为你而动", style = MaterialTheme.typography.titleMedium)
            VinylDisc(song.coverUrl, spinning = false, modifier = Modifier.size(190.dp).padding(top = 14.dp))
            Text(song.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp, start = 24.dp, end = 24.dp))
            Text(song.artistNames, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            FilledTonalButton(onClick = onPlay, enabled = enabled, modifier = Modifier.padding(top = 14.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("播放心动歌单", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun RadioChannelFeature(
    title: String,
    subtitle: String,
    stations: List<RadioStation>,
    onSelect: (RadioStation) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(stations, key = { "feature-${it.id}" }) { station ->
                Column(
                    modifier = Modifier.width(128.dp).nmlPressable(onClick = { onSelect(station) }),
                ) {
                    AsyncImage(
                        model = station.coverUrl,
                        contentDescription = station.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(128.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Text(station.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AudiobookGuideCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("听书 · 每日好故事", style = MaterialTheme.typography.titleMedium)
                Text("按榜单、分类和热度发现想听的内容", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
    }
}
