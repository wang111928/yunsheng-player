package com.litemusic.app.feature.playlist

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.litemusic.app.feature.comment.CommentSheetController
import com.litemusic.app.feature.player.SongActionSheet
import com.litemusic.app.feature.search.ArtistInfoDialog
import com.litemusic.app.ui.Routes
import com.litemusic.design.components.ErrorView
import com.litemusic.design.components.LoadingView
import com.litemusic.design.components.NmSnackbarHost
import com.litemusic.design.components.SongListItem
import com.litemusic.design.components.formatCount
import com.litemusic.design.components.NmlButton
import com.litemusic.design.components.SectionHeader
import com.litemusic.player.PlaybackController
import com.litemusic.player.OfflinePlaybackAvailability
import com.litemusic.app.util.NetworkStatusMonitor
import com.litemusic.app.feature.player.isOfflineUnavailable
import com.litemusic.app.feature.player.offlineUnavailableLabel
import com.litemusic.app.feature.player.offlinePlayableQueue
import com.litemusic.app.feature.player.offlineQueueStartIndex
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.shared.domain.QueueBuilder
import com.litemusic.shared.model.Song
import com.litemusic.shared.model.Artist
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

@Composable
fun PlaylistScreen(
    navController: NavController,
    playlistId: Long,
    viewModel: PlaylistViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val player = rememberPlaylistPlayer()
    val controller: PlaybackController = koinInject()
    val scope = rememberCoroutineScope()
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var selectedArtist by remember { mutableStateOf<Artist?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val settings: SettingsStore = koinInject()
    val network: NetworkStatusMonitor = koinInject()
    val quality by settings.quality.collectAsState(initial = com.litemusic.shared.util.Quality.EXHIGH)
    val isOnline by network.isOnline.collectAsState()
    val cacheRevision by OfflinePlaybackAvailability.cacheRevision.collectAsState()
    val queueBuilder = remember { QueueBuilder() }

    selectedArtist?.let { artist ->
        ArtistInfoDialog(artist, onDismiss = { selectedArtist = null }, onOpenDetail = {
            selectedArtist = null
            navController.navigate(Routes.artist(artist.id))
        })
    }

    actionSong?.let { song ->
        SongActionSheet(
            song = song,
            onDismiss = { actionSong = null },
            onPlayNext = {
                player.enqueueNext(song) { message -> scope.launch { snackbar.showSnackbar(message) } }
            },
            onLike = { viewModel.toggleLike(song.id) },
            onComment = { CommentSheetController.open(song.id) },
            onArtist = song.ar.firstOrNull()?.takeIf { it.id > 0 }?.let { artist ->
                { selectedArtist = artist }
            },
            onUnavailable = { action -> scope.launch { snackbar.showSnackbar("${action.label} 暂无可用接口") } },
        )
    }

    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it); viewModel.toastShown() }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        network.refresh()
        if (viewModel.state.value.playlist?.id != playlistId) {
            viewModel.load(playlistId)
        }
    }

    when {
        state.loading && state.playlist == null -> LoadingView()
        state.error != null && state.playlist == null ->
            ErrorView(state.error!!, onRetry = { viewModel.load(playlistId) })
        state.playlist != null -> {
            val pl = state.playlist!!
            // Cache span lookups may hit disk.  Recompute off the frame thread and retain a
            // pending value while a changed cache/network state is being scanned.
            val offlineUnavailableIds by key(pl.tracks, quality, isOnline, cacheRevision) {
                produceState<Set<Long>?>(
                    initialValue = if (isOnline) emptySet() else null,
                    pl.tracks,
                    quality,
                    isOnline,
                    cacheRevision,
                ) {
                    value = withContext(Dispatchers.IO) {
                        if (isOnline) emptySet() else pl.tracks
                            .filter { song ->
                                isOfflineUnavailable(
                                    isOnline,
                                    OfflinePlaybackAvailability.canPlay(context, queueBuilder.toQueueItem(song, quality)),
                                )
                            }
                            .mapTo(mutableSetOf()) { it.id }
                    }
                }
            }
            val playableSongs = remember(pl.tracks, isOnline, offlineUnavailableIds) {
                offlinePlayableQueue(pl.tracks, isOnline, offlineUnavailableIds)
            }
            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                    Text(pl.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "更多") }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 4.dp,
                            shadowElevation = 8.dp,
                        ) {
                            DropdownMenuItem(
                                text = { Text("分享歌单") },
                                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
                                onClick = {
                                    menuOpen = false
                                    val link = playlistPublicLink(pl.id)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "分享歌单《${pl.name}》\n$link")
                                    }
                                    context.startActivity(Intent.createChooser(intent, "分享歌单"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制歌单链接") },
                                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
                                onClick = {
                                    menuOpen = false
                                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("网易云歌单链接", playlistPublicLink(pl.id)))
                                    scope.launch { snackbar.showSnackbar("歌单链接已复制") }
                                },
                            )
                            if (state.isMine) {
                                DropdownMenuItem(
                                    text = { Text("删除歌单") },
                                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (pl.subscribed) "取消收藏" else "收藏歌单") },
                                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
                                onClick = {
                                    menuOpen = false
                                    viewModel.toggleSubscribe()
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = pl.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(128.dp).clip(RoundedCornerShape(20.dp)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pl.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        pl.creator?.nickname?.takeIf { it.isNotBlank() }?.let { creator ->
                            Text(creator, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (pl.description.isNotBlank()) {
                            Text(pl.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(
                    pl.trackCount.toString() + " 首 · " + formatCount(pl.playCount) + " 次播放",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NmlButton(onClick = {
                        player.playSongs(navController, playableSongs, 0)
                    }, enabled = playableSongs.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, null)
                            Text("全部播放", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    IconButton(onClick = { viewModel.toggleSubscribe() }, containerColor = MaterialTheme.colorScheme.surface) {
                        Icon(
                            if (pl.subscribed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (pl.subscribed) "取消收藏" else "收藏",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(MaterialTheme.colorScheme.surface), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "songs-heading") { SectionHeader("歌曲列表") }
                    if (pl.tracks.isEmpty()) item {
                        Text(
                            "这个歌单暂无可播放歌曲",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    itemsIndexed(pl.tracks, key = { _, song -> song.id }) { index, song ->
                        val queueIndex = offlineQueueStartIndex(
                            songs = pl.tracks,
                            sourceIndex = index,
                            isOnline = isOnline,
                            unavailableIds = offlineUnavailableIds,
                        )
                        SongListItem(
                            song = song,
                            index = index,
                            enabled = queueIndex != null,
                            disabledReason = offlineUnavailableLabel(isOnline, offlineUnavailableIds, song.id),
                            onMv = song.mv.takeIf { it > 0L }?.let { mvId ->
                                { navController.navigate(Routes.mv(mvId)) }
                            },
                            onClick = {
                                queueIndex?.let { player.playSongs(navController, playableSongs, it) }
                            },
                            trailing = {
                                IconButton(onClick = { viewModel.toggleLike(song.id) }) {
                                    Icon(
                                        if (song.id in state.likedIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        "红心",
                                        tint = if (song.id in state.likedIds) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                            onMore = { actionSong = song },
                        )
                    }
                }
            }
                NmSnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 18.dp),
                )
            }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("删除歌单") },
                    text = { Text("确定删除歌单「" + pl.name + "」？该操作不可恢复。") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            viewModel.deleteCurrent { navController.popBackStack() }
                        }) { Text("删除") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                    },
                )
            }
        }
    }
}
