package com.litemusic.app.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.litemusic.app.data.LocalLyricRepository
import com.litemusic.app.data.PlaylistRepository
import com.litemusic.app.data.SongRepository
import com.litemusic.lyric.LyricEngine
import com.litemusic.lyric.LyricUiLine
import com.litemusic.player.PlaybackController
import com.litemusic.shared.model.LyricContent
import com.litemusic.shared.model.LyricResponse
import com.litemusic.shared.player.PlayerUiState
import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val controller: PlaybackController,
    private val songRepository: SongRepository,
    private val playlistRepository: PlaylistRepository,
    private val localLyricRepository: LocalLyricRepository,
    private val lyricEngine: LyricEngine,
) : ViewModel() {

    data class LyricUi(
        val lines: List<LyricUiLine> = emptyList(),
        val hasTranslation: Boolean = false,
        val message: String? = null,
    )

    private val _lyric = MutableStateFlow(LyricUi())
    val lyric: StateFlow<LyricUi> = _lyric.asStateFlow()
    private val localLyricImports = LocalLyricImportCoordinator<Uri>()

    private val lyricCoordinator = LyricLoadCoordinator(
        scope = viewModelScope,
        load = { item ->
            if (item.isLocal) {
                localLyricRepository.load(item)?.let(::toLyricUi)
                    ?: LyricUi(message = "未找到歌词，可选择 .lrc 文件")
            } else when (val result = songRepository.lyric(item.id)) {
                is AppResult.Success -> {
                    toLyricUi(result.data)
                }
                is AppResult.Failure -> null
            }
        },
        show = { item, result ->
            val lyric = result ?: LyricUi()
            _lyric.value = lyric.copy(message = lyric.message ?: playerTextFallback(item))
                .withLocalImportMessage(item, localLyricImports)
        },
    )

    val playerState: StateFlow<PlayerUiState> = controller.state
    val liked: StateFlow<Set<Long>> = playlistRepository.likedIds

    init {
        viewModelScope.launch { playlistRepository.loadLikedIds() }
        viewModelScope.launch {
            controller.state.collect { s ->
                lyricCoordinator.updateCurrent(s.current)
                localLyricImports.onCurrentChanged(s.current)
            }
        }
    }

    fun toggle() = controller.toggle()
    fun playIndex(index: Int) = controller.playIndex(index)
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(ms: Long) = controller.seekTo(ms)
    fun setQuality(q: Quality) = controller.setQuality(q)
    fun cycleMode() = controller.cyclePlayMode()
    fun removeAt(index: Int) = controller.removeAt(index)
    fun move(from: Int, to: Int) = controller.move(from, to)

    /** Records the source before showing the system picker, so a later song switch cannot misapply it. */
    fun prepareLocalLyricImport(item: QueueItem): Boolean = localLyricImports.prepare(item)

    fun importPickedLocalLyric(uri: Uri?) {
        val selected = uri ?: run {
            localLyricImports.accept(null)
            return
        }
        val target = localLyricImports.accept(selected) ?: return
        viewModelScope.launch {
            when (localLyricImports.finish(target, localLyricRepository.import(target, selected), playerState.value.current)) {
                LocalLyricImportCoordinator.Completion.RELOAD_CURRENT -> lyricCoordinator.reloadIfCurrent(target)
                LocalLyricImportCoordinator.Completion.SHOW_FAILURE -> {
                    _lyric.value = _lyric.value.withLocalImportMessage(target, localLyricImports)
                }
                LocalLyricImportCoordinator.Completion.NO_CURRENT_UPDATE -> Unit
            }
        }
    }

    /** 红心 / 取消红心（eapi /api/radio/like，成功后全局红心集合立即同步） */
    fun toggleLike(songId: Long) {
        viewModelScope.launch {
            val likedNow = songId in playlistRepository.likedIds.value
            playlistRepository.like(songId, !likedNow)
        }
    }

    private fun toLyricUi(response: LyricResponse): LyricUi {
        val doc = lyricEngine.build(response)
        return LyricUi(
            lines = lyricEngine.toUiLines(doc),
            hasTranslation = doc.translations.isNotEmpty(),
        )
    }

    private fun toLyricUi(text: String): LyricUi = toLyricUi(LyricResponse(lrc = LyricContent(lyric = text)))
}

/** Program prose is informational text; it is never converted into timestamped or fake lyric lines. */
internal fun playerTextFallback(item: QueueItem?): String? =
    item?.description?.trim()?.takeIf { it.isNotEmpty() }?.let { "节目简介\n$it" }
