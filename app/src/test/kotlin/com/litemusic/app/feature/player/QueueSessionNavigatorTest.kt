package com.litemusic.app.feature.player

import com.litemusic.player.QueueSessionNavigator
import com.litemusic.shared.player.PlayMode
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.player.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSessionNavigatorTest {
    private fun machine(size: Int = 3, start: Int = 0) = PlayerStateMachine().also { sm ->
        sm.setQueue((0 until size).map { QueueItem(it.toLong(), "song$it", "artist") }, start)
    }

    @Test fun emptyQueueHasNoNavigation() {
        val nav = QueueSessionNavigator(PlayerStateMachine())
        assertFalse(nav.hasNext()); assertFalse(nav.hasPrevious())
        assertFalse(nav.next()); assertFalse(nav.previous())
    }

    @Test fun sequenceStopsAtEndAndMovesPrevious() {
        val sm = machine(start = 2); val nav = QueueSessionNavigator(sm)
        assertFalse(nav.hasNext()); assertTrue(nav.hasPrevious())
        assertTrue(nav.previous()); assertEquals(1, sm.state.value.currentIndex)
    }

    @Test fun repeatOneRestartsCurrentQueueIndex() {
        val sm = machine(start = 1); sm.setPlayMode(PlayMode.REPEAT_ONE)
        assertTrue(QueueSessionNavigator(sm).next()); assertEquals(1, sm.state.value.currentIndex)
    }

    @Test fun repeatAllAndShuffleAlwaysUseStateMachineIndices() {
        val sm = machine(start = 2); sm.setPlayMode(PlayMode.REPEAT_ALL)
        assertTrue(QueueSessionNavigator(sm).next()); assertEquals(0, sm.state.value.currentIndex)
        sm.setPlayMode(PlayMode.SHUFFLE)
        assertTrue(QueueSessionNavigator(sm).next()); assertTrue(sm.state.value.currentIndex in 0..2)
    }

    @Test fun previousAfterThreeSecondsStartsThePriorSong() {
        val sm = machine(start = 1); sm.updatePosition(4_000)
        val generation = sm.state.value.selectionGeneration

        assertTrue(QueueSessionNavigator(sm).previous())

        assertEquals(0, sm.state.value.currentIndex)
        assertEquals(0L, sm.state.value.positionMs)
        assertEquals(com.litemusic.shared.player.PlayPhase.LOADING, sm.state.value.phase)
        assertEquals(generation + 1L, sm.state.value.selectionGeneration)
    }
}
