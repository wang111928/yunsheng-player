package com.litemusic.player

import android.content.Context
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.litemusic.shared.player.PlayMode
import com.litemusic.shared.player.PlayPhase
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.util.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.concurrent.futures.await

/**
 * App 侧播放控制器：连接 PlaybackService，转发用户操作到状态机与播放器。
 */
class PlaybackController(
    private val context: Context,
    val stateMachine: PlayerStateMachine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private var connecting: Job? = null
    private var pendingSeekMs: Long? = null

    val state: StateFlow<com.litemusic.shared.player.PlayerUiState> = stateMachine.state

    suspend fun connect() {
        runCatching {
            controller = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            )
                .buildAsync()
                .await()
        }.onSuccess {
            PlayerLog.i(
                "MediaController 已连接 isPlaying=${controller?.isPlaying} " +
                    "playWhenReady=${controller?.playWhenReady} uiPhase=${state.value.phase}",
            )
            pendingSeekMs?.let { position ->
                controller?.seekTo(position)
                pendingSeekMs = null
            }
        }.onFailure {
            PlayerLog.e("MediaController 连接失败（播放/暂停/seek 将走状态机通道）", it)
        }
    }

    /**
     * 懒重连：MediaController 连接失败（冷启动竞态 / 服务被回收）时必须能自愈，
     * 否则播放页的播放键会永久失效 —— 这是线上「播放键点不动」的根因。
     */
    private fun ensureConnected() {
        if (controller != null || connecting?.isActive == true) return
        connecting = scope.launch { connect() }
    }

    fun playIndex(index: Int) {
        stateMachine.playIndex(index)
        ensureConnected()
        controller?.play()
    }

    /**
     * 播放 / 暂停。
     *
     * 关键：先改状态机（PlaybackService 会 collect 状态机并同步 ExoPlayer），
     * 再尽力直连 MediaController 降低延迟。旧实现只调 controller?.pause()，
     * 一旦 MediaController 未连上就静默无动作 —— 表现为「播放键点了没反应」，
     * 而上一首/下一首走的是状态机通道，所以只有播放键坏。
     */
    fun toggle() {
        val s = state.value
        if (s.current == null) return
        val wantPlay = s.phase != PlayPhase.PLAYING
        if (wantPlay) {
            stateMachine.onPlaying()
            controller?.play()
        } else {
            stateMachine.onPaused()
            controller?.pause()
        }
        ensureConnected()
    }

    fun next() {
        val idx = stateMachine.nextIndex()
        if (idx != null) playIndex(idx)
    }

    fun previous() {
        val idx = stateMachine.previousIndex()
        if (idx >= 0) playIndex(idx)
    }

    fun seekTo(ms: Long) {
        val position = ms.coerceAtLeast(0L)
        stateMachine.updatePosition(position, state.value.bufferedMs)
        ensureConnected()
        if (controller == null) pendingSeekMs = position else controller?.seekTo(position)
    }

    /**
     * 切换音质：改写队列条目音质 → PlaybackService 检测到 mediaKey 变化后重解析地址，
     * 同一首歌保留播放进度。
     */
    fun setQuality(quality: Quality) {
        val idx = state.value.currentIndex
        if (idx < 0) return
        if (state.value.queue.getOrNull(idx)?.quality == quality) return
        PlayerLog.i("切换音质 → " + quality.code)
        stateMachine.replaceItemQuality(idx, quality)
    }

    fun setQueue(items: List<QueueItem>, startIndex: Int = 0) {
        stateMachine.setQueue(items, startIndex)
    }

    fun enqueue(items: List<QueueItem>) = stateMachine.enqueueAppend(items)

    fun replaceQueueMetadata(items: List<QueueItem>): Boolean = stateMachine.replaceQueueMetadata(items)

    fun setPlayMode(mode: PlayMode) = stateMachine.setPlayMode(mode)

    fun cyclePlayMode(): PlayMode = stateMachine.cyclePlayMode()

    fun removeAt(index: Int) = stateMachine.removeAt(index)

    fun move(from: Int, to: Int) = stateMachine.move(from, to)

    fun release() {
        scope.launch { controller?.release() }
    }
}
