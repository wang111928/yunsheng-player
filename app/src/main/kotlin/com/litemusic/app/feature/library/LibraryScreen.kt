package com.litemusic.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartDisplay
import com.litemusic.design.components.NmlButton as FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.litemusic.app.BuildConfig
import com.litemusic.app.feature.playlist.rememberPlaylistPlayer
import com.litemusic.app.ui.Routes
import com.litemusic.design.components.LoadingView
import com.litemusic.design.components.PlaylistCard
import com.litemusic.design.components.nmlPressable
import com.litemusic.design.components.NmlButton
import com.litemusic.app.ui.NmlSegmentedTabs
import org.koin.androidx.compose.koinViewModel

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    val state by viewModel.state.collectAsState()
    val session = state.session
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var libraryTab by remember { mutableStateOf("音乐") }
    var playlistTab by remember { mutableStateOf("创建") }
    val currentUid = session?.userId ?: 0L
    val visiblePlaylists = when (playlistTab) {
        "创建" -> if (currentUid > 0L) {
            state.myPlaylists.filter { it.userId == 0L || it.userId == currentUid }
        } else state.myPlaylists
        "收藏" -> if (currentUid > 0L) {
            state.myPlaylists.filter { it.userId > 0L && it.userId != currentUid }
        } else emptyList()
        else -> state.myPlaylists
    }
    val createdCount = if (currentUid > 0L) state.myPlaylists.count { it.userId == 0L || it.userId == currentUid } else state.myPlaylists.size
    val subscribedCount = if (currentUid > 0L) state.myPlaylists.count { it.userId > 0L && it.userId != currentUid } else 0

    if (showCreateDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建歌单") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    placeholder = { Text("歌单名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.createPlaylist(newName) { _ -> showCreateDialog = false; newName = "" }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("取消") } },
        )
    }

    if (state.loading && session == null) {
        LoadingView()
        return
    }

    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).nmlPressable(onClick = {
                session?.userId?.takeIf { it > 0L }?.let { navController.navigate(Routes.user(it)) }
            })) {
                AsyncImage(
                    model = session?.avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(CircleShape),
                )
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(session?.nickname ?: "未登录", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (session?.loggedIn == true) {
                        Text(state.membership, color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    if (state.signature.isNotBlank()) {
                        Text(
                            state.signature,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                NmlButton(onClick = { navController.navigate(Routes.SIGNIN) }) {
                    Text("签到", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCell("关注", state.follows, Modifier.weight(1f)) {
                    session?.userId?.takeIf { it > 0L }?.let { navController.navigate(Routes.follows(it)) }
                }
                StatCell("粉丝", state.followeds, Modifier.weight(1f)) {
                    session?.userId?.takeIf { it > 0L }?.let { navController.navigate(Routes.follows(it, followers = true)) }
                }
                StatCell("等级", state.level, Modifier.weight(1f), null)
                StatCell("红心", state.likedCount, Modifier.weight(1f)) {
                    navController.navigate(Routes.LIKED)
                }
            }
        }

        item {
            LibraryModeTabs(
                selected = libraryTab,
                labels = listOf("音乐", "播客", "笔记"),
                onSelect = { libraryTab = it },
            )
        }

        if (libraryTab != "音乐") {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        "$libraryTab 内容将在官方数据源可用后显示",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (libraryTab == "音乐") {
            item {
                LibraryModeTabs(
                    selected = playlistTab,
                    labels = listOf("近期", "创建", "收藏"),
                    onSelect = { playlistTab = it },
                    compact = true,
                )
            }
        }

        if (libraryTab == "音乐") item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EntryCard("本地音乐", Icons.Default.SmartDisplay, Modifier.weight(1f)) {
                    navController.navigate(Routes.LOCAL)
                }
                if (BuildConfig.FEATURE_MSG) {
                    EntryCard("私信", Icons.Default.Chat, Modifier.weight(1f)) {
                        navController.navigate(Routes.MSGS)
                    }
                }
                if (BuildConfig.FEATURE_TOGETHER) {
                    EntryCard("一起听", Icons.Default.MusicNote, Modifier.weight(1f)) {
                        navController.navigate(Routes.TOGETHER)
                    }
                }
            }
        }

        if (libraryTab == "音乐") {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("我的歌单 · 创建 $createdCount · 收藏 $subscribedCount", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (state.error != null) TextButton(onClick = viewModel::refresh) { Text("重试") }
                    TextButton(onClick = { navController.navigate(Routes.PLAYLIST_IMPORT) }) { Text("导入") }
                    TextButton(onClick = { showCreateDialog = true }) { Text("新建歌单") }
                }
            }
            if (state.error != null) item {
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp))
            }

            items(visiblePlaylists, key = { it.id }) { pl ->
                Row(
                    Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .nmlPressable(onClick = { navController.navigate(Routes.playlist(pl.id)) })
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = pl.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(pl.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pl.trackCount.toString() + " 首", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun EntryCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .nmlPressable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun StatCell(
    label: String,
    value: Int,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = onClick?.let { Modifier.nmlPressable(onClick = it) } ?: Modifier
    Column(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            )
            .then(clickModifier)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryModeTabs(
    selected: String,
    labels: List<String>,
    onSelect: (String) -> Unit,
    compact: Boolean = false,
) {
    if (compact) {
        NmlSegmentedTabs(labels, labels.indexOf(selected), { onSelect(labels[it]) })
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (compact) 0.dp else 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        labels.forEach { label ->
            val active = label == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .nmlPressable(onClick = { onSelect(label) })
                    .padding(vertical = if (compact) 8.dp else 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .width(if (active) 42.dp else 0.dp)
                        .height(3.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}
