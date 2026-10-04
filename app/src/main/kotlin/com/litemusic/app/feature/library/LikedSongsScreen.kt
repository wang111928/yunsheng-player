package com.litemusic.app.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.litemusic.app.data.PlaylistRepository
import com.litemusic.app.feature.playlist.rememberPlaylistPlayer
import com.litemusic.app.ui.Routes
import com.litemusic.design.components.EmptyView
import com.litemusic.design.components.ErrorView
import com.litemusic.design.components.LoadingView
import com.litemusic.design.components.SongListItem
import com.litemusic.design.components.NmlTopBar
import com.litemusic.design.components.NmlButton
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** 我喜欢的音乐（红心）列表 */
class LikedSongsViewModel(
    private val repo: PlaylistRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val songs: List<Song> = emptyList(),
        val likedIds: Set<Long> = emptySet(),
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.likedIds.collect { ids -> _state.update { it.copy(likedIds = ids) } }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val uid = repo.currentUserId()
            when (val r = repo.likedSongs(forceRefresh = true)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        songs = r.data,
                        error = if (r.data.isEmpty() && uid == 0L) "登录已失效，请重新登录" else null,
                    )
                }
                is AppResult.Failure -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun toggleLike(songId: Long) {
        viewModelScope.launch {
            val liked = songId in _state.value.likedIds
            when (val r = repo.like(songId, !liked)) {
                is AppResult.Success ->
                    if (liked) _state.update { s -> s.copy(songs = s.songs.filterNot { it.id == songId }) }
                is AppResult.Failure -> _state.update { it.copy(toast = r.message) }
            }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }
}

@Composable
fun LikedSongsScreen(
    navController: NavController,
    viewModel: LikedSongsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val player = rememberPlaylistPlayer()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it); viewModel.toastShown() }
    }

    Column(Modifier.fillMaxSize()) {
        NmlTopBar("喜欢的音乐", onBack = { navController.popBackStack() })
        if (state.songs.isNotEmpty()) {
            Text("${state.songs.size} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            NmlButton(onClick = { player.playSongs(navController, state.songs, 0) }, modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PlayArrow, null); Text("全部播放") }
            }
        }

        when {
            state.loading -> LoadingView()
            state.error != null -> ErrorView(state.error!!, onRetry = { viewModel.load() })
            state.songs.isEmpty() -> EmptyView("还没有红心歌曲")
            else -> LazyColumn(
                Modifier.weight(1f).padding(horizontal = 12.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                    SongListItem(
                        song = song,
                        index = index,
                        onMv = song.mv.takeIf { it > 0L }?.let { mvId ->
                            { navController.navigate(Routes.mv(mvId)) }
                        },
                        onClick = {
                            player.playSongs(navController, state.songs, index)
                        },
                        trailing = {
                            IconButton(onClick = { viewModel.toggleLike(song.id) }) {
                                Icon(
                                    Icons.Default.Favorite,
                                    "取消红心",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
        SnackbarHost(snackbar)
    }
}
