package com.litemusic.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import com.litemusic.app.data.PlaylistRepository
import com.litemusic.app.feature.comment.CommentSheetController
import com.litemusic.app.feature.player.offlinePlayableQueue
import com.litemusic.app.feature.player.offlineQueueStartIndex
import com.litemusic.app.feature.player.offlineUnavailableLabel
import com.litemusic.app.feature.player.rememberOfflineUnavailableIds
import com.litemusic.app.feature.player.SongActionSheet
import com.litemusic.app.feature.playlist.rememberPlaylistPlayer
import com.litemusic.app.ui.Routes
import com.litemusic.app.ui.navigateToBottomTab
import com.litemusic.app.util.NetworkStatusMonitor
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.design.components.CoverCard
import com.litemusic.design.components.DailyBanner
import com.litemusic.design.components.EmptyView
import com.litemusic.design.components.ErrorView
import com.litemusic.design.components.KgEntry
import com.litemusic.design.components.LoadingView
import com.litemusic.design.components.NmSnackbarHost
import com.litemusic.design.components.NmlSearchEntryBar
import com.litemusic.design.components.SectionHeader
import com.litemusic.design.components.SongListItem
import com.litemusic.design.components.nmlPressable
import com.litemusic.design.theme.NmlTheme
import com.litemusic.player.OfflinePlaybackAvailability
import com.litemusic.player.PlaybackController
import com.litemusic.shared.domain.QueueBuilder
import com.litemusic.shared.model.Song
import com.litemusic.shared.model.Artist
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.Quality
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 发现音乐（对齐网易云手机端首页）：
 * 顶部频道栏（心动/推荐/音乐/播客/听书）→ 搜索入口 → 轮播横幅 → 金刚区。
 * → 推荐歌单横滑 → 排行榜横滑 → 每日推荐曲目列表。
 *
 * 频道分别展示每日推荐、心动智能续播、音乐榜单、电台和有声书。
 * 推荐歌单不落当天磁盘缓存，重开应用或回到前台时会重新拉取真实数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val player = rememberPlaylistPlayer()
    val controller: PlaybackController = koinInject()
    val playlistRepository: PlaylistRepository = koinInject()
    val settings: SettingsStore = koinInject()
    val network: NetworkStatusMonitor = koinInject()
    val quality by settings.quality.collectAsState(initial = Quality.EXHIGH)
    val isOnline by network.isOnline.collectAsState()
    val cacheRevision by OfflinePlaybackAvailability.cacheRevision.collectAsState()
    val context = LocalContext.current
    val queueBuilder = remember { QueueBuilder() }
    // Radio changes can occur while this destination is stopped. Refresh before rendering a
    // cached recommendation list so unavailable rows never briefly look playable.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { network.refresh() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var actionSong by remember { mutableStateOf<Song?>(null) }
    val data = state.data
    val daily = data?.daily ?: emptyList()
    val homeSongs = data?.homeSongs ?: emptyList()
    val playlists = data?.playlists ?: emptyList()
    val toplists = data?.toplists ?: emptyList()
    val artists = data?.artists ?: emptyList()
    val homeUnavailableIds = rememberOfflineUnavailableIds(
        songs = homeSongs,
        isOnline = isOnline,
        quality = quality,
        cacheRevision = cacheRevision,
        context = context,
        queueBuilder = queueBuilder,
    )
    val playableHomeSongs = remember(homeSongs, isOnline, homeUnavailableIds) {
        offlinePlayableQueue(homeSongs, isOnline, homeUnavailableIds)
    }
    // Retain the recommendation position while visiting a different home channel.
    val recommendationListState = rememberLazyListState()

    actionSong?.let { song ->
        SongActionSheet(
            song = song,
            onDismiss = { actionSong = null },
            onPlayNext = {
                player.enqueueNext(song) { message -> scope.launch { snackbar.showSnackbar(message) } }
            },
            onLike = {
                scope.launch {
                    val liked = song.id in playlistRepository.likedIds.value
                    when (val result = playlistRepository.like(song.id, !liked)) {
                        is AppResult.Failure -> snackbar.showSnackbar(result.message)
                        is AppResult.Success -> Unit
                    }
                }
            },
            onComment = { CommentSheetController.open(song.id) },
            onArtist = song.ar.firstOrNull()?.takeIf { it.id > 0L }?.let { artist ->
                { navController.navigate(Routes.artist(artist.id)) }
            },
            onUnavailable = { action -> scope.launch { snackbar.showSnackbar("${action.label} 暂无可用接口") } },
        )
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        HomeChannelBar(selected = state.channel, onSelect = viewModel::selectChannel)
        NmlSearchEntryBar(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            onClick = { navController.navigate(Routes.SEARCH) { launchSingleTop = true } },
        )

        if (state.channel != HomeChannel.RECOMMEND) {
            HomeChannelContent(state.channel, navController, viewModel)
        } else {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.loading && data == null -> LoadingView()
                    state.error != null && data == null ->
                        ErrorView(state.error ?: "加载失败", onRetry = { viewModel.load(force = true) })
                    data != null -> PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = { viewModel.load(force = true) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = recommendationListState,
                            contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                        ) {
                            item(key = "banner") {
                                DailyBanner(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    coverUrl = daily.firstOrNull()?.coverThumbUrl,
                                    subtitle = if (daily.isEmpty()) {
                                        "根据你的口味，每天为你推荐好歌"
                                    } else {
                                        "根据你的口味，今天为你推荐 " + daily.size + " 首"
                                    },
                                    onClick = { navController.navigate(Routes.DAILY) },
                                )
                            }
                            item(key = "quick") {
                                QuickEntries(onEntry = { entry ->
                                    when (entry) {
                                        HomeQuickEntry.DAILY -> navController.navigate(Routes.DAILY)
                                        HomeQuickEntry.PLAYLISTS ->
                                            navController.navigateToBottomTab(Routes.PLAYLISTS)
                                        HomeQuickEntry.TOPLIST -> navController.navigate(Routes.TOPLISTS)
                                        HomeQuickEntry.FM -> player.playFm(navController) { msg ->
                                            scope.launch { snackbar.showSnackbar(msg) }
                                        }
                                    }
                                })
                            }
                            state.error?.let { message ->
                                item(key = "refresh-error") {
                                    Text(
                                        message,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            if (playlists.isNotEmpty()) {
                                item(key = "pl-h") { SectionHeader("推荐歌单") }
                                item(key = "pl") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        items(playlists, key = { it.id }) { pl ->
                                            CoverCard(playlist = pl, onClick = {
                                                navController.navigate(Routes.playlist(pl.id))
                                            })
                                        }
                                    }
                                }
                            }
                            if (toplists.isNotEmpty()) {
                                item(key = "top-h") { SectionHeader("排行榜") }
                                item(key = "top") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        items(toplists, key = { it.id }) { tl ->
                                            CoverCard(playlist = tl, showSubtitle = true, onClick = {
                                                navController.navigate(Routes.playlist(tl.id))
                                            })
                                        }
                                    }
                                }
                            }
                            if (artists.isNotEmpty()) {
                                item(key = "artists-h") {
                                    SectionHeader(
                                        title = "推荐歌手",
                                        actionText = if (state.artistsRefreshing) "刷新中…" else "换一批",
                                        onAction = viewModel::refreshArtists,
                                    )
                                }
                                item(key = "artists") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        items(artists, key = { it.id }) { artist ->
                                            HomeArtistCard(artist) {
                                                navController.navigate(Routes.artist(artist.id))
                                            }
                                        }
                                    }
                                }
                                data?.artistsError?.let { message ->
                                    item(key = "artists-refresh-error") {
                                        Text(
                                            "推荐歌手刷新失败：$message",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                            } else {
                                data?.artistsError?.let { message ->
                                    item(key = "artists-error") {
                                        Text(
                                            "推荐歌手暂不可用：$message",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                            if (homeSongs.isNotEmpty()) {
                                item(key = "home-songs-h") {
                                    SectionHeader(
                                        title = "为你推荐 · " + homeSongs.size + " 首",
                                        playAll = { player.playSongs(navController, playableHomeSongs, 0) },
                                        onQueue = { navController.navigate(Routes.PLAYER) },
                                    )
                                }
                                itemsIndexed(homeSongs, key = { _, s -> s.id }) { index, song ->
                                    val queueIndex = offlineQueueStartIndex(
                                        songs = homeSongs,
                                        sourceIndex = index,
                                        isOnline = isOnline,
                                        unavailableIds = homeUnavailableIds,
                                    )
                                    SongListItem(
                                        song = song,
                                        index = index,
                                        enabled = queueIndex != null,
                                        disabledReason = offlineUnavailableLabel(
                                            isOnline,
                                            homeUnavailableIds,
                                            song.id,
                                        ),
                                        onMv = song.mv.takeIf { it > 0 }?.let { mvId ->
                                            { navController.navigate(Routes.mv(mvId)) }
                                        },
                                        onMore = { actionSong = song },
                                        onClick = {
                                            queueIndex?.let { player.playSongs(navController, playableHomeSongs, it) }
                                        },
                                    )
                                }
                                if (state.homeSongsLoadingMore) {
                                    item(key = "home-songs-loading-more") {
                                        androidx.compose.material3.LinearProgressIndicator(
                                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                        )
                                    }
                                }
                                state.homeSongsLoadMoreError?.let { message ->
                                    item(key = "home-songs-load-more-error") {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                message,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.weight(1f),
                                            )
                                            androidx.compose.material3.TextButton(
                                                onClick = viewModel::loadMoreHomeSongs,
                                            ) { Text("重试") }
                                        }
                                    }
                                }
                            }
                        }
                        LaunchedEffect(
                            state.homeSongsHasMore,
                            state.homeSongsLoadingMore,
                            state.homeSongsLoadMoreError,
                            homeSongs.size,
                        ) {
                            if (!state.homeSongsHasMore || state.homeSongsLoadingMore || state.homeSongsLoadMoreError != null) {
                                return@LaunchedEffect
                            }
                            snapshotFlow {
                                recommendationListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                            }.distinctUntilChanged().collect { lastVisible ->
                                if (lastVisible >= recommendationListState.layoutInfo.totalItemsCount - 4) {
                                    viewModel.loadMoreHomeSongs()
                                }
                            }
                        }
                    }
                    else -> EmptyView("暂无内容")
                }
            }
        }
    }
        NmSnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun HomeArtistCard(artist: Artist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(18.dp))
            .nmlPressable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = artist.picUrl,
            contentDescription = artist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            artist.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 顶部频道栏：选中项加粗 + 底部小圆条。 */
