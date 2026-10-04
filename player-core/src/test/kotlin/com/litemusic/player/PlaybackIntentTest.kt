package com.litemusic.player

import com.litemusic.shared.player.PlayPhase
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.player.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIntentTest {
    @Test
    fun pausedStateNeverStartsPlaybackWhileThePlayerIsBuffering() {
        assertFalse(shouldPlayForPhase(PlayPhase.PAUSED))
    }

    @Test
    fun aLoadingCurrentItemCanStartPlayback() {
        assertTrue(shouldPlayForPhase(PlayPhase.LOADING))
    }

    @Test
    fun aResolvedUrlForAnOldItemCannotApplyToTheCurrentItem() {
        assertFalse(canApplyResolvedSource("old#320000", "new#320000", PlayPhase.PLAYING))
    }

    @Test
    fun anErrorRecoveryRespectsAPauseWhileTheUrlRequestWasPending() {
        assertFalse(canApplyResolvedSource("song#320000", "song#320000", PlayPhase.PAUSED))
    }

    @Test
    fun notificationPauseChangesLoadingIntentToPausedEvenWhenIsPlayingWasAlreadyFalse() {
        val stateMachine = PlayerStateMachine().also { it.setQueue(listOf(QueueItem(1, "song", "artist"))) }
        stateMachine.playIndex(0)

        assertTrue(synchronizePauseFromPlayWhenReady(stateMachine, playWhenReady = false, suppressInternalPause = false))
        assertEquals(PlayPhase.PAUSED, stateMachine.state.value.phase)
        assertFalse(canApplyResolvedSource("1#320000", "1#320000", stateMachine.state.value.phase))
    }

    @Test
    fun internalSourcePauseDoesNotOverwriteTheLoadingIntent() {
        val stateMachine = PlayerStateMachine().also { it.setQueue(listOf(QueueItem(1, "song", "artist"))) }
        stateMachine.playIndex(0)

        assertFalse(synchronizePauseFromPlayWhenReady(stateMachine, playWhenReady = false, suppressInternalPause = true))
        assertEquals(PlayPhase.LOADING, stateMachine.state.value.phase)
    }
}
