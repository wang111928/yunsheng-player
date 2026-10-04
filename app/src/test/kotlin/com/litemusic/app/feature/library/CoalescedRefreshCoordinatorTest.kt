package com.litemusic.app.feature.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoalescedRefreshCoordinatorTest {
    @Test
    fun requestWhileRefreshIsGated_runsOneFollowUpAfterTheCurrentSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstReadGate = CompletableDeferred<Unit>()
        var source = "旧歌单"
        val reads = mutableListOf<String>()
        val coordinator = CoalescedRefreshCoordinator(this, dispatcher) {
            reads += source
            if (reads.size == 1) firstReadGate.await()
        }

        coordinator.request()
        advanceUntilIdle()
        coordinator.request()
        coordinator.request()
        source = "新歌单"
        firstReadGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("旧歌单", "新歌单"), reads)
    }

    @Test
    fun changingGenerationPreventsAnOldPendingRefreshFromRunningAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val oldGate = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        val coordinator = CoalescedRefreshCoordinator(this, dispatcher) {
            reads += "refresh-${reads.size + 1}"
            oldGate.await()
        }

        coordinator.request()
        advanceUntilIdle()
        coordinator.request()
        coordinator.invalidate()
        coordinator.request()
        advanceUntilIdle()

        assertEquals(listOf("refresh-1", "refresh-2"), reads)
        oldGate.complete(Unit)
        coordinator.close()
    }
}
