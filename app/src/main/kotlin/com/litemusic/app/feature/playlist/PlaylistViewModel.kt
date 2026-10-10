package com.litemusic.app.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.PlaylistRepository
import com.litemusic.app.data.playlistAfterRemovingTracks
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

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
        val mutating: Boolean = false,
        val mutationError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // 红心状态全局共享：别处（播放页）点红心后本页立即同步
        viewModelScope.launch {
            repo.likedIds.collect { ids -> _state.update { it.copy(likedIds = ids) } }
        }
    }

    fun load(id: Long, force: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = repo.detail(id, force = force)) {
                is AppResult.Success -> {
                    val uid = repo.currentUserId()
                    _state.update {
                        it.copy(loading = false, playlist = r.data, isMine = r.data.userId != 0L && r.data.userId == uid)
                    }
                    repo.loadLikedIds()
                }
                // Keep a rendered playlist visible on a failed refresh.  This matters after a
                // cold offline restart: detail() already falls back to disk cache, but a stale
                // result must never blank a page the user is currently reading.
                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, error = if (it.playlist == null) r.message else null, toast = if (it.playlist == null) it.toast else r.message)
                }
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
        if (!_state.value.isMine || _state.value.mutating) return
        _state.update { it.copy(mutating = true, mutationError = null) }
        viewModelScope.launch {
            try {
                if (!ownsCurrentPlaylist(pl)) { _state.update { it.copy(toast = "账号已变化，请重新打开歌单") }; return@launch }
                when (val r = repo.delete(listOf(pl.id))) {
                    is AppResult.Success -> {
                        onDone()
                    }
                    is AppResult.Failure -> _state.update { it.copy(toast = r.message) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(toast = "删除结果未能确认，请刷新歌单后重试") } }
            finally { _state.update { it.copy(mutating = false) } }
        }
    }

    /** 批量删除歌曲 */
    fun removeTracks(ids: List<Long>, onDone: () -> Unit = {}) {
        val pl = _state.value.playlist ?: return
        if (ids.isEmpty() || !_state.value.isMine || _state.value.mutating) return
        _state.update { it.copy(mutating = true, mutationError = null) }
        viewModelScope.launch {
            try {
                if (!ownsCurrentPlaylist(pl)) { _state.update { it.copy(toast = "账号已变化，请重新打开歌单") }; return@launch }
                val r = repo.removeTracks(pl.id, ids)
                if (r is AppResult.Success) {
                    _state.update { state -> state.copy(playlist = state.playlist?.let { playlistAfterRemovingTracks(it, ids) }, toast = "已删除 ${ids.distinct().size} 首歌曲") }
                    onDone()
                    load(pl.id)
                } else {
                    _state.update { it.copy(toast = (r as? AppResult.Failure)?.message ?: "删除失败") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(toast = "删除结果未能确认，已保留所选歌曲，请刷新后重试") } }
            finally { _state.update { it.copy(mutating = false) } }
        }
    }

    fun updateMetadata(name: String, description: String, onDone: () -> Unit = {}) {
        val pl = _state.value.playlist ?: return
        if (!_state.value.isMine || _state.value.mutating || name.isBlank()) return
        _state.update { it.copy(mutating = true, mutationError = null) }
        viewModelScope.launch {
            try {
                if (!ownsCurrentPlaylist(pl)) { _state.update { it.copy(toast = "账号已变化，请重新打开歌单") }; return@launch }
                when (val result = repo.update(pl.id, name, description)) {
                    is AppResult.Success -> {
                        _state.update { it.copy(playlist = it.playlist?.copy(name = name, description = description), toast = "歌单信息已保存") }
                        onDone()
                        load(pl.id)
                    }
                    is AppResult.Failure -> _state.update { it.copy(toast = result.message, mutationError = result.message) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(toast = "保存结果未能确认，请稍后重试", mutationError = "保存结果未能确认，修改内容已保留") } }
            finally { _state.update { it.copy(mutating = false) } }
        }
    }

    private suspend fun ownsCurrentPlaylist(playlist: Playlist): Boolean {
        val uid = repo.currentUserId()
        return uid > 0L && playlist.userId == uid && _state.value.playlist?.id == playlist.id
    }

    fun toastShown() = _state.update { it.copy(toast = null) }
}
