package com.litemusic.app.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.PlaylistRepository
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlaylistViewModel(
    private val repo: PlaylistRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val playlist: Playlist? = null,
        val likedIds: Set<Long> = emptySet(),
        val isMine: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // 红心状态全局共享：别处（播放页）点红心后本页立即同步
        viewModelScope.launch {
            repo.likedIds.collect { ids -> _state.update { it.copy(likedIds = ids) } }
        }
    }

    fun load(id: Long) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = repo.detail(id, force = true)) {
                is AppResult.Success -> {
                    val uid = repo.currentUserId()
                    _state.update {
                        it.copy(loading = false, playlist = r.data, isMine = r.data.userId != 0L && r.data.userId == uid)
                    }
                    repo.loadLikedIds()
                }
                is AppResult.Failure -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun toggleLike(songId: Long) {
        viewModelScope.launch {
            val liked = songId in _state.value.likedIds
            val r = repo.like(songId, !liked)
            if (r is AppResult.Failure) _state.update { it.copy(toast = r.message) }
        }
    }

    /** 收藏 / 取消收藏当前歌单 */
    fun toggleSubscribe() {
        val pl = _state.value.playlist ?: return
        val target = !pl.subscribed
        viewModelScope.launch {
            when (val r = repo.subscribe(pl.id, target)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        playlist = it.playlist?.copy(subscribed = target),
                        toast = if (target) "已收藏歌单" else "已取消收藏",
                    )
                }
                is AppResult.Failure -> _state.update { it.copy(toast = r.message) }
            }
        }
    }

    /** 删除自己的歌单，成功后回退上一页 */
    fun deleteCurrent(onDone: () -> Unit) {
        val pl = _state.value.playlist ?: return
        viewModelScope.launch {
            when (val r = repo.delete(listOf(pl.id))) {
                is AppResult.Success -> {
                    repo.detailCacheInvalidate(pl.id)
                    onDone()
                }
                is AppResult.Failure -> _state.update { it.copy(toast = r.message) }
            }
        }
    }

    /** 批量删除歌曲 */
    fun removeTracks(ids: List<Long>) {
        val pl = _state.value.playlist ?: return
        viewModelScope.launch {
            val r = repo.removeTracks(pl.id, ids)
            if (r is AppResult.Success) {
                load(pl.id)
            } else {
                _state.update { it.copy(toast = (r as? AppResult.Failure)?.message ?: "删除失败") }
            }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }
}