@Composable
private fun HomeChannelBar(
    selected: HomeChannel,
    onSelect: (HomeChannel) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        homeChannels().forEach { channel ->
            val isSelected = channel == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .nmlPressable(onClick = { onSelect(channel) })
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    channel.label,
                    style = if (isSelected) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (isSelected) NmlTheme.colors.onSurface else NmlTheme.colors.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .width(18.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isSelected) NmlTheme.colors.primary else Color.Transparent),
                )
            }
        }
    }
}

/** 金刚区快捷分类。 */
@Composable
private fun QuickEntries(onEntry: (HomeQuickEntry) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        homeQuickEntries().chunked(4).forEach { rowEntries ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                rowEntries.forEach { entry ->
                    KgEntry(icon = quickEntryIcon(entry), label = entry.label, onClick = { onEntry(entry) })
                }
                repeat(4 - rowEntries.size) { Spacer(Modifier.width(68.dp)) }
            }
        }
    }
}

private fun quickEntryIcon(entry: HomeQuickEntry): ImageVector = when (entry) {
    HomeQuickEntry.DAILY -> Icons.Default.DateRange
    HomeQuickEntry.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
    HomeQuickEntry.TOPLIST -> Icons.Default.LocalFireDepartment
    HomeQuickEntry.FM -> Icons.Default.Radio
}

