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
    fun previousRespectsPosition() = runTest {
        val sm = PlayerStateMachine()
        sm.setQueue(items(3), 1)
        sm.updatePosition(5000L)
        assertEquals(1, sm.previousIndex()) // 超过 3 秒回到当前曲目开头
        sm.updatePosition(1000L)
        assertEquals(0, sm.previousIndex())
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
