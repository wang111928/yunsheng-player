package com.litemusic.app.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import com.litemusic.design.components.nmlPressable
import com.litemusic.design.components.unavailableSongBackground
import com.litemusic.design.components.unavailableSongForeground
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.litemusic.shared.player.PlayerUiState
import com.litemusic.shared.player.QueueItem
import com.litemusic.player.OfflinePlaybackAvailability
import com.litemusic.app.util.NetworkStatusMonitor
import org.koin.compose.koinInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PlaybackQueueRow(
    val index: Int,
    val item: QueueItem,
    val isCurrent: Boolean,
    val key: String,
)

internal fun playbackQueueRows(state: PlayerUiState): List<PlaybackQueueRow> {
    val occurrences = mutableMapOf<Long, Int>()
    return state.queue.mapIndexed { index, item ->
        val occurrence = occurrences.getOrDefault(item.id, 0)
        occurrences[item.id] = occurrence + 1
        PlaybackQueueRow(index = index, item = item, isCurrent = index == state.currentIndex,
            key = "${item.id}:$occurrence")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQueueSheet(
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val network: NetworkStatusMonitor = koinInject()
    val isOnline by network.isOnline.collectAsState()
    val cacheRevision by OfflinePlaybackAvailability.cacheRevision.collectAsState()
    val context = LocalContext.current
    val offlineAvailable by key(state.queue, isOnline, cacheRevision) {
        produceState<List<Boolean>>(
            // Re-key the state so a newly offline queue is gray during its cache scan rather
            // than displaying the previous online result until the coroutine finishes.
            initialValue = List(state.queue.size) { isOnline },
            state.queue,
            isOnline,
            cacheRevision,
        ) {
            value = withContext(Dispatchers.IO) {
                state.queue.map { item -> !isOfflineUnavailable(isOnline, OfflinePlaybackAvailability.canPlay(context, item)) }
            }
        }
    }
    LaunchedEffect(Unit) { network.refresh() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 300.dp, max = 580.dp)
                .padding(bottom = 18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("接下来播放", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.queue.size} 首歌曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭队列")
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(playbackQueueRows(state), key = { it.key }) { row ->
                    QueueRow(
                        row = row,
                        isOfflineAvailable = offlineAvailable.getOrNull(row.index) ?: true,
                        canMoveLeft = row.index > 0,
                        canMoveRight = row.index < state.queue.lastIndex,
                        onPlay = { onPlay(row.index) },
                        onRemove = { onRemove(row.index) },
                        onMoveLeft = { onMove(row.index, row.index - 1) },
                        onMoveRight = { onMove(row.index, row.index + 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    row: PlaybackQueueRow,
    isOfflineAvailable: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val unavailableForeground = unavailableSongForeground(MaterialTheme.colorScheme.surface)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (!isOfflineAvailable) unavailableSongBackground(MaterialTheme.colorScheme.surface)
                else if (row.isCurrent) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
            )
            .nmlPressable(onClick = onPlay, enabled = isOfflineAvailable)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = if (!isOfflineAvailable) unavailableForeground
            else if (row.isCurrent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                row.item.title + if (row.item.isLocal) "  · 本地" else "",
                style = if (row.isCurrent) MaterialTheme.typography.bodyLarge
                else MaterialTheme.typography.bodyMedium,
                color = if (!isOfflineAvailable) unavailableForeground
                else if (row.isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isOfflineAvailable) row.item.artist else "离线不可播",
                style = MaterialTheme.typography.bodySmall,
                color = if (isOfflineAvailable) MaterialTheme.colorScheme.onSurfaceVariant else unavailableForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMoveLeft, enabled = canMoveLeft) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上移")
        }
        IconButton(onClick = onMoveRight, enabled = canMoveRight) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下移")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "移除")
        }
    }
}
