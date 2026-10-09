package com.litemusic.player

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import com.litemusic.shared.player.PlayPhase
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.player.QueueItem

internal fun playbackMediaKey(item: QueueItem): String = item.id.toString() + "#" + item.quality.br

/** A forced URL refresh belongs to one direct song selection, not to a song forever. */
internal data class UrlRefreshAttempt(
    val songId: Long,
    val selectionGeneration: Long,
)

/** A direct MediaController command is safe only when it still addresses the UI's current item. */
internal fun canControlActiveMedia(playerMediaKey: String?, current: QueueItem?): Boolean =
    current != null && playerMediaKey == playbackMediaKey(current)

/** Ignore a delayed ExoPlayer callback when it belongs to the item selected before the current one. */
internal fun isCurrentMediaEvent(playerMediaKey: String?, currentMediaKey: String?): Boolean =
    playerMediaKey != null && playerMediaKey == currentMediaKey

/** A direct re-selection may keep the same media key, so recovery also owns a generation. */
internal fun isCurrentSelection(
    expectedMediaKey: String,
    expectedSelectionGeneration: Long,
    currentMediaKey: String?,
    currentSelectionGeneration: Long,
): Boolean = expectedMediaKey == currentMediaKey &&
    expectedSelectionGeneration == currentSelectionGeneration

/** Loading still represents a pending play request, so its toggle action is cancellation. */
internal fun toggleCancelsPlayback(phase: PlayPhase): Boolean =
    phase == PlayPhase.PLAYING || phase == PlayPhase.LOADING

/** A selection token, unlike LOADING, cannot be produced by an ordinary buffering callback. */
internal fun shouldRestartSelectedCurrentMedia(
    selectionGeneration: Long,
    lastConsumedSelectionGeneration: Long,
    phase: PlayPhase,
): Boolean = selectionGeneration > lastConsumedSelectionGeneration &&
    (phase == PlayPhase.LOADING || phase == PlayPhase.PLAYING)

/** An old item's delayed internal pause must not cancel the next item's play intent. */
internal fun shouldPauseForStaleMediaEvent(playWhenReady: Boolean, reason: Int): Boolean =
    !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS

/** End-of-item navigation honors state-machine intent, so an earlier focus/pause event wins. */
internal fun shouldAdvanceAfterEnded(phase: PlayPhase): Boolean =
    phase == PlayPhase.PLAYING || phase == PlayPhase.LOADING

/** ExoPlayer owns one item; adjacent duplicate queue entries need an explicit restart. */
internal fun shouldRestartEndedMedia(currentMediaKey: String?, nextMediaKey: String?): Boolean =
    currentMediaKey != null && currentMediaKey == nextMediaKey

/** A duplicate Play must not turn an already-playing UI back into a spinner. */
internal fun shouldRestorePlaybackIntentFromExternalPlay(phase: PlayPhase): Boolean = when (phase) {
    PlayPhase.IDLE, PlayPhase.READY, PlayPhase.PAUSED, PlayPhase.ERROR -> true
    PlayPhase.LOADING, PlayPhase.PLAYING -> false
}

/** Do not send a system Play to the stale media item while a new selection resolves. */
internal fun canForwardExternalPlayToInstalledMedia(
    installedMediaKey: String?,
    current: QueueItem?,
): Boolean = installedMediaKey != null && current != null && installedMediaKey == playbackMediaKey(current)

/**
 * Not-playing describes the engine, not a pause request: buffering, focus suppression and
 * natural completion all produce it. Pause intent comes from the reason-bearing
 * playWhenReady callback or the session command, so an early false callback cannot cancel NEXT.
 */
internal fun synchronizeNotPlayingObservation(
    stateMachine: PlayerStateMachine,
    playWhenReady: Boolean,
    playbackState: Int,
    suppressInternalPause: Boolean,
) {
    if (suppressInternalPause || !playWhenReady || !shouldPlayForPhase(stateMachine.state.value.phase)) return
    if (playbackState == Player.STATE_BUFFERING) {
        stateMachine.onLoading()
    }
}

/** The last item stays installed in STATE_ENDED, which needs an explicit rewind before Play. */
internal fun shouldRestartEndedInstalledMedia(playbackState: Int, canControlInstalledMedia: Boolean): Boolean =
    playbackState == Player.STATE_ENDED && canControlInstalledMedia

/** A same-key explicit selection needs a fresh prepare when ExoPlayer has reached the end. */
internal fun shouldPrepareForExplicitCurrentSelection(playbackState: Int): Boolean =
    playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED

/**
 * Keep Media3's diagnostic code in the log, while giving a listener a concrete next step.
 * A partial cache deliberately does not count as an offline download: it can sustain the
 * current stream for a while, but cannot guarantee a new seek or a later restart.
 */
