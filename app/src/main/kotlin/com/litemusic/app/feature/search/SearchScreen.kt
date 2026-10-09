package com.litemusic.app.feature.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import com.litemusic.app.data.PlaylistRepository
import com.litemusic.app.data.SearchRepository
import com.litemusic.app.feature.comment.CommentSheetController
import com.litemusic.app.feature.player.offlinePlayableQueue
import com.litemusic.app.feature.player.offlineQueueStartIndex
import com.litemusic.app.feature.player.offlineUnavailableLabel
import com.litemusic.app.feature.player.rememberOfflineUnavailableIds
import com.litemusic.app.feature.player.SongActionSheet
import com.litemusic.app.feature.playlist.rememberPlaylistPlayer
import com.litemusic.app.ui.Routes
import com.litemusic.app.util.NetworkStatusMonitor
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.design.components.NmlCard
import com.litemusic.design.components.NmlTactileSurface
import com.litemusic.design.components.SectionHeader
import com.litemusic.design.components.SongListItem
import com.litemusic.design.theme.NmlTheme
import com.litemusic.player.OfflinePlaybackAvailability
import com.litemusic.player.PlaybackController
import com.litemusic.shared.domain.QueueBuilder
import com.litemusic.shared.model.Artist
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Profile
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.Quality
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 搜索页（对齐网易云手机端）：
 * 顶部圆角搜索框（返回 + 输入 + 清空）→ 未输入时展示「搜索历史 / 热门搜索」发现面板
 * → 输入后按「单曲 / 歌手 / 歌单 / 用户」圆角分组展示结果，每组可展开 / 收起。
 *
 * 结果全部来自真实搜索接口；任一类失败则该组为空，不做任何假数据填充。
 */
