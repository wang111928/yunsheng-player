package com.litemusic.player

import com.litemusic.shared.player.PlayPhase
import com.litemusic.shared.player.PlayerStateMachine

/** A state-machine phase is the source of truth for whether an async source may auto-play. */
internal fun shouldPlayForPhase(phase: PlayPhase): Boolean = when (phase) {
    PlayPhase.PLAYING, PlayPhase.READY, PlayPhase.LOADING -> true
    PlayPhase.IDLE, PlayPhase.PAUSED, PlayPhase.ERROR -> false
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