internal fun playbackFailureMessage(
    errorCode: Int?,
    hasValidatedNetwork: Boolean,
    hasCompleteCache: Boolean,
): String {
    if (!hasValidatedNetwork && !hasCompleteCache) {
        return "当前无网络，且这首歌未完整缓存，暂时无法播放"
    }
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> "网络连接异常，请检查网络后重试"

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
        -> "播放地址已失效或歌曲暂不可用，请稍后重试"

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        -> "音频解码失败，请切换音质后重试"

        else -> "播放失败，请稍后重试"
    }
}

/** A direct tap restarts a song; a quality switch or process restore keeps its position. */
internal fun sourceResumePosition(
    explicitSelection: Boolean,
    sameSong: Boolean,
    currentPositionMs: Long,
    restoredPositionMs: Long,
    hasMediaItem: Boolean,
): Long = when {
    explicitSelection -> 0L
    sameSong -> currentPositionMs.coerceAtLeast(0L)
    !hasMediaItem -> restoredPositionMs.coerceAtLeast(0L)
    else -> 0L
}

/** Clear the retained item only when a failed selection still owns state and that item is stale. */
internal fun shouldClearStaleMediaAfterSourceFailure(
    failedMediaKey: String,
    currentMediaKey: String?,
    playerMediaKey: String?,
): Boolean = currentMediaKey == failedMediaKey && playerMediaKey != null && playerMediaKey != failedMediaKey

/** With no player item installed yet, an external pause still cancels the selected item's intent. */
internal fun shouldSynchronizePauseWithoutMedia(
    phase: PlayPhase,
    playWhenReady: Boolean,
    suppressInternalPause: Boolean,
): Boolean = !playWhenReady && !suppressInternalPause && shouldPlayForPhase(phase)

/** A system Play before source installation becomes state-machine intent, not a detached flag. */
internal fun synchronizePlayWithoutMedia(
    stateMachine: PlayerStateMachine,
    suppressInternalPlay: Boolean,
): Boolean {
    if (suppressInternalPlay || stateMachine.state.value.current == null) return false
    stateMachine.onLoading()
    return true
}

/**
 * A MediaSession can receive play commands before the asynchronous queue restore finishes.
 * Keep only the latest command, then consume it once for the restored current selection.
 */
internal class PreRestorePlayIntent {
    private var requested = false

    fun requestPlay() {
        requested = true
    }

    fun cancel() {
        requested = false
    }

    fun consumeForRestoredSelection(stateMachine: PlayerStateMachine): Boolean {
        if (!requested) return false
        requested = false
        if (stateMachine.state.value.current == null) return false
        stateMachine.onLoading()
        return true
    }
}

/** A state-machine phase is the source of truth for whether an async source may auto-play. */
internal fun shouldPlayForPhase(phase: PlayPhase): Boolean = when (phase) {
    PlayPhase.PLAYING, PlayPhase.READY, PlayPhase.LOADING -> true
    PlayPhase.IDLE, PlayPhase.PAUSED, PlayPhase.ERROR -> false
}

/**
 * ExoPlayer can dispatch BUFFERING/READY with playWhenReady=false during source preparation
 * or before ENDED. Preserve the app's durable play intent; a real pause is already recorded by
 * QueueSessionPlayer or the reason-bearing playWhenReady callback.
 */
internal fun phaseForCurrentPlaybackState(
    phaseBeforeCallback: PlayPhase,
    playbackState: Int,
    playWhenReady: Boolean,
): PlayPhase? = when (playbackState) {
    Player.STATE_BUFFERING -> if (playWhenReady || shouldPlayForPhase(phaseBeforeCallback)) {
        PlayPhase.LOADING
    } else {
        PlayPhase.PAUSED
    }

    Player.STATE_READY -> when {
        playWhenReady -> PlayPhase.PLAYING
        shouldPlayForPhase(phaseBeforeCallback) -> phaseBeforeCallback
        else -> PlayPhase.PAUSED
    }
    else -> null
}

/** A resolved stream must still target the current queue item and retain play intent. */
internal fun canApplyResolvedSource(
    resolvedMediaKey: String,
    currentMediaKey: String?,
    phase: PlayPhase,
): Boolean = resolvedMediaKey == currentMediaKey && shouldPlayForPhase(phase)

/**
 * A notification/controller pause can change only playWhenReady while the player is already
 * buffering, so onIsPlayingChanged(false) is not guaranteed to follow. Keep the state-machine
 * intent in sync before a pending URL result decides whether it may auto-play.
 */
internal fun synchronizePauseFromPlayWhenReady(
    stateMachine: PlayerStateMachine,
    playWhenReady: Boolean,
    suppressInternalPause: Boolean,
): Boolean {
    if (playWhenReady || suppressInternalPause || stateMachine.state.value.current == null) return false
    stateMachine.onPaused()
    return true
}
