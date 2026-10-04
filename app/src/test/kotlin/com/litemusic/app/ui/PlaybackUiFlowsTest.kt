package com.litemusic.app.ui

import com.litemusic.shared.player.PlayPhase
import com.litemusic.shared.player.PlayerUiState
import com.litemusic.shared.player.QueueItem
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackUiFlowsTest {
    @Test fun progressAndBufferTicksDoNotInvalidatePlaybackMetadata() = runTest {
        val initial = PlayerUiState(
            phase = PlayPhase.PLAYING,
            queue = listOf(QueueItem(1, "Track", "Artist")),
            currentIndex = 0,
            durationMs = 180_000,
        )
        val frames = listOf(initial, initial.copy(positionMs = 250), initial.copy(positionMs = 500, bufferedMs = 20_000))
            .asFlow().playbackMetadata().toList()
        assertEquals(listOf(initial), frames)
    }

    @Test fun pauseTrackDurationAndErrorChangesStillReachPlaybackControls() = runTest {
        val initial = PlayerUiState(
            phase = PlayPhase.PLAYING,
            queue = listOf(QueueItem(1, "Track", "Artist"), QueueItem(2, "Next", "Artist")),
            currentIndex = 0,
            positionMs = 1_000,
        )
        val updates = listOf(initial, initial.copy(phase = PlayPhase.PAUSED),
            initial.copy(currentIndex = 1), initial.copy(durationMs = 200_000),
            initial.copy(phase = PlayPhase.ERROR, error = "Unavailable"))
        val frames = updates.asFlow().playbackMetadata().toList()
        assertEquals(5, frames.size)
        assertEquals(PlayPhase.PAUSED, frames[1].phase)
        assertEquals(2L, frames[2].current?.id)
        assertEquals(200_000L, frames[3].durationMs)
        assertEquals("Unavailable", frames[4].error)
    }
}
