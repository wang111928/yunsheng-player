package com.litemusic.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import com.litemusic.app.feature.playlist.rememberPlaylistPlayer
import com.litemusic.app.feature.player.offlinePlayableQueue
import com.litemusic.app.feature.player.offlineQueueStartIndex
import com.litemusic.app.feature.player.offlineUnavailableLabel
import com.litemusic.app.feature.player.rememberOfflineUnavailableIds
import com.litemusic.app.feature.player.SongActionSheet
import com.litemusic.app.util.NetworkStatusMonitor
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.design.components.ErrorView
import com.litemusic.design.components.SongListItem
import com.litemusic.player.OfflinePlaybackAvailability
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.domain.QueueBuilder
import com.litemusic.shared.model.ArtistDetailResponse
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.Quality
import org.koin.compose.koinInject

/** A real artist endpoint replaces the previous dialog that searched the name again. */
@Composable
fun ArtistDetailScreen(navController: NavController, artistId: Long) {
    val api: NMApi = koinInject()
    val player = rememberPlaylistPlayer()
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<ArtistDetailResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf(0) }

    LaunchedEffect(artistId, request) {
        if (artistId <= 0L) {
            loading = false
            error = "歌手信息无效"
        } else {
            loading = true
            error = null
            when (val response = api.artistDetail(artistId)) {
                is AppResult.Success -> {
                    if (response.data.code == 200 && response.data.artist != null) {
                        result = response.data
                    } else {
                        error = "歌手信息读取失败(${response.data.code})"
                    }
                }
                is AppResult.Failure -> error = response.message
            }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = navController::popBackStack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("歌手主页", style = MaterialTheme.typography.titleLarge)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> ErrorView(error ?: "歌手信息读取失败", onRetry = { request++ })
            result != null -> ArtistContent(result!!, onPlay = { songs, index ->
                player.playSongs(navController, songs, index)
            })
        }
    }
}

@Composable
private fun ArtistContent(detail: ArtistDetailResponse, onPlay: (List<com.litemusic.shared.model.Song>, Int) -> Unit) {
    val artist = detail.artist ?: return
    val context = LocalContext.current
    val settings: SettingsStore = koinInject()
    val network: NetworkStatusMonitor = koinInject()
    val quality by settings.quality.collectAsState(initial = Quality.EXHIGH)
    val isOnline by network.isOnline.collectAsState()
    val cacheRevision by OfflinePlaybackAvailability.cacheRevision.collectAsState()
    val queueBuilder = remember { QueueBuilder() }
    var actionSong by remember { mutableStateOf<com.litemusic.shared.model.Song?>(null) }
    actionSong?.let { song ->
        SongActionSheet(song = song, onDismiss = { actionSong = null })
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { network.refresh() }
    val unavailableSongIds = rememberOfflineUnavailableIds(
        songs = detail.hotSongs,
        isOnline = isOnline,
        quality = quality,
        cacheRevision = cacheRevision,
        context = context,
        queueBuilder = queueBuilder,
    )
    val playableSongs = remember(detail.hotSongs, isOnline, unavailableSongIds) {
        offlinePlayableQueue(detail.hotSongs, isOnline, unavailableSongIds)
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "artist-header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = artist.picUrl,
                    contentDescription = artist.name,
                    modifier = Modifier.size(96.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(artist.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    artist.alias.takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (detail.hotSongs.isNotEmpty()) {
            item(key = "hot-title") {
                Text("热门歌曲", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            items(detail.hotSongs.size, key = { detail.hotSongs[it].id }) { index ->
                val song = detail.hotSongs[index]
                val queueIndex = offlineQueueStartIndex(
                    songs = detail.hotSongs,
                    sourceIndex = index,
                    isOnline = isOnline,
                    unavailableIds = unavailableSongIds,
                )
                SongListItem(
                    song = song,
                    index = index,
                    enabled = queueIndex != null,
                    disabledReason = offlineUnavailableLabel(isOnline, unavailableSongIds, song.id),
                    onClick = { queueIndex?.let { onPlay(playableSongs, it) } },
                    onMore = { actionSong = song },
                )
            }
        } else {
            item(key = "empty-songs") {
                Text("暂未返回可播放歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp))
            }
        }
    }
}
