package com.litemusic.app.feature.localmusic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.LocalMusicRepository
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.player.PlaybackController
import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.util.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LocalMusicViewModel(
    private val repo: LocalMusicRepository,
    private val settings: SettingsStore,
    private val controller: PlaybackController,
) : ViewModel() {

    enum class BrowseMode { SONGS, ARTISTS, ALBUMS, FOLDERS }

    data class UiState(
        val loading: Boolean = true,
        val songs: List<LocalMusicRepository.LocalSong> = emptyList(),
        val mode: BrowseMode = BrowseMode.SONGS,
        val lyrics: Map<Long, String> = emptyMap(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val songs = repo.query(settings)
            _state.update { it.copy(loading = false, songs = songs) }
        }
    }

    fun setMode(mode: BrowseMode) = _state.update { it.copy(mode = mode) }

    /** 播放本地歌曲（与在线混播） */
    fun play(songs: List<LocalMusicRepository.LocalSong>, startIndex: Int) {
        val items = songs.map { s ->
            QueueItem(
                id = -s.id, // 本地歌曲用负 id 与在线区分
                title = s.title,
                artist = s.artist,
                album = s.album,
                durationMs = s.durationMs,
                localPath = s.path,
                quality = Quality.LOSSLESS,
            )
        }
        controller.setQueue(items, startIndex)
    }

    fun loadLyric(mediaId: Long) {
        viewModelScope.launch {
            val song = _state.value.songs.firstOrNull { it.id == mediaId } ?: return@launch
            val lrc = repo.lyric(song)
            if (!lrc.isNullOrBlank()) {
                _state.update { it.copy(lyrics = it.lyrics + (mediaId to lrc)) }
            }
        }
    }

    fun updateMeta(mediaId: Long, title: String?, artist: String?, album: String?) {
        viewModelScope.launch {
            repo.updateMeta(mediaId, title, artist, album)
            refresh()
        }
    }
}
