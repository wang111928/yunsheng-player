package com.litemusic.app.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.litemusic.player.PlaybackController
import com.litemusic.shared.domain.QueueBuilder
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.Quality

enum class SongAction(val label: String) {
    SUPPORT("赞赏好音乐"),
    PLAY_NEXT("下一首播放"),
    LIKE("喜欢"),
    COLLECT("收藏到歌单"),
    LESS_RECOMMENDATION("减少推荐"),
    DOWNLOAD("下载"),
    COMMENT("评论"),
    SHARE("分享"),
    PURCHASE("单曲购买"),
    ARTIST("歌手"),
}

internal fun songActionLabels(): List<String> = SongAction.entries.map { it.label }

/** Adds a song immediately after the current item while retaining that queue's quality. */
internal fun PlaybackController.enqueueNext(song: Song, quality: Quality = state.value.current?.quality ?: Quality.EXHIGH) {
    val currentIndex = state.value.currentIndex
    enqueue(listOf(QueueBuilder().toQueueItem(song, quality)))
    val appendedIndex = state.value.queue.lastIndex
    val targetIndex = if (currentIndex < 0) 0 else (currentIndex + 1).coerceAtMost(appendedIndex)
    if (appendedIndex > targetIndex) move(appendedIndex, targetIndex)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionSheet(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onLike: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    onComment: (() -> Unit)? = null,
    onArtist: (() -> Unit)? = null,
    onUnavailable: (SongAction) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artistNames.ifBlank { "未知歌手" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.MoreVert, contentDescription = "歌曲操作")
                }
            }
            Spacer(Modifier.height(4.dp))
            SongAction.entries.forEach { action ->
                val callback = when (action) {
                    SongAction.PLAY_NEXT -> onPlayNext
                    SongAction.LIKE -> onLike
                    SongAction.COLLECT -> onQueue
                    SongAction.COMMENT -> onComment
                    SongAction.ARTIST -> onArtist
                    else -> null
                }
                SongActionRow(
                    action = action,
                    enabled = callback != null,
                    onClick = {
                        if (callback != null) {
                            callback()
                            onDismiss()
                        } else {
                            onUnavailable(action)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SongActionRow(
    action: SongAction,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            actionIcon(action),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Text(
            action.label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier.padding(start = 18.dp),
        )
    }
}

private fun actionIcon(action: SongAction): ImageVector = when (action) {
    SongAction.SUPPORT -> Icons.Default.Favorite
    SongAction.PLAY_NEXT -> Icons.Default.PlayArrow
    SongAction.LIKE -> Icons.Default.FavoriteBorder
    SongAction.COLLECT -> Icons.Default.PlaylistAdd
    SongAction.LESS_RECOMMENDATION -> Icons.Default.Block
    SongAction.DOWNLOAD -> Icons.Default.Download
    SongAction.COMMENT -> Icons.Default.Comment
    SongAction.SHARE -> Icons.Default.Share
    SongAction.PURCHASE -> Icons.Default.ShoppingCart
    SongAction.ARTIST -> Icons.Default.Person
}
