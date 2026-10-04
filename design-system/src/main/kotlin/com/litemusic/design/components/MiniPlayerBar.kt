package com.litemusic.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.litemusic.shared.player.PlayerUiState

data class MiniPlayerMember(val name: String, val avatarUrl: String?)

/** 底部迷你播放栏（正在播放） */
@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onQueue: () -> Unit,
    togetherMembers: List<MiniPlayerMember> = emptyList(),
    positionMs: () -> Long = { state.positionMs },
) {
    val current = state.current ?: return
    val duration = state.durationMs.takeIf { it > 0L } ?: current.durationMs
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .shadow(2.dp, RoundedCornerShape(26.dp), clip = false)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp))
            .nmlPressable(onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (togetherMembers.isNotEmpty()) {
            Box(Modifier.size(width = if (togetherMembers.size > 1) 64.dp else 44.dp, height = 44.dp)) {
                togetherMembers.take(2).forEachIndexed { index, member ->
                    Box(
                        Modifier.offset(x = (index * 20).dp).size(44.dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(member.name.take(1).ifBlank { "云" }, color = MaterialTheme.colorScheme.onSurface)
                        AsyncImage(member.avatarUrl, member.name, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(CircleShape))
                    }
                }
            }
        } else {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                AsyncImage(current.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp))
            }
        }
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f).padding(horizontal = 11.dp),
        ) {
            Text(
                if (togetherMembers.isNotEmpty()) "在一起听：${current.title}" else current.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                current.artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NmlIconButton(onClick = onToggle) {
            CircularProgressIndicator(
                progress = {
                    if (duration > 0L) {
                        (positionMs().toFloat() / duration).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier.size(40.dp),
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                strokeWidth = 2.dp,
            )
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
        NmlIconButton(onClick = onQueue) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "接下来播放",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