@Composable
fun SearchScreen(
    navController: NavController,
    initialKeyword: String = "",
    viewModel: SearchViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val player = rememberPlaylistPlayer()
    val context = LocalContext.current
    val controller: PlaybackController = koinInject()
    val playlistRepository: PlaylistRepository = koinInject()
    val settings: SettingsStore = koinInject()
    val network: NetworkStatusMonitor = koinInject()
    val quality by settings.quality.collectAsState(initial = Quality.EXHIGH)
    val isOnline by network.isOnline.collectAsState()
    val cacheRevision by OfflinePlaybackAvailability.cacheRevision.collectAsState()
    val queueBuilder = remember { QueueBuilder() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { network.refresh() }
    val scope = rememberCoroutineScope()
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var selectedArtist by remember { mutableStateOf<Artist?>(null) }
    val resultSongs = state.results?.songs.orEmpty()
    val unavailableSongIds = rememberOfflineUnavailableIds(
        songs = resultSongs,
        isOnline = isOnline,
        quality = quality,
        cacheRevision = cacheRevision,
        context = context,
        queueBuilder = queueBuilder,
    )
    val playableSongs = remember(resultSongs, isOnline, unavailableSongIds) {
        offlinePlayableQueue(resultSongs, isOnline, unavailableSongIds)
    }

    LaunchedEffect(initialKeyword) {
        if (initialKeyword.isNotBlank()) {
            viewModel.setKeyword(initialKeyword)
            viewModel.commitKeyword()
        }
    }

    selectedArtist?.let { artist ->
        ArtistInfoDialog(
            artist = artist,
            onDismiss = { selectedArtist = null },
            onOpenDetail = {
                selectedArtist = null
                navController.navigate(Routes.artist(artist.id))
            },
        )
    }

    actionSong?.let { song ->
        SongActionSheet(
            song = song,
            onDismiss = { actionSong = null },
            onPlayNext = {
                player.enqueueNext(song) { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            },
            onLike = {
                scope.launch {
                    val liked = song.id in playlistRepository.likedIds.value
                    when (val result = playlistRepository.like(song.id, !liked)) {
                        is AppResult.Failure -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        is AppResult.Success -> Unit
                    }
                }
            },
            onComment = { CommentSheetController.open(song.id) },
            onArtist = song.ar.firstOrNull()?.takeIf { it.id > 0 }?.let { artist ->
                { selectedArtist = artist }
            },
            onUnavailable = { action ->
                Toast.makeText(context, action.label + " 暂无可用接口", Toast.LENGTH_SHORT).show()
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        SearchTopBar(
            keyword = state.keyword,
            onKeyword = viewModel::setKeyword,
            onBack = { navController.popBackStack() },
            onSearch = { viewModel.commitKeyword() },
        )

        val results = state.results
        when {
            state.keyword.isBlank() -> DiscoveryPanel(
                hots = state.hots.map { it.first }.filter { it.isNotBlank() },
                history = state.history,
                onPick = { kw ->
                    viewModel.setKeyword(kw)
                    viewModel.commitKeyword()
                },
                onRemoveHistory = viewModel::removeHistory,
            )
            state.searching && results == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            state.error != null && results == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.error ?: "搜索失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NmlTheme.colors.onSurfaceMuted,
                )
            }
            results != null -> ResultList(
                bundle = results,
                expanded = state.expanded,
                isOnline = isOnline,
                unavailableSongIds = unavailableSongIds,
                onToggleSection = viewModel::toggleSection,
                loadingMore = state.loadingMore,
                onLoadMore = viewModel::loadMore,
                onPlaySong = { index ->
                    offlineQueueStartIndex(
                        songs = results.songs,
                        sourceIndex = index,
                        isOnline = isOnline,
                        unavailableIds = unavailableSongIds,
                    )?.let { queueIndex ->
                        player.playSongs(navController, playableSongs, queueIndex)
                    }
                },
                onMoreSong = { song -> actionSong = song },
                onOpenMv = { id -> navController.navigate(Routes.mv(id)) },
                onOpenArtist = { artist ->
                    if (artist.id > 0L) navController.navigate(Routes.artist(artist.id))
                },
                onOpenPlaylist = { id -> navController.navigate(Routes.playlist(id)) },
                onOpenUser = { id -> navController.navigate(Routes.user(id)) },
            )
            else -> Box(Modifier.fillMaxSize())
        }
    }
}

/** 顶部搜索条：返回 + 圆角输入框 + 清空。 */
@Composable
private fun SearchTopBar(
    keyword: String,
    onKeyword: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeyword,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            placeholder = {
                Text("搜索歌曲、歌单、歌手、用户或歌词", style = MaterialTheme.typography.bodyMedium)
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (keyword.isNotEmpty()) {
                    IconButton(onClick = { onKeyword("") }) {
                        Icon(Icons.Default.Close, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/** 未输入关键词时的发现面板：搜索历史 + 热门搜索。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryPanel(
    hots: List<String>,
    history: List<String>,
    onPick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (history.isNotEmpty()) {
            item(key = "history") {
                NmlCard {
                    SectionHeader(title = "搜索历史")
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        history.forEach { kw ->
                            RemovablePill(text = kw, onClick = { onPick(kw) }, onRemove = { onRemoveHistory(kw) })
                        }
                    }
                }
            }
        }
        if (hots.isNotEmpty()) {
            item(key = "hot") {
                NmlCard {
                    SectionHeader(title = "热门搜索")
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        hots.forEach { kw ->
                            NmlTactileSurface(
                                shape = RoundedCornerShape(18.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                onClick = { onPick(kw) },
                            ) {
                                Text(
                                    kw,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 历史词 pill：点击填入，右侧小叉可删除。 */
@Composable
private fun RemovablePill(text: String, onClick: () -> Unit, onRemove: () -> Unit) {
    NmlTactileSurface(
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "删除",
                    tint = NmlTheme.colors.onSurfaceMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** 结果列表：按单曲 / 歌手 / 歌单 / 用户分组，每组一个圆角卡片。 */
@Composable
private fun ResultList(
    bundle: SearchRepository.SearchBundle,
    expanded: Set<SearchSection>,
    isOnline: Boolean,
    unavailableSongIds: Set<Long>?,
    onToggleSection: (SearchSection) -> Unit,
    loadingMore: Set<SearchSection>,
    onLoadMore: (SearchSection) -> Unit,
    onPlaySong: (Int) -> Unit,
    onMoreSong: (Song) -> Unit,
    onOpenMv: (Long) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenUser: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        searchSectionOrder().forEach { section ->
            val total = sectionTotal(bundle, section)
            if (total > 0) {
                val isExpanded = section in expanded
                val visible = sectionVisibleCount(total, isExpanded)
                val hasSeeAll = sectionHasSeeAll(total)
                item(key = "sec-" + section.name) {
                    NmlCard {
                        Column(Modifier.fillMaxWidth()) {
                            SectionHeader(
                                title = section.title,
                                actionText = if (hasSeeAll) sectionToggleLabel(section, total, isExpanded) else null,
                                onAction = if (hasSeeAll) ({ onToggleSection(section) }) else null,
                            )
                            when (section) {
                                SearchSection.SONG -> bundle.songs.take(visible).forEachIndexed { index, song ->
                                    val enabled = offlineQueueStartIndex(
                                        songs = bundle.songs,
                                        sourceIndex = index,
                                        isOnline = isOnline,
                                        unavailableIds = unavailableSongIds,
                                    ) != null
                                    SongListItem(
                                        song = song,
                                        index = index,
                                        enabled = enabled,
                                        disabledReason = offlineUnavailableLabel(
                                            isOnline,
                                            unavailableSongIds,
                                            song.id,
                                        ),
                                        onMv = song.mv.takeIf { it > 0L }?.let { mvId ->
                                            { onOpenMv(mvId) }
                                        },
                                        onMore = { onMoreSong(song) },
                                        onClick = { onPlaySong(index) },
                                    )
                                }
                                SearchSection.ARTIST -> bundle.artists.take(visible).forEach { artist ->
                                    ArtistRow(artist = artist, onClick = { onOpenArtist(artist) })
                                }
                                SearchSection.PLAYLIST -> bundle.playlists.take(visible).forEach { playlist ->
                                    PlaylistRow(playlist = playlist, onClick = { onOpenPlaylist(playlist.id) })
                                }
                                SearchSection.USER -> bundle.users.take(visible).forEach { user ->
                                    UserRow(user = user, onClick = { onOpenUser(user.userId) })
                                }
                            }
                            if (isExpanded && sectionHasMore(bundle, section)) {
                                TextButton(
                                    onClick = { onLoadMore(section) },
                                    enabled = section !in loadingMore,
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                ) {
                                    Text(if (section in loadingMore) "正在加载…" else "加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sectionTotal(bundle: SearchRepository.SearchBundle, section: SearchSection): Int = when (section) {
    SearchSection.SONG -> bundle.songs.size
    SearchSection.ARTIST -> bundle.artists.size
    SearchSection.PLAYLIST -> bundle.playlists.size
    SearchSection.USER -> bundle.users.size
}

/** 通用 48dp 头像 / 封面缩略图。 */
@Composable
private fun RowAvatar(url: String, shape: Shape) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowAvatar(url = artist.picUrl, shape = CircleShape)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(artist.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("歌手", style = MaterialTheme.typography.bodySmall, color = NmlTheme.colors.onSurfaceMuted)
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowAvatar(url = playlist.coverThumb, shape = RoundedCornerShape(11.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(playlist.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "歌单 · " + playlist.trackCount + " 首",
                style = MaterialTheme.typography.bodySmall,
                color = NmlTheme.colors.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun UserRow(user: Profile, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowAvatar(url = user.avatarUrl, shape = CircleShape)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(user.nickname, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                user.signature.ifBlank { "用户" },
                style = MaterialTheme.typography.bodySmall,
                color = NmlTheme.colors.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
