package com.litemusic.shared.player

import com.litemusic.shared.util.Quality
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerStateMachineTest {

    private fun items(n: Int) = List(n) { QueueItem(it.toLong(), "s" + it, "a", quality = Quality.HIGH) }

    @Test
    fun sequenceStopsAtEnd() = runTest {
        val sm = PlayerStateMachine()
        sm.setQueue(items(3), 0)
        assertEquals(1, sm.nextIndex())
        sm.playIndex(2)
        assertNull(sm.nextIndex())
    }

    @Test
    fun repeatAllWraps() = runTest {
        val sm = PlayerStateMachine()
        sm.setQueue(items(2), 1)
        sm.setPlayMode(PlayMode.REPEAT_ALL)
        assertEquals(0, sm.nextIndex())
    }

    @Test
    fun repeatOneStays() = runTest {
        val sm = PlayerStateMachine()
        sm.setQueue(items(2), 0)
        sm.setPlayMode(PlayMode.REPEAT_ONE)
        assertEquals(0, sm.nextIndex())
    }

    @Test
    fun snapshotRestore() = runTest {
        val sm = PlayerStateMachine()
        sm.setQueue(items(4), 2)
        sm.setPlayMode(PlayMode.REPEAT_ALL)
        sm.updatePosition(12_000)
        val snap = sm.snapshot()
        val sm2 = PlayerStateMachine()
        sm2.restore(snap)
        assertEquals(4, sm2.state.value.queue.size)
        assertEquals(2, sm2.state.value.currentIndex)
        assertEquals(PlayMode.REPEAT_ALL, sm2.state.value.playMode)
        assertEquals(12_000, sm2.state.value.positionMs)
        assertEquals(PlayPhase.PAUSED, sm2.state.value.phase)
    }

    @Test
    fun directQueueSelectionStartsLoadingButRestoredQueueRemainsPaused() = runTest {
        val sm = PlayerStateMachine()

        sm.setQueueAndPlay(items(2), 1)

        assertEquals(1, sm.state.value.currentIndex)
        assertEquals(PlayPhase.LOADING, sm.state.value.phase)

        val restored = PlayerStateMachine()
        restored.restore(sm.snapshot())
        assertEquals(PlayPhase.PAUSED, restored.state.value.phase)
    }

    @Test
    fun bufferingDoesNotLookLikeAnotherDirectSelection() = runTest {
        val sm = PlayerStateMachine()
        sm.setQueueAndPlay(items(1))
        val selection = sm.state.value.selectionGeneration

        sm.onLoading()

        assertEquals(selection, sm.state.value.selectionGeneration)
    }

    @Test
    fun navigatingToAnAdjacentDuplicateStillCreatesANewSelection() = runTest {
        val duplicate = QueueItem(8L, "same", "artist", quality = Quality.HIGH)
        val sm = PlayerStateMachine().also { it.setQueue(listOf(duplicate, duplicate)) }
        val before = sm.state.value.selectionGeneration

        sm.playIndex(1)

        assertEquals(before + 1L, sm.state.value.selectionGeneration)
        assertEquals(1, sm.state.value.currentIndex)
        assertEquals(PlayPhase.LOADING, sm.state.value.phase)
    }

    @Test
    fun previousAlwaysMovesToThePriorQueueItemRegardlessOfPosition() = runTest {
        val sm = PlayerStateMachine()
        sm.setQueue(items(3), 1)
        sm.updatePosition(0L)
        assertEquals(0, sm.previousIndex())
        sm.updatePosition(5000L)
        assertEquals(0, sm.previousIndex())
    }

    @Test
    fun previousKeepsExistingWrapAndShuffleBoundaries() = runTest {
        val sequence = PlayerStateMachine().also { it.setQueue(items(3), 0) }
        assertEquals(2, sequence.previousIndex())

        sequence.setPlayMode(PlayMode.REPEAT_ALL)
        assertEquals(2, sequence.previousIndex())

        sequence.setPlayMode(PlayMode.REPEAT_ONE)
        assertEquals(2, sequence.previousIndex())

        val shuffled = PlayerStateMachine().also { it.setQueue(items(3), 1); it.setPlayMode(PlayMode.SHUFFLE) }
        assertEquals(false, shuffled.previousIndex() == 1)

        val single = PlayerStateMachine().also { it.setQueue(items(1), 0); it.setPlayMode(PlayMode.SHUFFLE) }
        assertEquals(0, single.previousIndex())
    }

    @Test
    fun replacingQueueMetadataKeepsPlaybackState() = runTest {
        val sm = PlayerStateMachine()
        sm.setQueue(items(2), 1)
        sm.onPlaying()
        sm.updatePosition(12_345L, 20_000L)
        sm.replaceItemQuality(1, Quality.LOSSLESS)

        val replaced = sm.replaceQueueMetadata(
            items(2).mapIndexed { index, item -> item.copy(coverUrl = "https://img/$index.jpg") },
        )

        assertEquals(true, replaced)
        assertEquals(1, sm.state.value.currentIndex)
        assertEquals(PlayPhase.PLAYING, sm.state.value.phase)
        assertEquals(12_345L, sm.state.value.positionMs)
        assertEquals(20_000L, sm.state.value.bufferedMs)
        assertEquals("https://img/1.jpg", sm.state.value.current?.coverUrl)
        assertEquals(Quality.LOSSLESS, sm.state.value.current?.quality)
    }

    @Test
    fun replacingQueueMetadataRejectsAnotherQueue() = runTest {
        val sm = PlayerStateMachine()
        sm.setQueue(items(2), 0)

        val replaced = sm.replaceQueueMetadata(
            listOf(QueueItem(99L, "other", "artist")),
        )

        assertEquals(false, replaced)
        assertEquals(listOf(0L, 1L), sm.state.value.queue.map { it.id })
    }
}
