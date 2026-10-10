package com.litemusic.app.feature.playlist

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.text.Collator
import java.util.Locale

internal enum class PlaylistTrackOrder { ORIGINAL, TITLE, ARTIST }

/** Filtering and ordering stay local until the service exposes a verified reorder endpoint. */
internal fun playlistVisibleSongs(
    tracks: List<Song>,
    query: String,
    order: PlaylistTrackOrder,
    offlineOnly: Boolean = false,
    cachedSongIds: Set<Long> = emptySet(),
): List<Song> {
    val filtered = tracks.filter { song ->
        (!offlineOnly || song.id in cachedSongIds) &&
            (query.isBlank() || song.name.contains(query, ignoreCase = true) ||
            song.artistNames.contains(query, ignoreCase = true)
            )
    }
    return when (order) {
        PlaylistTrackOrder.ORIGINAL -> filtered
        // Chinese library sorting follows the device's Chinese collation rather than Unicode
        // code points (for example, 甲 before 乙), while keeping the server order otherwise.
        PlaylistTrackOrder.TITLE -> filtered.sortedWith(chineseSongComparator { it.name })
        PlaylistTrackOrder.ARTIST -> filtered.sortedWith(chineseSongComparator { it.artistNames })
    }
}

private fun chineseSongComparator(value: (Song) -> String): Comparator<Song> {
    val collator = Collator.getInstance(Locale.CHINA)
    return Comparator { left, right -> collator.compare(value(left), value(right)) }
}

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
    var editMetadata by remember { mutableStateOf(false) }
    var trackQuery by rememberSaveable(playlistId) { mutableStateOf("") }
    var trackOrder by rememberSaveable(playlistId) { mutableStateOf(PlaylistTrackOrder.ORIGINAL) }
    var offlineOnly by rememberSaveable(playlistId) { mutableStateOf(false) }
    var selectingTracks by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf(setOf<Long>()) }
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
            val cachedSongIds by key(pl.tracks, quality, cacheRevision, offlineOnly) {
                produceState<Set<Long>>(
                    initialValue = emptySet(),
                    pl.tracks,
                    quality,
                    cacheRevision,
                ) {
                    value = if (!offlineOnly) emptySet() else withContext(Dispatchers.IO) {
                        pl.tracks.filter { song ->
                            OfflinePlaybackAvailability.canPlay(context, queueBuilder.toQueueItem(song, quality))
                        }.mapTo(mutableSetOf()) { it.id }
                    }
                }
            }
            val visibleTracks = remember(pl.tracks, trackQuery, trackOrder, offlineOnly, cachedSongIds) {
                playlistVisibleSongs(pl.tracks, trackQuery, trackOrder, offlineOnly, cachedSongIds)
            }
            val visiblePlayableSongs = remember(visibleTracks, isOnline, offlineUnavailableIds) {
                offlinePlayableQueue(visibleTracks, isOnline, offlineUnavailableIds)
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
                                    text = { Text("编辑名称和简介") },
                                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
                                    onClick = { menuOpen = false; editMetadata = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (selectingTracks) "退出歌曲管理" else "批量删除歌曲") },
                                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
                                    onClick = {
                                        menuOpen = false
                                        selectingTracks = !selectingTracks
                                        selectedTrackIds = emptySet()
                                    },
                                )
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
                OutlinedTextField(
                    value = trackQuery,
                    onValueChange = { trackQuery = it },
                    label = { Text("搜索歌单内歌曲") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaylistTrackOrder.entries.forEach { order ->
                        TextButton(onClick = { trackOrder = order }) {
                            Text(if (trackOrder == order) "✓ ${order.label}" else order.label)
                        }
                    }
                    TextButton(onClick = { offlineOnly = !offlineOnly }) {
                        Text(if (offlineOnly) "✓ 仅离线可播" else "仅离线可播")
                    }
                }
                if (selectingTracks) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selectedTrackIds.size} 首", modifier = Modifier.weight(1f))
                    TextButton(enabled = !state.mutating && selectedTrackIds.isNotEmpty(), onClick = {
                        viewModel.removeTracks(selectedTrackIds.toList()) {
                            selectedTrackIds = emptySet()
                            selectingTracks = false
                        }
                    }) { Text(if (state.mutating) "正在删除…" else "删除所选") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NmlButton(onClick = {
                        player.playSongs(navController, visiblePlayableSongs, 0)
                    }, enabled = visiblePlayableSongs.isNotEmpty(), modifier = Modifier.weight(1f)) {
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
                    if (visibleTracks.isEmpty()) item {
                        Text(
                            "这个歌单暂无可播放歌曲",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    itemsIndexed(visibleTracks, key = { _, song -> song.id }) { index, song ->
                        val queueIndex = offlineQueueStartIndex(
                            songs = visibleTracks,
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
                                queueIndex?.let { player.playSongs(navController, visiblePlayableSongs, it) }
                            },
                            trailing = {
                                if (selectingTracks) {
                                    Checkbox(
                                        checked = song.id in selectedTrackIds,
                                        onCheckedChange = { checked ->
                                            selectedTrackIds = if (checked) selectedTrackIds + song.id else selectedTrackIds - song.id
                                        },
                                        enabled = !state.mutating,
                                    )
                                } else {
                                    IconButton(onClick = { viewModel.toggleLike(song.id) }) {
                                        Icon(
                                            if (song.id in state.likedIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            "红心",
                                            tint = if (song.id in state.likedIds) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
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
            if (editMetadata) {
                PlaylistMetadataDialog(
                    playlist = pl,
                    busy = state.mutating,
                    error = state.mutationError,
                    onDismiss = { if (!state.mutating) editMetadata = false },
                    onSave = { name, description ->
                        viewModel.updateMetadata(name, description) { editMetadata = false }
                    },
                )
            }
        }
    }
}

private val PlaylistTrackOrder.label: String get() = when (this) {
    PlaylistTrackOrder.ORIGINAL -> "原顺序"
    PlaylistTrackOrder.TITLE -> "歌名"
    PlaylistTrackOrder.ARTIST -> "歌手"
}

@Composable
private fun PlaylistMetadataDialog(
    playlist: com.litemusic.shared.model.Playlist,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    var description by remember(playlist.id) { mutableStateOf(playlist.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑歌单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, enabled = !busy)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("简介") }, enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { onSave(name.trim(), description.trim()) }) { Text(if (busy) "正在保存…" else "保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
