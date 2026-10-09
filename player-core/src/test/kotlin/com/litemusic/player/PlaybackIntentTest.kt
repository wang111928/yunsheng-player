package com.litemusic.player

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import com.litemusic.shared.player.PlayPhase
import com.litemusic.shared.player.PlayMode
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.player.QueueSnapshot
import com.litemusic.shared.player.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIntentTest {
    @Test
    fun offlineWithoutACompleteCacheExplainsWhyPlaybackCannotStart() {
        assertEquals(
            "当前无网络，且这首歌未完整缓存，暂时无法播放",
            playbackFailureMessage(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                hasValidatedNetwork = false,
                hasCompleteCache = false,
            ),
        )
    }

    @Test
    fun playbackFailuresAreShownAsActionsInsteadOfMedia3Codes() {
        assertEquals(
            "网络连接异常，请检查网络后重试",
            playbackFailureMessage(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                hasValidatedNetwork = true,
                hasCompleteCache = false,
            ),
        )
        assertEquals(
            "播放地址已失效或歌曲暂不可用，请稍后重试",
            playbackFailureMessage(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                hasValidatedNetwork = true,
                hasCompleteCache = false,
            ),
        )
        assertEquals(
            "音频解码失败，请切换音质后重试",
            playbackFailureMessage(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                hasValidatedNetwork = true,
                hasCompleteCache = true,
            ),
        )
    }

    @Test
    fun pausedStateNeverStartsPlaybackWhileThePlayerIsBuffering() {
        assertFalse(shouldPlayForPhase(PlayPhase.PAUSED))
    }

    @Test
    fun aLoadingCurrentItemCanStartPlayback() {
        assertTrue(shouldPlayForPhase(PlayPhase.LOADING))
    }

    @Test
    fun sourcePreparationBufferingKeepsAutomaticNextTrackInLoading() {
        assertEquals(
            PlayPhase.LOADING,
            phaseForCurrentPlaybackState(
                phaseBeforeCallback = PlayPhase.LOADING,
                playbackState = Player.STATE_BUFFERING,
                playWhenReady = false,
            ),
        )
    }

    @Test
    fun sourcePreparationReadyKeepsAutomaticNextTrackInLoading() {
        assertEquals(
            PlayPhase.LOADING,
            phaseForCurrentPlaybackState(
                phaseBeforeCallback = PlayPhase.LOADING,
                playbackState = Player.STATE_READY,
                playWhenReady = false,
            ),
        )
    }

    @Test
    fun playbackStateWithoutAPlayRequestChangeKeepsActiveIntentBeforeEnded() {
        assertEquals(
            PlayPhase.LOADING,
            phaseForCurrentPlaybackState(PlayPhase.PLAYING, Player.STATE_BUFFERING, false),
        )
        assertEquals(
            PlayPhase.PLAYING,
            phaseForCurrentPlaybackState(PlayPhase.PLAYING, Player.STATE_READY, false),
        )
        assertEquals(
            PlayPhase.PAUSED,
            phaseForCurrentPlaybackState(PlayPhase.PAUSED, Player.STATE_READY, false),
        )
    }

    @Test
    fun automaticNextTrackCanStillApplyItsResolvedSourceAfterPrepareBuffering() {
        val stateMachine = PlayerStateMachine().also {
            it.setQueueAndPlay(listOf(QueueItem(1, "ending", "artist"), QueueItem(2, "next", "artist")))
            it.playIndex(1)
        }

        when (phaseForCurrentPlaybackState(
            phaseBeforeCallback = stateMachine.state.value.phase,
            playbackState = Player.STATE_BUFFERING,
            playWhenReady = false,
        )) {
            PlayPhase.LOADING -> stateMachine.onLoading()
            PlayPhase.PAUSED -> stateMachine.onPaused()
            else -> error("prepare buffering must produce a playback phase")
        }

        assertTrue(canApplyResolvedSource("2#320000", "2#320000", stateMachine.state.value.phase))
    }

    @Test
    fun userPauseDuringBufferingRemainsPaused() {
        assertEquals(
            PlayPhase.PAUSED,
            phaseForCurrentPlaybackState(
                phaseBeforeCallback = PlayPhase.PAUSED,
                playbackState = Player.STATE_BUFFERING,
                playWhenReady = false,
            ),
        )
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

    @Test
    fun anOldPlayerItemCannotBeDirectlyPlayedForANewSelection() {
        val current = QueueItem(2, "new song", "artist")

        assertFalse(canControlActiveMedia("1#320000", current))
        assertTrue(canControlActiveMedia("2#192000", current))
    }

    @Test
    fun anOldPlayerCallbackCannotChangeTheNewSelectionsUiState() {
        assertFalse(isCurrentMediaEvent("1#320000", "2#320000"))
        assertTrue(isCurrentMediaEvent("2#320000", "2#320000"))
        assertFalse(isCurrentMediaEvent(null, "2#320000"))
    }

    @Test
    fun oldFailureRecoveryCannotOverwriteAReselectedSameMediaKey() {
        assertFalse(isCurrentSelection("2#320000", 4, "2#320000", 5))
        assertTrue(isCurrentSelection("2#320000", 5, "2#320000", 5))
        assertFalse(isCurrentSelection("2#320000", 5, "3#320000", 5))
    }

    @Test
    fun staleSelectionsDoNotConsumeTheNewSelectionsForcedUrlRefresh() {
        val attempted = mutableSetOf(UrlRefreshAttempt(songId = 2, selectionGeneration = 4))

        assertTrue(attempted.add(UrlRefreshAttempt(songId = 2, selectionGeneration = 5)))
        assertFalse(attempted.add(UrlRefreshAttempt(songId = 2, selectionGeneration = 5)))
    }

    @Test
    fun explicitSelectionRestartsEvenWhenTheSameSongChangesQuality() {
        assertEquals(0L, sourceResumePosition(true, true, 41_000L, 12_000L, true))
        assertEquals(41_000L, sourceResumePosition(false, true, 41_000L, 12_000L, true))
        assertEquals(12_000L, sourceResumePosition(false, false, 0L, 12_000L, false))
    }

    @Test
    fun anExternalPauseCancelsPlaybackRequestedForAnItemStillResolving() {
        val stateMachine = PlayerStateMachine().also {
            it.setQueue(listOf(QueueItem(1, "old", "artist"), QueueItem(2, "new", "artist")))
            it.playIndex(1)
        }

        assertTrue(synchronizePauseFromPlayWhenReady(stateMachine, playWhenReady = false, suppressInternalPause = false))
        assertEquals(PlayPhase.PAUSED, stateMachine.state.value.phase)
        assertFalse(canApplyResolvedSource("2#192000", "2#192000", stateMachine.state.value.phase))
    }

    @Test
    fun oldItemsDelayedInternalPauseDoesNotCancelAutomaticNextSong() {
        val stateMachine = PlayerStateMachine().also {
            it.setQueueAndPlay(listOf(QueueItem(1, "old", "artist"), QueueItem(2, "next", "artist")))
            it.playIndex(1)
        }

        val staleInternalPause = shouldPauseForStaleMediaEvent(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        )
        if (staleInternalPause) stateMachine.onPaused()

        assertFalse(staleInternalPause)
        assertEquals(PlayPhase.LOADING, stateMachine.state.value.phase)
        // A real lock-screen pause reaches QueueSessionPlayer.pause() and must still win.
        stateMachine.onPaused()
        assertEquals(PlayPhase.PAUSED, stateMachine.state.value.phase)
        assertTrue(shouldPauseForStaleMediaEvent(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
    }

    @Test
    fun endedTrackAdvancesOnlyWhilePlaybackIntentRemainsActive() {
        assertTrue(shouldAdvanceAfterEnded(PlayPhase.PLAYING))
        assertTrue(shouldAdvanceAfterEnded(PlayPhase.LOADING))
        assertFalse(shouldAdvanceAfterEnded(PlayPhase.PAUSED))
    }

    @Test
    fun adjacentDuplicateMediaNeedsAnExplicitRestart() {
        assertTrue(shouldRestartEndedMedia("7#320000", "7#320000"))
        assertFalse(shouldRestartEndedMedia("7#320000", "8#320000"))
        assertFalse(shouldRestartEndedMedia(null, "7#320000"))
    }

    @Test
    fun duplicateExternalPlayKeepsThePlayingStateButPausedStateCanResume() {
        assertFalse(shouldRestorePlaybackIntentFromExternalPlay(PlayPhase.PLAYING))
        assertFalse(shouldRestorePlaybackIntentFromExternalPlay(PlayPhase.LOADING))
        assertTrue(shouldRestorePlaybackIntentFromExternalPlay(PlayPhase.PAUSED))
        assertTrue(shouldRestorePlaybackIntentFromExternalPlay(PlayPhase.ERROR))
    }

    @Test
    fun externalPlayDoesNotTargetAnInstalledOldItemWhileNewSelectionResolves() {
        val selected = QueueItem(2, "new", "artist")
        assertFalse(canForwardExternalPlayToInstalledMedia("1#192000", selected))
        assertTrue(canForwardExternalPlayToInstalledMedia("2#192000", selected))
    }

    @Test
    fun notPlayingBeforeEndedStillAdvancesTheQueue() {
        for (reportedState in listOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_ENDED)) {
            val machine = PlayerStateMachine().also {
                it.setQueueAndPlay(listOf(QueueItem(1, "ending", "artist"), QueueItem(2, "next", "artist")))
                it.onPlaying()
            }
            synchronizeNotPlayingObservation(machine, false, reportedState, suppressInternalPause = false)
            if (shouldAdvanceAfterEnded(machine.state.value.phase)) {
                machine.nextIndex()?.let(machine::playIndex)
            }
            assertEquals("not-playing reported state=$reportedState", 1, machine.state.value.currentIndex)
            assertEquals(PlayPhase.LOADING, machine.state.value.phase)
        }
    }

    @Test
    fun notPlayingObservationCannotUndoAnExplicitPauseDuringBuffering() {
        val machine = PlayerStateMachine().also {
            it.setQueueAndPlay(listOf(QueueItem(1, "song", "artist")))
            it.onPlaying()
        }
        synchronizePauseFromPlayWhenReady(machine, false, suppressInternalPause = false)
        synchronizeNotPlayingObservation(machine, true, Player.STATE_BUFFERING, suppressInternalPause = false)
        assertEquals(PlayPhase.PAUSED, machine.state.value.phase)
        assertFalse(shouldAdvanceAfterEnded(machine.state.value.phase))
    }

    @Test
    fun bufferingObservationKeepsAnActiveRequestLoading() {
        val machine = PlayerStateMachine().also {
            it.setQueueAndPlay(listOf(QueueItem(1, "song", "artist")))
            it.onPlaying()
        }
        synchronizeNotPlayingObservation(machine, true, Player.STATE_BUFFERING, suppressInternalPause = false)
        assertEquals(PlayPhase.LOADING, machine.state.value.phase)
    }

    @Test
    fun lockScreenPlayRestartsOnlyTheInstalledCurrentMediaAfterQueueEnd() {
        assertTrue(shouldRestartEndedInstalledMedia(Player.STATE_ENDED, canControlInstalledMedia = true))
        assertFalse(shouldRestartEndedInstalledMedia(Player.STATE_ENDED, canControlInstalledMedia = false))
        assertFalse(shouldRestartEndedInstalledMedia(Player.STATE_READY, canControlInstalledMedia = true))
    }

    @Test
    fun sameKeyExplicitSelectionPreparesAnEndedOrIdlePlayerBeforePlaying() {
        assertTrue(shouldPrepareForExplicitCurrentSelection(Player.STATE_ENDED))
        assertTrue(shouldPrepareForExplicitCurrentSelection(Player.STATE_IDLE))
        assertFalse(shouldPrepareForExplicitCurrentSelection(Player.STATE_READY))
    }

    @Test
    fun loadingToggleCancelsPendingPlaybackInsteadOfRequestingAnotherPlay() {
        assertTrue(toggleCancelsPlayback(PlayPhase.LOADING))
        assertTrue(toggleCancelsPlayback(PlayPhase.PLAYING))
        assertFalse(toggleCancelsPlayback(PlayPhase.PAUSED))
    }

    @Test
    fun onlyANewDirectSelectionCanRestartAnAlreadyInstalledCurrentItem() {
        assertTrue(shouldRestartSelectedCurrentMedia(2, 1, PlayPhase.LOADING))
        assertFalse(shouldRestartSelectedCurrentMedia(2, 2, PlayPhase.LOADING))
        assertFalse(shouldRestartSelectedCurrentMedia(2, 1, PlayPhase.PAUSED))
        assertFalse(shouldRestartSelectedCurrentMedia(2, 1, PlayPhase.ERROR))
    }

    @Test
    fun sourceFailureClearsOnlyAStalePlayerItemForTheStillSelectedSong() {
        assertTrue(shouldClearStaleMediaAfterSourceFailure("2#192000", "2#192000", "1#192000"))
        assertFalse(shouldClearStaleMediaAfterSourceFailure("2#192000", "3#192000", "1#192000"))
        assertFalse(shouldClearStaleMediaAfterSourceFailure("2#192000", "2#192000", "2#192000"))
    }

    @Test
    fun pauseWithoutAMediaItemCancelsAResolvingSelectionsPendingPlayback() {
        assertTrue(shouldSynchronizePauseWithoutMedia(PlayPhase.LOADING, playWhenReady = false, suppressInternalPause = false))
        assertFalse(shouldSynchronizePauseWithoutMedia(PlayPhase.LOADING, playWhenReady = false, suppressInternalPause = true))
        assertFalse(shouldSynchronizePauseWithoutMedia(PlayPhase.PAUSED, playWhenReady = false, suppressInternalPause = false))
    }

    @Test
    fun appPauseWinsWhenItFollowsSystemPlayBeforeTheSourceIsInstalled() {
        val stateMachine = PlayerStateMachine().also {
            it.setQueue(listOf(QueueItem(2, "new", "artist")))
            it.onPaused()
        }

        assertTrue(synchronizePlayWithoutMedia(stateMachine, suppressInternalPlay = false))
        assertEquals(PlayPhase.LOADING, stateMachine.state.value.phase)
        stateMachine.onPaused()

        assertFalse(canApplyResolvedSource("2#192000", "2#192000", stateMachine.state.value.phase))
    }

    @Test
    fun playBeforeQueueRestoreStartsTheRestoredCurrentSong() {
        val intent = PreRestorePlayIntent().also { it.requestPlay() }
        val stateMachine = PlayerStateMachine().also { it.restore(restoredSnapshot()) }

        assertTrue(intent.consumeForRestoredSelection(stateMachine))
        assertEquals(2L, stateMachine.state.value.current?.id)
        assertEquals(PlayPhase.LOADING, stateMachine.state.value.phase)
    }

    @Test
    fun pauseBeforeQueueRestoreCancelsThePendingSystemPlay() {
        val intent = PreRestorePlayIntent().also {
            it.requestPlay()
            it.cancel()
        }
        val stateMachine = PlayerStateMachine().also { it.restore(restoredSnapshot()) }

        assertFalse(intent.consumeForRestoredSelection(stateMachine))
        assertEquals(PlayPhase.PAUSED, stateMachine.state.value.phase)
    }

    @Test
    fun appPauseAfterRestoreWinsOverTheConsumedSystemPlay() {
        val intent = PreRestorePlayIntent().also { it.requestPlay() }
        val stateMachine = PlayerStateMachine().also { it.restore(restoredSnapshot()) }
        assertTrue(intent.consumeForRestoredSelection(stateMachine))

        stateMachine.onPaused()

        assertFalse(canApplyResolvedSource("2#192000", "2#192000", stateMachine.state.value.phase))
    }

    private fun restoredSnapshot() = QueueSnapshot(
        items = listOf(QueueItem(2, "restored", "artist")),
        currentIndex = 0,
        positionMs = 0,
        playMode = PlayMode.SEQUENCE,
    )
}
