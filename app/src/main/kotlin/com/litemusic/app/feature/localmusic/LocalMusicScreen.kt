package com.litemusic.app.feature.localmusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.litemusic.design.components.NmlTopBar
import com.litemusic.design.components.nmlPressable
import com.litemusic.app.ui.NmlSegmentedTabs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.litemusic.app.data.LocalMusicRepository
import com.litemusic.app.feature.localmusic.LocalMusicViewModel.BrowseMode
import com.litemusic.design.components.EmptyView
import com.litemusic.design.components.LoadingView
import org.koin.androidx.compose.koinViewModel

@Composable
fun LocalMusicScreen(
    navController: NavController,
    viewModel: LocalMusicViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.refresh()
    }

    LaunchedEffect(Unit) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.refresh()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            NmlTopBar("本地音乐", onBack = { navController.popBackStack() })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            NmlSegmentedTabs(BrowseMode.entries.map(::modeLabel), state.mode.ordinal,
                { viewModel.setMode(BrowseMode.entries[it]) }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            when {
                state.loading -> LoadingView()
                state.songs.isEmpty() -> EmptyView("未扫描到本地音乐\n（时长 ≥ 设置中最小时长，自动排除铃声）")
                else -> when (state.mode) {
                    BrowseMode.SONGS -> SongList(state.songs, viewModel)
                    BrowseMode.ARTISTS -> GroupList(state.songs.groupBy { it.artist }, viewModel)
                    BrowseMode.ALBUMS -> GroupList(state.songs.groupBy { it.album }, viewModel)
                    BrowseMode.FOLDERS -> GroupList(state.songs.groupBy { it.folder }, viewModel)
                }
            }
        }
    }
}

@Composable
private fun SongList(songs: List<LocalMusicRepository.LocalSong>, viewModel: LocalMusicViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(songs, key = { it.id }) { song ->
            LocalSongRow(song) { viewModel.play(songs, songs.indexOf(song)) }
        }
    }
}

@Composable
private fun GroupList(
    groups: Map<String, List<LocalMusicRepository.LocalSong>>,
    viewModel: LocalMusicViewModel,
) {
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.entries.sortedByDescending { it.value.size }.forEach { (name, songs) ->
            item(key = "heading-$name") {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp)) {
                    Text(name.ifBlank { "未知" }, style = MaterialTheme.typography.titleMedium)
                    Text(songs.size.toString() + " 首", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(if (name in expanded) songs else songs.take(3), key = { "song-$name-${it.id}" }) { song ->
                LocalSongRow(song) { viewModel.play(songs, songs.indexOf(song)) }
            }
            if (songs.size > 3) item(key = "expand-$name") {
                TextButton(onClick = { expanded = if (name in expanded) expanded - name else expanded + name }) {
                    Text(if (name in expanded) "收起" else "查看全部 (${songs.size})")
                }
            }
        }
    }
}

@Composable
private fun LocalSongRow(song: LocalMusicRepository.LocalSong, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
        .nmlPressable(onClick = onPlay).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist + " · " + song.album, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(fmt(song.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun modeLabel(mode: BrowseMode) = when (mode) {
    BrowseMode.SONGS -> "歌曲"
    BrowseMode.ARTISTS -> "歌手"
    BrowseMode.ALBUMS -> "专辑"
    BrowseMode.FOLDERS -> "文件夹"
}

private fun fmt(ms: Long): String {
    val total = ms / 1000
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}
