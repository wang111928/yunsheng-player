package com.litemusic.player

import android.app.PendingIntent
import android.app.ActivityOptions
import android.os.Build
import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.player.PlayPhase
import com.litemusic.shared.player.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 播放服务：MediaSessionService + ExoPlayer。
 *  - Android 14+ mediaPlayback 前台服务类型
 *  - 播放队列来自 PlayerStateMachine（进程被杀后从 Room 恢复）
 *  - 音质降级链：URL 解析失败 / 播放错误自动降一档
 *
 * 与 app 层的契约：App 侧只改 PlayerStateMachine（纯 Kotlin 状态机），
 * 本服务把状态机映射到 ExoPlayer。这样即使 MediaController 未连上，
 * 播放/暂停/切歌依然有效（旧实现：播放键直接调 MediaController，连不上就静默失效）。
 */
class PlaybackService : MediaSessionService() {

    lateinit var player: ExoPlayer
    private lateinit var sessionNavigator: QueueSessionNavigator
    private lateinit var sessionPlayer: QueueSessionPlayer
    lateinit var mediaSession: MediaSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 由 App 装配注入（Koin 无法注入 Service 时通过伴生对象桥接）
    companion object {
        const val TAG = "NmlPlayer"
        const val EXTRA_OPEN_PLAYER = "nml.openPlayer"
        const val EXTRA_SHOW_LYRICS = "nml.showLyrics"
        private const val ACTION_FAVORITE = "nml.favorite"
        private const val ACTION_LYRICS = "nml.lyrics"
        private const val ACTION_NEXT = "nml.next"
        private const val ACTION_PREVIOUS = "nml.previous"

        @Volatile
        var bridge: PlaybackBridge? = null

        @Volatile
        var lockScreenLyric: LockScreenLyricController? = null
    }

    /** 本次播放已尝试过「强制刷新播放地址」的歌曲 id（避免错误循环） */
    private val refreshedUrlIds: MutableSet<Long> = mutableSetOf()
    private var lastNavigationFingerprint: Triple<Int, Int, com.litemusic.shared.player.PlayMode>? = null
    private var lastButtonItem: QueueItem? = null
    private val pendingFavorites = mutableSetOf<Long>()
    private var suppressInternalPauseState = false

    /** 播放器的「当前媒体」标识：歌曲 id + 音质码率。音质一变即视为换源，需要重解析地址。 */
    private fun mediaKey(item: QueueItem): String = item.id.toString() + "#" + item.quality.br

    private fun currentMediaKeyOf(player: ExoPlayer): String? = player.currentMediaItem?.mediaId

