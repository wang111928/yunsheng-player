package com.litemusic.app.feature.playlist.importing

import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportSearchCoordinatorTest {
    @Test fun parallelCallersSendOnlyOneRequestAtATimeWithSpacing() = runTest {
        val coordinator = ImportSearchCoordinator()
        val starts = mutableListOf<Long>()
        var active = 0
        var maximumActive = 0
        (1..3).map { index -> async {
            coordinator.search("song$index", 20) {
                starts += testScheduler.currentTime
                maximumActive = maxOf(maximumActive, ++active)
                delay(100)
                active--
                AppResult.Success(emptyList())
            }
        } }.awaitAll()
        assertEquals(listOf(0L, 1600L, 3200L), starts)
        assertEquals(1, maximumActive)
    }

    @Test fun repeatedPreviewsKeepSuccessfulResultsWithoutAnotherRequest() = runTest {
        val coordinator = ImportSearchCoordinator()
        var calls = 0
        suspend fun search() = coordinator.search("same", 20) {
            calls++
            AppResult.Success(listOf(Song(id = 1)))
        }
        val first = search()
        coordinator.clearLimited()
        assertEquals(first, search())
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun businessLimitsStopRemainingRequestsButRetainSuccessfulCache() = runTest {
        for (code in listOf(405, -460, -462)) {
            val coordinator = ImportSearchCoordinator()
            var calls = 0
            coordinator.search("known", 20) { calls++; AppResult.Success(listOf(Song(id = 1))) }
            coordinator.search("blocked", 20) { calls++; AppResult.Failure(code, "limited") }
            assertTrue(coordinator.limited)
            val notSent = coordinator.search("next", 20) { calls++; AppResult.Success(emptyList()) }
            assertTrue(notSent is AppResult.Failure)
            assertTrue(coordinator.search("known", 20) { error("must use cache") } is AppResult.Success)
            assertEquals(2, calls)
            coordinator.clearLimited()
            coordinator.search("next", 20) { calls++; AppResult.Success(emptyList()) }
            assertEquals(3, calls)
        }
    }

    @Test fun cacheIsBoundedAndIncludesTheRequestedPageSize() = runTest {
        val coordinator = ImportSearchCoordinator(0, 2)
        var calls = 0
        suspend fun search(keyword: String, limit: Int = 20) = coordinator.search(keyword, limit) {
            calls++; AppResult.Success(emptyList())
        }
        search("a"); search("b"); search("c")
        search("b")
        assertEquals(3, calls)
        search("a")
        assertEquals(4, calls)
        search("a", 30)
        assertEquals(5, calls)
    }

    @Test fun cancelledRequestReleasesTheQueueWithoutCachingAResult() = runTest {
        val coordinator = ImportSearchCoordinator()
        val job = async { coordinator.search("cancelled", 20) { delay(5000); AppResult.Success(emptyList()) } }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        var called = false
        coordinator.search("cancelled", 20) { called = true; AppResult.Success(emptyList()) }
        assertTrue(called)
        assertFalse(coordinator.limited)
    }
}
