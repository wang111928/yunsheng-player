package com.litemusic.player

import android.app.PendingIntent
import android.app.ActivityOptions
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import android.os.SystemClock

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
    private lateinit var streamAudioCache: StreamAudioCache

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
        private const val STREAM_SOURCE_PREFS = "stream-source-v1"

        @Volatile
        var bridge: PlaybackBridge? = null

        @Volatile
        var lockScreenLyric: LockScreenLyricController? = null

        private val sleepTimer = SleepTimerController(SystemClock::elapsedRealtime)
        val sleepTimerState = sleepTimer.state
        fun startSleepTimer(minutes: Int) = sleepTimer.startMinutes(minutes)
        fun stopAfterCurrentTrack(mediaKey: String, selectionGeneration: Long) =
            sleepTimer.stopAfterCurrent(mediaKey, selectionGeneration)
        fun cancelSleepTimer() = sleepTimer.cancel()
    }

    /** Each direct selection may force-refresh its URL once; a stale selection cannot consume it. */
    private val refreshedUrlIds: MutableSet<UrlRefreshAttempt> = mutableSetOf()
    private var lastNavigationFingerprint: Triple<Int, Int, com.litemusic.shared.player.PlayMode>? = null
    private var lastButtonItem: QueueItem? = null
    private var lastConsumedSelectionGeneration = 0L
    private val pendingFavorites = mutableSetOf<Long>()
    private var suppressInternalPauseState = false
    private val streamPrefetchLock = Any()
    private var activeStreamPrefetch: StreamPrefetchTask? = null
    private var initialQueueRestoreComplete = false
    private val preRestorePlayIntent = PreRestorePlayIntent()
    /** Identity, rather than a media key alone, keeps an old IO completion from clearing a new task. */
    private class StreamPrefetchTask(val mediaKey: String) {
        var job: Job? = null
        var writer: CacheWriter? = null
    }

    /** 播放器的「当前媒体」标识：歌曲 id + 音质码率。音质一变即视为换源，需要重解析地址。 */
    private fun mediaKey(item: QueueItem): String = playbackMediaKey(item)

    private fun currentMediaKeyOf(player: ExoPlayer): String? = player.currentMediaItem?.mediaId

    override fun onCreate() {
        super.onCreate()

        val b = bridge ?: error("PlaybackService.bridge 未装配")
        val stateMachine = b.stateMachine
        clearLegacyPersistedStreamUrls()
        streamAudioCache = StreamAudioCacheStore.get(this)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(streamAudioCache.playbackDataSourceFactory))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 120_000, 1_000, 2_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
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
            b.awaitInitialQueueRestore()
            initialQueueRestoreComplete = true
            preRestorePlayIntent.consumeForRestoredSelection(stateMachine)
            // A Service can be recreated while the app process (and its state machine) survives.
            // The new ExoPlayer has no media item, so an existing selection token is history:
            // treating it as a fresh tap would discard the saved playback position.
            lastConsumedSelectionGeneration = stateMachine.state.value.selectionGeneration
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
                    cancelCurrentStreamCompletion()
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
                val selectionGeneration = s.selectionGeneration
                val isSame = player.currentMediaItem?.mediaId == key
                val isNewExplicitSelection = s.selectionGeneration > lastConsumedSelectionGeneration
                // A completed stream can have been written at a lower quality after automatic
                // degradation, or at a higher quality before the user changed Settings.  Only
                // examine cache variants while a source must be installed; this collector also
                // receives position updates every 250ms, so scanning on every unchanged item
                // would put needless cache IO on the main thread. Never switch quality online.
                if (shouldCheckOfflineCachedVariant(isSame, isNewExplicitSelection) && !hasValidatedNetwork()) {
                    val cachedItem = OfflinePlaybackAvailability.playableCachedItem(this@PlaybackService, current)
                    if (cachedItem != null && cachedItem.quality != current.quality) {
                        PlayerLog.i(
                            "离线使用完整缓存 id=${current.id} " +
                                "音质=${current.quality.code}->${cachedItem.quality.code}",
                        )
                        stateMachine.replaceItemQuality(s.currentIndex, cachedItem.quality)
                        return@collect
                    }
                }
                if (!isSame) {
                    if (isNewExplicitSelection) lastConsumedSelectionGeneration = s.selectionGeneration
                    cancelCurrentStreamCompletion()
                    // A selection changes state immediately, but URL resolution is asynchronous.
                    // Silence the previous item before awaiting the new source so it cannot be
                    // resumed by a stale MediaController command when the new source is offline.
                    // An ended item is already silent. Pausing it would emit a stale
                    // playWhenReady=false event while the next item is LOADING.
                    if (player.playbackState != Player.STATE_ENDED) pauseInternally()
                    // Keep a failed selection silent until the user explicitly asks to retry.
                    // Otherwise onError() emits another state and this collector loops through
                    // failing URL resolutions while ExoPlayer still retains the old media item.
                    if (s.phase == PlayPhase.ERROR) return@collect
                    // 音质切换：同一首歌重解析地址，保留播放进度（否则会被重置到 0）
                    val sameSong = player.currentMediaItem?.mediaId?.substringBefore('#') == current.id.toString()
                    // A restored service has no ExoPlayer item yet, so its persisted state is
                    // the only source of truth for resume position. New selections reset the
                    // state-machine position to zero and therefore still start at the beginning.
                    val resume = sourceResumePosition(
                        explicitSelection = isNewExplicitSelection,
                        sameSong = sameSong,
                        currentPositionMs = player.currentPosition,
                        restoredPositionMs = s.positionMs,
                        hasMediaItem = player.mediaItemCount > 0,
                    )
                    val url = resolvePlaybackUrl(b, current)
                    val latest = stateMachine.state.value
                    val latestKey = latest.current?.let(::mediaKey)
                    if (!isCurrentSelection(key, selectionGeneration, latestKey, latest.selectionGeneration)) {
                        // The resolver finished after the queue changed.  Do not put an old URL
                        // back into ExoPlayer, even for a moment.  It must also not pause the
                        // new item, whose request may already be playing concurrently.
                        return@collect
                    }
                    if (url.isNullOrBlank()) {
                        if (shouldClearStaleMediaAfterSourceFailure(
                                failedMediaKey = key,
                                currentMediaKey = stateMachine.state.value.current?.let(::mediaKey),
                                playerMediaKey = currentMediaKeyOf(player),
                            )
                        ) {
                            // The failed selection must not leave the old song's notification,
                            // lock-screen artwork, or resumable media item behind.
                            pauseInternally()
                            player.stop()
                            player.clearMediaItems()
                        }
                        val currentState = stateMachine.state.value
                        if (isCurrentSelection(
                                key,
                                selectionGeneration,
                                currentState.current?.let(::mediaKey),
                                currentState.selectionGeneration,
                            )
                        ) {
                            stateMachine.onError(playbackFailureMessageFor(current))
                        }
                        return@collect
                    }
                    refreshedUrlIds.clear()
                    PlayerLog.i("播放 " + current.id + " 音质=" + current.quality.code + " url=" + url.substringBefore('?'))
                    val mediaItem = buildMediaItem(current, url)
                    if (player.playbackState != Player.STATE_ENDED) pauseInternally()
                    player.setMediaItem(mediaItem)
                    if (resume > 0) player.seekTo(resume)
                    if (canApplyResolvedSource(key, latestKey, latest.phase)) {
                        player.prepare()
                        player.play()
                    } else {
                        player.pause()
                    }
                } else {
                    val restartSelection = shouldRestartSelectedCurrentMedia(
                        selectionGeneration = s.selectionGeneration,
                        lastConsumedSelectionGeneration = lastConsumedSelectionGeneration,
                        phase = s.phase,
                    )
                    // Consume even a selection that was paused before the collector observed
                    // it. A later buffering/Play callback must not replay that old tap.
                    if (isNewExplicitSelection) lastConsumedSelectionGeneration = s.selectionGeneration
                    when {
                        restartSelection -> {
                            player.seekTo(0)
                            if (shouldPrepareForExplicitCurrentSelection(player.playbackState)) player.prepare()
                            player.play()
                        }
                        s.phase == PlayPhase.PLAYING && !player.isPlaying -> {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                            player.play()
                        }
                        s.phase == PlayPhase.PAUSED && player.playWhenReady -> {
                            // 状态机的暂停意图 → 真正暂停播放器（播放键的可靠性依赖这条路径）
                            pauseInternally()
                        }
                    }
                }
            }
        }

        // 播放器 → 状态机 + 锁屏歌词
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (player.currentMediaItem == null) {
                    if (!initialQueueRestoreComplete) {
                        // The session becomes available before queue restore. Preserve only the
                        // latest external request and bind it to the restored current item later.
                        if (!suppressInternalPauseState) {
                            if (playWhenReady) preRestorePlayIntent.requestPlay()
                            else preRestorePlayIntent.cancel()
                        }
                        return
                    }
                    // pauseInternally() can run while a URL is being installed. Its callback
                    // must not erase a system Play command that arrived before the source.
                    if (playWhenReady) {
                        synchronizePlayWithoutMedia(
                            stateMachine = stateMachine,
                            suppressInternalPlay = suppressInternalPauseState,
                        )
                    }
                    if (shouldSynchronizePauseWithoutMedia(
                            phase = stateMachine.state.value.phase,
                            playWhenReady = playWhenReady,
                            suppressInternalPause = suppressInternalPauseState,
                        )
                    ) {
                        synchronizePauseFromPlayWhenReady(
                            stateMachine = stateMachine,
                            playWhenReady = false,
                            suppressInternalPause = false,
                        )
                    }
                    return
                }
                if (!isCurrentMediaEvent(
                        playerMediaKey = currentMediaKeyOf(player),
                        currentMediaKey = stateMachine.state.value.current?.let(::mediaKey),
                    )
                ) {
                    if (playWhenReady) pauseInternally()
                    else if (shouldPauseForStaleMediaEvent(playWhenReady, reason)) {
                        // Standard MediaSession pause commands update the state machine in
                        // QueueSessionPlayer. Only audio-focus loss from a stale ExoPlayer
                        // item should cancel the pending next-song intent here.
                        stateMachine.onPaused()
                    }
                    return
                }
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && synchronizePauseFromPlayWhenReady(
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
                if (!isCurrentMediaEvent(
                        playerMediaKey = currentMediaKeyOf(player),
                        currentMediaKey = stateMachine.state.value.current?.let(::mediaKey),
                    )
                ) {
                    if (player.playWhenReady) pauseInternally()
                    return
                }
                when (playbackState) {
                    Player.STATE_BUFFERING, Player.STATE_READY -> when (phaseForCurrentPlaybackState(
                        phaseBeforeCallback = stateMachine.state.value.phase,
                        playbackState = playbackState,
                        playWhenReady = player.playWhenReady,
                    )) {
                        PlayPhase.LOADING -> stateMachine.onLoading()
                        PlayPhase.PLAYING -> {
                            stateMachine.onPlaying()
                            stateMachine.setDuration(player.duration.takeIf { it > 0 } ?: stateMachine.state.value.durationMs)
                        }
                        PlayPhase.PAUSED -> stateMachine.onPaused()
                        null -> Unit
                        else -> Unit
                    }
                    Player.STATE_ENDED -> {
                        if (sleepTimer.shouldPause(SystemClock.elapsedRealtime(), currentMediaKeyOf(player), stateMachine.state.value.selectionGeneration)) {
                            stateMachine.onPaused()
                            pauseInternally()
                            return
                        }
                        if (shouldAdvanceAfterEnded(stateMachine.state.value.phase)) {
                            val endingMediaKey = currentMediaKeyOf(player)
                            val next = stateMachine.nextIndex()
                            if (next != null) {
                                stateMachine.playIndex(next)
                                val nextMediaKey = stateMachine.state.value.current?.let(::mediaKey)
                                if (shouldRestartEndedMedia(endingMediaKey, nextMediaKey)) {
                                    // Repeat-one and adjacent duplicate songs share the same
                                    // key, so queue state alone cannot make the collector swap.
                                    player.seekTo(0)
                                    player.prepare()
                                    player.play()
                                }
                            } else stateMachine.onPaused()
                        } else stateMachine.onPaused()
                    }
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlayerLog.i(
                    "media state isPlaying=$isPlaying playWhenReady=${player.playWhenReady} " +
                        "playbackState=${player.playbackState} suppression=${player.playbackSuppressionReason} " +
                        "uiPhase=${stateMachine.state.value.phase}",
                )
                if (!isCurrentMediaEvent(
                        playerMediaKey = currentMediaKeyOf(player),
                        currentMediaKey = stateMachine.state.value.current?.let(::mediaKey),
                    )
                ) {
                    if (isPlaying) pauseInternally()
                    return
                }
                if (isPlaying) {
                    refreshedUrlIds.clear()
                    stateMachine.onPlaying()
                    scheduleCurrentStreamCompletion(stateMachine.state.value.current)
                } else {
                    synchronizeNotPlayingObservation(
                        stateMachine = stateMachine,
                        playWhenReady = player.playWhenReady,
                        playbackState = player.playbackState,
                        suppressInternalPause = suppressInternalPauseState,
                    )
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
                val failedKey = currentMediaKeyOf(player) ?: return
                if (!isCurrentMediaEvent(failedKey, mediaKey(cur))) return
                val failedSelectionGeneration = s.selectionGeneration
                val resumePosition = player.currentPosition.coerceAtLeast(0)
                scope.launch {
                    if (refreshedUrlIds.add(UrlRefreshAttempt(cur.id, failedSelectionGeneration))) {
                        val fresh = resolvePlaybackUrl(b, cur, forceRefresh = true)
                        if (!fresh.isNullOrBlank()) {
                            val latest = stateMachine.state.value
                            if (!isCurrentSelection(
                                    failedKey,
                                    failedSelectionGeneration,
                                    latest.current?.let(::mediaKey),
                                    latest.selectionGeneration,
                                ) || currentMediaKeyOf(player) != failedKey
                            ) {
                                return@launch
                            }
                            PlayerLog.i("重取播放地址成功，同音质重试 url=" + fresh.substringBefore('?'))
                            // The old CacheWriter captured the expired URL. Let the recovered
                            // player start a writer for the fresh source when it reaches READY.
                            cancelCurrentStreamCompletion()
                            player.setMediaItem(buildMediaItem(cur, fresh))
                            if (resumePosition > 0) player.seekTo(resumePosition)
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
                        val url = resolvePlaybackUrl(b, item)
                        val latest = stateMachine.state.value
                        if (!isCurrentSelection(
                                failedKey,
                                failedSelectionGeneration,
                                latest.current?.let(::mediaKey),
                                latest.selectionGeneration,
                            ) || currentMediaKeyOf(player) != failedKey
                        ) {
                            return@launch
                        }
                        if (!url.isNullOrBlank()) {
                            PlayerLog.i("音质降级为 " + degraded.code + " 后重试 url=" + url.substringBefore('?'))
                            cancelCurrentStreamCompletion()
                            stateMachine.replaceItemQuality(latest.currentIndex, degraded)
                            player.setMediaItem(buildMediaItem(item, url))
                            if (resumePosition > 0) player.seekTo(resumePosition)
                            if (shouldPlayForPhase(latest.phase)) {
                                player.prepare()
                                player.play()
                            } else pauseInternally()
                            return@launch
                        }
                    }
                    val currentState = stateMachine.state.value
                    if (isCurrentSelection(
                            failedKey,
                            failedSelectionGeneration,
                            currentState.current?.let(::mediaKey),
                            currentState.selectionGeneration,
                        )
                    ) {
                        stateMachine.onError(playbackFailureMessageFor(cur, error.errorCode))
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
                sleepTimer.onSelectionChanged(s.current?.let(::mediaKey), s.selectionGeneration)
                if (sleepTimer.shouldPause(SystemClock.elapsedRealtime(), endedMediaKey = null)) {
                    stateMachine.onPaused()
                    pauseInternally()
                    continue
                }
                val currentKey = s.current?.let(::mediaKey)
                // Before a restored source has finished resolving, ExoPlayer reports position
                // zero. Never write that bootstrap value over the persisted resume position.
                if (currentKey == null || player.currentMediaItem?.mediaId != currentKey) continue
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
            // The URL contains a short-lived signature.  Keeping the cache key tied to song
            // identity and quality lets a restarted service read disk bytes through a newer
            // (or already expired) URI without mixing qualities.
            .apply { streamCacheKey(item)?.let(::setCustomCacheKey) }
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

    /**
     * This check is intentionally used only after source resolution/playback has failed.  Do
     * not reject a partially cached active stream before ExoPlayer has a chance to consume the
     * bytes already present in its cache.
     */
    private fun playbackFailureMessageFor(item: QueueItem, errorCode: Int? = null): String {
        val hasCompleteCache = item.isLocal || OfflinePlaybackAvailability.canPlay(this, item)
        return playbackFailureMessage(
            errorCode = errorCode,
            hasValidatedNetwork = hasValidatedNetwork(),
            hasCompleteCache = hasCompleteCache,
        )
    }

    private fun hasValidatedNetwork(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * A fully cached stream can be played after process restart without resolving its signed
     * URL again. The cache-only URI is synthetic: the custom cache key identifies the bytes, so
     * no signed CDN URL needs to be persisted in backup-eligible preferences.
     */
    private suspend fun resolvePlaybackUrl(
        bridge: PlaybackBridge,
        item: QueueItem,
        forceRefresh: Boolean = false,
    ): String? {
        if (item.isLocal) return runCatching { bridge.resolveUrl(item, forceRefresh) }.getOrNull()
        val cacheKey = streamCacheKey(item) ?: return null
        if (!forceRefresh && streamAudioCache.isComplete(cacheKey)) {
            PlayerLog.i("使用完整音频缓存 id=${item.id} 音质=${item.quality.code}")
            return offlineStreamCacheUri(item)
        }
        val resolved = runCatching { bridge.resolveUrl(item, forceRefresh) }.getOrNull()
        if (!resolved.isNullOrBlank()) {
            return resolved
        }
        return offlineStreamCacheUri(item).takeIf { streamAudioCache.hasAnyBytes(cacheKey) }
    }

    /**
     * Complete only the song the user has actually started.  It starts with actual playback so
     * even a short listen begins retaining bytes. The persistent cache has no size cap, has one
     * writer, and is cancelled as soon as the song/quality changes, so it never becomes a
     * queue-wide automatic download feature.
     */
    private fun scheduleCurrentStreamCompletion(item: QueueItem?) {
        val current = item ?: return
        if (!shouldPrefetchCurrentStream(current)) return
        val key = mediaKey(current)
        val cacheKey = streamCacheKey(current) ?: return
        // A completed entry is already usable after restart. Do not reopen the network merely
        // because playback later toggled between buffering and ready.
        if (streamAudioCache.isComplete(cacheKey)) return
        val url = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        // A restarted offline player uses a synthetic URI to read committed spans. It must not
        // start a background completion request against that placeholder host.
        if (url.startsWith(STREAM_CACHE_PLACEHOLDER_ORIGIN)) return
        val task = synchronized(streamPrefetchLock) {
            val previous = activeStreamPrefetch
            if (previous?.mediaKey == key) return
            activeStreamPrefetch = null
            previous?.writer?.cancel()
            previous?.job?.cancel()
            StreamPrefetchTask(key).also { activeStreamPrefetch = it }
        }
        val job = scope.launch {
            withContext(Dispatchers.IO) {
                val writer = CacheWriter(
                    streamAudioCache.prefetchDataSource(),
                    DataSpec.Builder().setUri(url).setKey(cacheKey).build(),
                    ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES),
                    null,
                )
                val isCurrent = synchronized(streamPrefetchLock) {
                    if (activeStreamPrefetch === task) {
                        task.writer = writer
                        true
                    } else {
                        false
                    }
                }
                // Cancellation can happen after this IO coroutine was scheduled but before the
                // writer became observable. Identity ownership closes that narrow race.
                if (!isCurrent) {
                    writer.cancel()
                    return@withContext
                }
                try {
                    writer.cache()
                    PlayerLog.i("当前歌曲缓存完成 id=${current.id} 音质=${current.quality.code}")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    // Network loss is expected.  Completed spans stay available and incomplete
                    // spans are never represented as a durable offline download.
                    PlayerLog.i("当前歌曲缓存未完成 id=${current.id}: ${e.javaClass.simpleName}")
                } finally {
                    // CacheWriter may commit fragments before a cancellation or a failing
                    // request. Refresh UI availability for every writer terminal path.
                    StreamAudioCacheStore.notifyContentsChanged()
                    synchronized(streamPrefetchLock) {
                        if (activeStreamPrefetch === task) {
                            activeStreamPrefetch = null
                        }
                    }
                }
            }
        }
        synchronized(streamPrefetchLock) {
            // The task could have been cancelled before its coroutine was published.
            if (activeStreamPrefetch === task) task.job = job else job.cancel()
        }
    }

    private fun cancelCurrentStreamCompletion(): Job? {
        return synchronized(streamPrefetchLock) {
            val task = activeStreamPrefetch ?: return@synchronized null
            // Clear ownership before cancellation. A late writer then observes that it no
            // longer owns the active slot and cannot overwrite a newer task's state.
            activeStreamPrefetch = null
            task.writer?.cancel()
            task.job?.cancel()
            task.job
        }
    }

    /** Remove signed URLs written by earlier builds; this file contains no other app settings. */
    private fun clearLegacyPersistedStreamUrls() {
        getSharedPreferences(STREAM_SOURCE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

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
        cancelCurrentStreamCompletion()
        // A new service/session must never inherit a timer from a destroyed playback lifecycle.
        cancelSleepTimer()
        scope.cancel()
        mediaSession.run { player.release(); release() }
        // StreamAudioCacheStore intentionally retains the process-wide SimpleCache. A cancelled
        // CacheWriter can therefore finish closing on IO without blocking this main-thread
        // callback, and a same-process service restart reuses the existing directory lock.
        super.onDestroy()
    }
}
