package com.litemusic.shared.player

import androidx.compose.runtime.Immutable
import com.litemusic.shared.util.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class QueueItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String? = null,
    val durationMs: Long = 0,
    val localPath: String? = null,
    /** Non-lyric text from a radio program. It is displayed as an introduction, never parsed as lyrics. */
    val description: String? = null,
    /** Resource thread used by comments when playback represents a radio program rather than a song. */
    val commentThreadId: String? = null,
    val quality: Quality = Quality.HIGH,
) {
    val isLocal: Boolean get() = localPath != null
}

enum class PlayMode { SEQUENCE, REPEAT_ALL, REPEAT_ONE, SHUFFLE }

enum class PlayPhase { IDLE, LOADING, READY, PLAYING, PAUSED, ERROR }

@Immutable
data class PlayerUiState(
    val phase: PlayPhase = PlayPhase.IDLE,
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val isPreparingNext: Boolean = false,
    /** Increments only for a direct list/program selection, never for buffering callbacks. */
    val selectionGeneration: Long = 0L,
    val error: String? = null,
) {
    val current: QueueItem? get() = queue.getOrNull(currentIndex)
}

@Serializable
data class QueueSnapshot(
    val items: List<QueueItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val playMode: PlayMode,
)

/**
 * 播放器状态机（纯 Kotlin，可单测）：
 * 负责队列 / 播放模式 / 上下曲索引 / 进度 的状态演进，
 * 不依赖任何 Android 播放实现。
 */
class PlayerStateMachine {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    // SHUFFLE 模式下的播放顺序（原始索引数组）
    private var shuffleOrder: List<Int> = emptyList()

