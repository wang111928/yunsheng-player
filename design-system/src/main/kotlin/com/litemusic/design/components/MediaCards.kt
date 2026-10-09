package com.litemusic.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.litemusic.design.theme.NmlTheme
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Song

/**
 * 列表通用组件（设计规范 P1）：
 * NmlCoverCard(112/134, 播放量角标) / NmlTrackItem(高 58, 序号+标题+副标题) /
 * NmlSearchBar / KgEntry(金刚区 48dp 圆角 16)。
 */

/** 歌曲行（列表通用）。index < 0 时显示 40dp 封面缩略图，否则显示序号。 */
@Composable
private fun Modifier.nmlPress(onClick: () -> Unit): Modifier {
    return nmlPressable(onClick)
}

/** Keep unavailable tracks visibly gray even when a skin has tinted surface tokens. */
fun unavailableSongForeground(surface: Color): Color =
    if (surface.luminance() < 0.5f) Color(0xFFADB0B7) else Color(0xFF666A70)

fun unavailableSongBackground(surface: Color): Color =
    if (surface.luminance() < 0.5f) Color(0xFF2B2D32) else Color(0xFFECEDEF)

@Composable
fun SongListItem(
    song: Song,
    index: Int,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onMv: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    enabled: Boolean = true,
    /** Shown for an intentionally unavailable row, for example an uncached offline song. */
    disabledReason: String? = null,
    onClick: () -> Unit,
) {
    val disabledContentColor = unavailableSongForeground(MaterialTheme.colorScheme.surface)
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else disabledContentColor
    val subtitleColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else disabledContentColor
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(70.dp)
            // Keep a solid neutral surface behind disabled copy on vivid art skins.
            // Fading the entire row made the gray text hard to see against the painting.
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) Color.Transparent
                else unavailableSongBackground(MaterialTheme.colorScheme.surface),
            )
            .nmlPressable(onClick, enabled = enabled)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.coverThumbUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (enabled) null else ColorFilter.colorMatrix(
                ColorMatrix().apply { setToSaturation(0f) },
            ),
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.name,
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artistNames + (song.albumName.takeIf { it.isNotBlank() }?.let { " - " + it } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!enabled && !disabledReason.isNullOrBlank()) {
                Text(
                    disabledReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = disabledContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onMv != null && song.mv > 0) {
            Box(
                Modifier.size(48.dp).nmlPressable(onMv),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "播放MV",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text("MV", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (song.privilege?.fee == 1) {
            Text(
                "VIP",
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.primary else disabledContentColor,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        trailing?.invoke()
        if (onMore != null) {
            NmlIconButton(onClick = onMore) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多歌曲操作")
            }
        }
    }
}

/** 歌单卡片（网格） */
@Composable
fun PlaylistCard(
    playlist: Playlist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.nmlPress(onClick)) {
        Box {
            AsyncImage(
                model = playlist.cover,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (playlist.playCount > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color(0xCC18151A), RoundedCornerShape(bottomStart = 8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(11.dp))
                    Text(
                        formatCount(playlist.playCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
        Text(
            playlist.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (playlist.updateFrequency.isNotBlank()) {
            Text(
                playlist.updateFrequency,
                style = MaterialTheme.typography.labelSmall,
                color = NmlTheme.colors.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 横滑cover卡（112 宽，推荐歌单 / 排行榜共用） */
@Composable
fun CoverCard(
    playlist: Playlist,
    modifier: Modifier = Modifier,
    showSubtitle: Boolean = false,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.width(112.dp).nmlPress(onClick)) {
        Box {
            AsyncImage(
                model = playlist.coverThumb,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (playlist.playCount > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color(0xCC18151A), RoundedCornerShape(bottomStart = 8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(11.dp))
                    Text(
                        formatCount(playlist.playCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
        Text(
            playlist.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (showSubtitle && playlist.updateFrequency.isNotBlank()) {
            Text(
                playlist.updateFrequency,
                style = MaterialTheme.typography.labelSmall,
                color = NmlTheme.colors.onSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

fun formatCount(count: Double): String = when {
    count >= 100_000_000.0 -> String.format("%.1f亿", count / 100_000_000.0)
    count >= 10_000.0 -> String.format("%.1f万", count / 10_000.0)
    else -> count.toLong().toString()
}

/** 分区标题（左标题 + 右侧可选操作） */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    playAll: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playAll != null) {
            NmlIconButton(
                onClick = playAll,
                containerColor = MaterialTheme.colorScheme.primary,
            ) { Icon(Icons.Default.PlayArrow, contentDescription = "播放全部", tint = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        if (actionText != null && onAction != null) {
            Box(
                Modifier.height(48.dp).nmlPressable(onAction).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onQueue != null) {
            NmlIconButton(onClick = onQueue) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "接下来播放")
            }
        }
    }
}

/** 首页顶部搜索入口（高 46 / 圆角 23） */
@Composable
fun SearchEntryBar(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(MaterialTheme.colorScheme.surface)
            .nmlPress(onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = NmlTheme.colors.onSurfaceMuted, modifier = Modifier.size(18.dp))
        Text(
            "搜索歌曲、歌手、歌单",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = NmlTheme.colors.onSurfaceMuted,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 金刚区入口（独立表面小卡片，48dp 图标触摸区域）。 */
@Composable
fun KgEntry(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .width(68.dp)
            .height(74.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .nmlPress(onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** 每日推荐横幅（高 110dp，用当日推荐首曲封面做图）。 */
@Composable
fun DailyBanner(
    modifier: Modifier = Modifier,
    coverUrl: String? = null,
    subtitle: String = "根据你的口味，每天 30 首",
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .nmlPress(onClick),
    ) {
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xE60A0608), Color(0x990A0608), Color(0x4D0A0608)),
                    )
                ),
        )
        Row(
            Modifier.fillMaxWidth().height(110.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text("每日推荐", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xCCFFFFFF),
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 分组容器（surface 卡片 + 1dp 描边，AMOLED 下替代阴影） */
@Composable
fun NmlCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(20.dp),
            )
            .padding(vertical = 6.dp),
    ) { content() }
}

@Composable
fun NmlTactileSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    borderColor: Color = Color.Transparent,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(containerColor)
            .border(0.5.dp, borderColor, shape)
            .nmlPressable(onClick, pressScale = 0.97f),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun NmlSearchEntryBar(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NmlTactileSurface(
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = NmlTheme.colors.primary, modifier = Modifier.size(19.dp))
            Text(
                "搜索歌曲、歌手、歌单或歌词",
                style = MaterialTheme.typography.bodyMedium,
                color = NmlTheme.colors.onSurfaceMuted,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