    override fun onCreate() {
        super.onCreate()

        val b = bridge ?: error("PlaybackService.bridge 未装配")
        val stateMachine = b.stateMachine

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        sessionNavigator = QueueSessionNavigator(stateMachine)
        // ExoPlayer intentionally owns one resolved stream only.  Expose the state-machine
        // queue to system surfaces through this adapter so NEXT/PREVIOUS are standard player
        // commands rather than opaque custom actions (lock-screen and atomic widgets rely on
        // those commands when deciding which controls to display).
        sessionPlayer = QueueSessionPlayer(player, stateMachine)

        mediaSession = MediaSession.Builder(this, sessionPlayer).apply {
            sessionActivityPendingIntent()?.let { setSessionActivity(it) }
            setCallback(sessionCallback())
        }.build()
        updateMediaButtons()
        b.favoriteIds?.let { ids -> scope.launch { ids.collect { updateMediaButtons() } } }

        // 状态机 → 播放器
        // 注意：这里必须用 collect 而不是 collectLatest。进度计时器每 250ms 就会写入一次
        // 播放位置（切歌瞬间位置非 0，状态必变），collectLatest 会取消正在进行的
        // resolveUrl 网络请求，导致「新歌永远加载不出来」。
        scope.launch {
            stateMachine.state.collect { s ->
                val navigation = Triple(s.queue.size, s.currentIndex, s.playMode)
                if (navigation != lastNavigationFingerprint) {
                    lastNavigationFingerprint = navigation
                    sessionPlayer.refreshCommands()
                    updateMediaButtons()
                }
                if (s.current != lastButtonItem) {
                    lastButtonItem = s.current
                    updateMediaButtons()
                }
                val current = s.current
                if (current == null) {
                    // A connected MediaSession must not keep exposing the previous song when
                    // the app queue has been cleared (including removal of its last item).
                    if (player.mediaItemCount > 0) {
                        player.pause()
                        player.stop()
                        player.clearMediaItems()
                    }
                    return@collect
                }
                val key = mediaKey(current)
                val isSame = player.currentMediaItem?.mediaId == key
                if (!isSame) {
                    // 音质切换：同一首歌重解析地址，保留播放进度（否则会被重置到 0）
                    val sameSong = player.currentMediaItem?.mediaId?.substringBefore('#') == current.id.toString()
                    val resume = if (sameSong) player.currentPosition.coerceAtLeast(0) else 0L
                    val url = try { b.resolveUrl(current) } catch (e: Exception) { null }
                    val latest = stateMachine.state.value
                    val latestKey = latest.current?.let(::mediaKey)
                    if (latestKey != key) {
                        // The resolver finished after the queue changed.  Do not put an old URL
                        // back into ExoPlayer, even for a moment.  It must also not pause the
                        // new item, whose request may already be playing concurrently.
                        return@collect
                    }
                    if (url.isNullOrBlank()) {
                        stateMachine.onError("无法获取播放地址")
                        return@collect
                    }
                    refreshedUrlIds.clear()
                    PlayerLog.i("播放 " + current.id + " 音质=" + current.quality.code + " url=" + url.substringBefore('?'))
                    val mediaItem = buildMediaItem(current, url)
                    pauseInternally()
                    player.setMediaItem(mediaItem)
                    if (resume > 0) player.seekTo(resume)
                    if (canApplyResolvedSource(key, latestKey, latest.phase)) {
                        player.prepare()
                        player.play()
                    } else {
                        player.pause()
                    }
                } else if (s.phase == com.litemusic.shared.player.PlayPhase.PLAYING && !player.isPlaying) {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                } else if (s.phase == PlayPhase.PAUSED && player.playWhenReady) {
                    // 状态机的暂停意图 → 真正暂停播放器（播放键的可靠性依赖这条路径）
                    pauseInternally()
                }
            }
        }

        // 播放器 → 状态机 + 锁屏歌词
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (synchronizePauseFromPlayWhenReady(
                        stateMachine = stateMachine,
                        playWhenReady = playWhenReady,
                        suppressInternalPause = suppressInternalPauseState,
                    )
                ) return

                // MediaController/notification Play targets ExoPlayer directly. A restored
                // paused item is deliberately IDLE, so prepare it only when play is requested.
                if (playWhenReady && player.playbackState == Player.STATE_IDLE &&
                    player.currentMediaItem != null && stateMachine.state.value.current != null
                ) {
                    stateMachine.onLoading()
                    player.prepare()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (player.playWhenReady) stateMachine.onLoading() else stateMachine.onPaused()
                    }
                    Player.STATE_READY -> {
                        if (player.playWhenReady) {
                            stateMachine.onPlaying()
                            stateMachine.setDuration(player.duration.takeIf { it > 0 } ?: stateMachine.state.value.durationMs)
                        } else stateMachine.onPaused()
                    }
                    Player.STATE_ENDED -> {
                        val next = stateMachine.nextIndex()
                        if (next != null) {
                            val repeatCurrent = next == stateMachine.state.value.currentIndex
                            stateMachine.playIndex(next)
                            if (repeatCurrent) {
                                // Repeat-one (or a one-item repeat-all queue) keeps the same
                                // mediaId, so the state collector does not replace the item.
                                player.seekTo(0)
                                player.prepare()
                                player.play()
                            }
                        } else stateMachine.onPaused()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlayerLog.i(
                    "media state isPlaying=$isPlaying playWhenReady=${player.playWhenReady} " +
                        "playbackState=${player.playbackState} suppression=${player.playbackSuppressionReason} " +
                        "uiPhase=${stateMachine.state.value.phase}",
                )
                if (isPlaying) {
                    refreshedUrlIds.clear()
                    stateMachine.onPlaying()
                } else if (suppressInternalPauseState) {
                } else if (!player.playWhenReady) {
                    stateMachine.onPaused()
                } else if (player.playbackState == Player.STATE_BUFFERING) {
                    stateMachine.onLoading()
                }
            }

            /**
             * 播放失败恢复链：
             *  1) 先「强制重取播放地址」同音质重试一次（网易 URL 有时效，缓存地址过期后 CDN 返回 403，
             *     这是线上最常见的失败原因）；
             *  2) 仍失败再按 极高→较高→标准 逐级降音质；
             *  3) 全部失败才把错误上报到 UI。
             */
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                PlayerLog.e(
                    "onPlayerError: " + error.errorCodeName + " / " + error.message +
                        " / url=" + (player.currentMediaItem?.localConfiguration?.uri?.toString()?.substringBefore('?') ?: "-") +
                        " / playing=" + player.playbackState,
                    error
                )
                val s = stateMachine.state.value
                val cur = s.current ?: return
                val failedKey = mediaKey(cur)
                scope.launch {
                    if (refreshedUrlIds.add(cur.id)) {
                        val fresh = try { b.resolveUrl(cur, true) } catch (e: Exception) { null }
                        if (!fresh.isNullOrBlank()) {
                            val latest = stateMachine.state.value
                            if (latest.current?.let(::mediaKey) != failedKey) {
                                return@launch
                            }
                            PlayerLog.i("重取播放地址成功，同音质重试 url=" + fresh.substringBefore('?'))
                            player.setMediaItem(buildMediaItem(cur, fresh))
                            if (canApplyResolvedSource(failedKey, latest.current?.let(::mediaKey), latest.phase)) {
                                player.prepare()
                                player.play()
                            } else pauseInternally()
                            return@launch
                        }
                    }
                    val degraded = cur.quality.degrade()
                    if (degraded != null) {
                        val item = cur.copy(quality = degraded)
                        val url = try { b.resolveUrl(item) } catch (e: Exception) { null }
                        val latest = stateMachine.state.value
                        if (latest.current?.let(::mediaKey) != failedKey) {
                            return@launch
                        }
                        if (!url.isNullOrBlank()) {
                            PlayerLog.i("音质降级为 " + degraded.code + " 后重试 url=" + url.substringBefore('?'))
                            stateMachine.replaceItemQuality(latest.currentIndex, degraded)
                            player.setMediaItem(buildMediaItem(item, url))
                            if (shouldPlayForPhase(latest.phase)) {
                                player.prepare()
                                player.play()
                            } else pauseInternally()
                            return@launch
                        }
                    }
                    if (stateMachine.state.value.current?.let(::mediaKey) == failedKey) {
                        stateMachine.onError("播放失败：" + error.errorCodeName)
                    }
                }
            }
        })

        // Position updates. ExoPlayer only contains the current song; the app queue remains
        // authoritative. Adding the next item here would let the platform auto-advance it
        // without updating PlayerStateMachine, making the notification and app disagree.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(250)
                val s = stateMachine.state.value
                val duration = player.duration.takeIf { it > 0 } ?: s.durationMs
                if (duration > 0 && duration != s.durationMs) stateMachine.setDuration(duration)
                stateMachine.updatePosition(player.currentPosition, player.bufferedPosition)
            }
        }
    }

    private fun pauseInternally() {
        if (!player.playWhenReady) return
        // ExoPlayer flushes listener callbacks inside pause(). Scope the guard so a pause while
        // already buffering cannot leave a stale flag that swallows the user's next pause.
        suppressInternalPauseState = true
        try {
            player.pause()
        } finally {
            suppressInternalPauseState = false
        }
    }

    private fun buildMediaItem(item: QueueItem, url: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaKey(item))
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setAlbumTitle(item.album)
                    .setArtworkUri(item.coverUrl?.let { android.net.Uri.parse(it) })
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    private fun sessionActivityPendingIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.putExtra(EXTRA_OPEN_PLAYER, true)
        intent.putExtra(EXTRA_SHOW_LYRICS, false)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun updateMediaButtons() {
        val current = bridge?.stateMachine?.state?.value?.current
        val liked = current?.let { bridge?.favoriteIds?.value?.contains(it.id) == true } == true
        val favoriteEnabled = current != null && !current.isLocal && bridge?.favoriteIds != null
        mediaSession.setMediaButtonPreferences(listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS).setDisplayName("上一首").setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS).setEnabled(sessionNavigator.hasPrevious()).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_PLAY).setDisplayName("播放/暂停").setPlayerCommand(Player.COMMAND_PLAY_PAUSE).setSlots(CommandButton.SLOT_CENTRAL).build(),
            CommandButton.Builder(CommandButton.ICON_NEXT).setDisplayName("下一首").setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT).setEnabled(sessionNavigator.hasNext()).setSlots(CommandButton.SLOT_FORWARD).build(),
            CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (liked) "取消喜欢" else "喜欢").setSessionCommand(SessionCommand(ACTION_FAVORITE, android.os.Bundle())).setEnabled(favoriteEnabled).setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW).build(),
            CommandButton.Builder(CommandButton.ICON_SUBTITLES).setDisplayName("歌词").setSessionCommand(SessionCommand(ACTION_LYRICS, android.os.Bundle())).setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW).build(),
        ))
    }

    private fun sessionCallback() = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            @Suppress("DEPRECATION")
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
            return when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> sessionNavigator.next()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> sessionNavigator.previous()
                else -> false
            }
        }

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_FAVORITE, android.os.Bundle()))
                .add(SessionCommand(ACTION_LYRICS, android.os.Bundle()))
                .add(SessionCommand(ACTION_NEXT, android.os.Bundle()))
                .add(SessionCommand(ACTION_PREVIOUS, android.os.Bundle()))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            command: SessionCommand,
            args: android.os.Bundle,
        ) = when (command.customAction) {
            ACTION_NEXT -> Futures.immediateFuture(SessionResult(if (sessionNavigator.next()) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            ACTION_PREVIOUS -> Futures.immediateFuture(SessionResult(if (sessionNavigator.previous()) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            ACTION_LYRICS -> {
                Futures.immediateFuture(SessionResult(if (openPlayer(lyrics = true)) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_UNKNOWN))
            }
            ACTION_FAVORITE -> {
                val item = bridge?.stateMachine?.state?.value?.current
                val b = bridge
                if (item == null || item.isLocal || b?.favoriteIds == null) {
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                } else {
                    val result = SettableFuture.create<SessionResult>()
                    if (!pendingFavorites.add(item.id)) {
                        result.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                    } else {
                        val liked = b.favoriteIds?.value?.contains(item.id) != true
                        scope.launch {
                            try {
                                val success = b.setFavorite(item, liked)
                                result.set(SessionResult(if (success) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_UNKNOWN))
                            } catch (cancelled: CancellationException) {
                                result.cancel(false)
                                throw cancelled
                            } catch (_: Exception) {
                                result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                            } finally {
                                pendingFavorites.remove(item.id)
                            }
                        }
                    }
                    result
                }
            }
            else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    private fun openPlayer(lyrics: Boolean): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra(EXTRA_OPEN_PLAYER, true)
        intent.putExtra(EXTRA_SHOW_LYRICS, lyrics)
        return try {
            val pending = PendingIntent.getActivity(this, if (lyrics) 1 else 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle() else null
            pending.send(this, 0, null, null, null, null, options)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        player.pause()
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession.run { player.release(); release() }
        super.onDestroy()
    }
}