    fun setQueue(items: List<QueueItem>, startIndex: Int = 0) {
        val index = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.lastIndex)
        shuffleOrder = items.indices.shuffled()
        _state.update { it.copy(queue = items, currentIndex = index, positionMs = 0, durationMs = 0, phase = PlayPhase.READY) }
    }

    /**
     * Replacing a queue from a direct user selection is a play request.  Keep the
     * queue and play intent in one state transition so a collector cannot observe
     * a new READY queue and mistake it for a restored, intentionally-paused queue.
     */
    fun setQueueAndPlay(items: List<QueueItem>, startIndex: Int = 0) {
        val index = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.lastIndex)
        shuffleOrder = items.indices.shuffled()
        _state.update {
            it.copy(
                queue = items,
                currentIndex = index,
                positionMs = 0,
                durationMs = 0,
                phase = if (index >= 0) PlayPhase.LOADING else PlayPhase.IDLE,
                selectionGeneration = it.selectionGeneration + 1L,
                error = null,
            )
        }
    }

    fun enqueueAppend(items: List<QueueItem>) {
        _state.update { it.copy(queue = it.queue + items) }
        shuffleOrder = _state.value.queue.indices.shuffled()
    }

    /**
     * Replace display metadata for the current queue without restarting playback.
     * The id layout must still match so a delayed metadata request cannot overwrite a
     * queue that the user has already replaced.
     */
    fun replaceQueueMetadata(items: List<QueueItem>): Boolean {
        while (true) {
            val current = _state.value
            if (current.queue.size != items.size || current.queue.indices.any { current.queue[it].id != items[it].id }) {
                return false
            }
            val next = current.copy(queue = current.queue.zip(items) { live, metadata ->
                live.copy(
                    title = metadata.title.ifBlank { live.title },
                    artist = metadata.artist.ifBlank { live.artist },
                    album = metadata.album.ifBlank { live.album },
                    coverUrl = metadata.coverUrl?.takeIf { it.isNotBlank() } ?: live.coverUrl,
                )
            })
            if (_state.compareAndSet(current, next)) return true
        }
    }

    fun playIndex(index: Int) {
        val q = _state.value.queue
        if (index !in q.indices) return
        // Queue navigation is also an explicit selection.  This includes adjacent duplicate
        // media keys, for which ExoPlayer cannot detect an item change by itself.
        _state.update {
            it.copy(
                currentIndex = index,
                positionMs = 0,
                durationMs = q[index].durationMs,
                phase = PlayPhase.LOADING,
                selectionGeneration = it.selectionGeneration + 1L,
            )
        }
    }

    /** 计算下一曲索引（考虑播放模式），返回 null 表示队列尽头（顺序模式且非循环） */
    fun nextIndex(): Int? {
        val s = _state.value
        if (s.queue.isEmpty() || s.currentIndex < 0) return null
        return when (s.playMode) {
            PlayMode.REPEAT_ONE -> s.currentIndex
            PlayMode.SHUFFLE -> nextIn(shuffleOrder, s.currentIndex)
            PlayMode.REPEAT_ALL -> (s.currentIndex + 1) % s.queue.size
            PlayMode.SEQUENCE -> {
                val n = s.currentIndex + 1
                if (n >= s.queue.size) null else n
            }
        }
    }

    fun previousIndex(): Int {
        val s = _state.value
        if (s.queue.isEmpty() || s.currentIndex !in s.queue.indices) return -1
        return when (s.playMode) {
            PlayMode.SHUFFLE -> prevIn(shuffleOrder, s.currentIndex)
            else -> (s.currentIndex - 1 + s.queue.size) % s.queue.size
        }
    }

    fun setPlayMode(mode: PlayMode) {
        _state.update { it.copy(playMode = mode) }
        if (mode == PlayMode.SHUFFLE && shuffleOrder.isEmpty()) {
            shuffleOrder = _state.value.queue.indices.shuffled()
        }
    }

    fun cyclePlayMode(): PlayMode {
        val next = PlayMode.entries[(PlayMode.entries.indexOf(_state.value.playMode) + 1) % PlayMode.entries.size]
        setPlayMode(next)
        return next
    }

    fun onLoading() = _state.update { it.copy(phase = PlayPhase.LOADING) }
    fun onPlaying() = _state.update { it.copy(phase = PlayPhase.PLAYING, error = null) }
    fun onPaused() = _state.update { it.copy(phase = PlayPhase.PAUSED) }
    fun onReady(durationMs: Long) = _state.update { it.copy(phase = PlayPhase.READY, durationMs = durationMs) }

    fun onError(message: String) {
        _state.update { it.copy(phase = PlayPhase.ERROR, error = message) }
    }

    fun updatePosition(positionMs: Long, bufferedMs: Long = 0) {
        _state.update { it.copy(positionMs = positionMs, bufferedMs = bufferedMs) }
    }

    fun setDuration(durationMs: Long) = _state.update { it.copy(durationMs = durationMs) }
    fun setPreparingNext(value: Boolean) = _state.update { it.copy(isPreparingNext = value) }

    /** 音质降级：替换队列中某条目音质（用于自动降级链） */
    fun replaceItemQuality(index: Int, quality: com.litemusic.shared.util.Quality) {
        _state.update { s ->
            if (index !in s.queue.indices) s
            else s.copy(queue = s.queue.toMutableList().also { it[index] = it[index].copy(quality = quality) })
        }
    }

    fun removeAt(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices) return
        val newQueue = s.queue.toMutableList().apply { removeAt(index) }
        val newIndex = when {
            index < s.currentIndex -> s.currentIndex - 1
            index == s.currentIndex -> -1
            else -> s.currentIndex
        }
        _state.update { it.copy(queue = newQueue, currentIndex = newIndex) }
        shuffleOrder = newQueue.indices.shuffled()
    }

    fun move(from: Int, to: Int) {
        val s = _state.value
        if (from !in s.queue.indices || to !in s.queue.indices) return
        val list = s.queue.toMutableList()
        val moved = list.removeAt(from)
        list.add(to, moved)
        val newIndex = when {
            s.currentIndex == from -> to
            s.currentIndex in (minOf(from, to) + 1)..maxOf(from, to) && s.currentIndex != from -> s.currentIndex + if (from < to) -1 else 1
            else -> s.currentIndex
        }
        _state.update { it.copy(queue = list, currentIndex = newIndex) }
        shuffleOrder = list.indices.shuffled()
    }

    fun snapshot(): QueueSnapshot = with(_state.value) {
        QueueSnapshot(queue, currentIndex, positionMs, playMode)
    }

    fun restore(snapshot: QueueSnapshot) {
        shuffleOrder = snapshot.items.indices.shuffled()
        _state.value = PlayerUiState(
            queue = snapshot.items,
            currentIndex = snapshot.currentIndex,
            positionMs = snapshot.positionMs,
            playMode = snapshot.playMode,
            phase = PlayPhase.PAUSED,
        )
    }

    /** Apply a persisted snapshot only while app startup has not received a user action. */
    fun restoreIfPristine(snapshot: QueueSnapshot): Boolean {
        while (true) {
            val current = _state.value
            if (current != PlayerUiState()) return false
            val restored = PlayerUiState(
                queue = snapshot.items,
                currentIndex = snapshot.currentIndex,
                positionMs = snapshot.positionMs,
                playMode = snapshot.playMode,
                phase = PlayPhase.PAUSED,
            )
            if (_state.compareAndSet(current, restored)) {
                shuffleOrder = snapshot.items.indices.shuffled()
                return true
            }
        }
    }

    private fun nextIn(order: List<Int>, current: Int): Int? {
        if (order.isEmpty()) return null
        val pos = order.indexOf(current)
        return if (pos < 0) order[0] else order[(pos + 1) % order.size]
    }

    private fun prevIn(order: List<Int>, current: Int): Int {
        if (order.isEmpty()) return current
        val pos = order.indexOf(current)
        return if (pos < 0) current else order[(pos - 1 + order.size) % order.size]
    }
}
