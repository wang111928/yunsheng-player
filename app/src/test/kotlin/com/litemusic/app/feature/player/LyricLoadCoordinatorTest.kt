package com.litemusic.app.feature.player

import com.litemusic.shared.player.QueueItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricLoadCoordinatorTest {
    @Test
    fun lateCompletionFromPreviousSongDoesNotReplaceCurrentLyrics() = runBlocking {
        val first = QueueItem(1L, "第一首", "歌手")
        val second = QueueItem(2L, "第二首", "歌手")
        val pending = mapOf(
            first.lyricSourceKey() to CompletableDeferred<String>(),
            second.lyricSourceKey() to CompletableDeferred<String>(),
        )
        var shown: String? = "旧歌词"
        val coordinator = LyricLoadCoordinator(
            scope = this,
            load = { item -> withContext(NonCancellable) { pending.getValue(item.lyricSourceKey()).await() } },
            show = { _, value -> shown = value },
        )

        try {
            coordinator.updateCurrent(first)
            yield()
            val firstJob = coroutineContext[Job]!!.children.single()
            coordinator.updateCurrent(second)
            yield()
            pending.getValue(first.lyricSourceKey()).complete("第一首歌词")
            withTimeout(1_000) { firstJob.join() }

            assertNull(shown)

            pending.getValue(second.lyricSourceKey()).complete("第二首歌词")
            withTimeout(1_000) { while (shown != "第二首歌词") yield() }

            assertEquals("第二首歌词", shown)
        } finally {
            pending.values.forEach { it.complete("") }
        }
    }

    @Test
    fun lateFailureFromPreviousSongDoesNotClearCurrentLyrics() = runBlocking {
        val first = QueueItem(1L, "第一首", "歌手")
        val second = QueueItem(2L, "第二首", "歌手")
        val firstResponse = CompletableDeferred<String?>()
        val secondResponse = CompletableDeferred<String?>()
        var shown: String? = "旧歌词"
        val coordinator = LyricLoadCoordinator(
            scope = this,
            load = { item ->
                withContext(NonCancellable) {
                    if (item.lyricSourceKey() == first.lyricSourceKey()) firstResponse.await() else secondResponse.await()
                }
            },
            show = { _, value -> shown = value },
        )

        try {
            coordinator.updateCurrent(first)
            yield()
            val firstJob = coroutineContext[Job]!!.children.single()
            coordinator.updateCurrent(second)
            yield()
            secondResponse.complete("第二首歌词")
            withTimeout(1_000) { while (shown != "第二首歌词") yield() }

            assertEquals("第二首歌词", shown)

            firstResponse.complete(null)
            withTimeout(1_000) { firstJob.join() }

            assertEquals("第二首歌词", shown)
        } finally {
            firstResponse.complete(null)
            secondResponse.complete(null)
        }
    }

    @Test
    fun qualityChangeForSameSongDoesNotStartAnotherLyricLoad() = runBlocking {
        val highQuality = QueueItem(1L, "同一首", "歌手")
        val changedQuality = highQuality.copy(quality = com.litemusic.shared.util.Quality.EXHIGH)
        val response = CompletableDeferred<String>()
        var loads = 0
        val coordinator = LyricLoadCoordinator(
            scope = this,
            load = {
                loads += 1
                response.await()
            },
            show = { _, _ -> },
        )

        try {
            coordinator.updateCurrent(highQuality)
            withTimeout(1_000) { while (loads < 1) yield() }
            coordinator.updateCurrent(changedQuality)
            yield()

            assertEquals(1, loads)
            response.complete("歌词")
            yield()
        } finally {
            response.complete("")
        }
    }

    @Test
    fun clearingCurrentSongCancelsPendingLoadAndClearsLyrics() = runBlocking {
        val song = QueueItem(1L, "待加载", "歌手")
        val response = CompletableDeferred<String>()
        var shown: String? = "上一首歌词"
        val coordinator = LyricLoadCoordinator(
            scope = this,
            load = { response.await() },
            show = { _, value -> shown = value },
        )

        try {
            coordinator.updateCurrent(song)
            yield()
            coordinator.updateCurrent(null)
            response.complete("迟到的歌词")
            yield()

            assertNull(shown)
        } finally {
            response.complete("")
        }
    }

    @Test
    fun localSongDispatchesItsOwnSnapshotWithoutRequestingOnlineLyrics() = runBlocking {
        val local = QueueItem(-1L, "本地歌曲", "歌手", localPath = "/music/local.mp3")
        var loads = 0
        var shown: String? = "上一首歌词"
        val coordinator = LyricLoadCoordinator(
            scope = this,
            load = { item ->
                loads += 1
                assertEquals(local, item)
                "本地歌词"
            },
            show = { _, value -> shown = value },
        )

        coordinator.updateCurrent(local)
        withTimeout(1_000) { while (shown != "本地歌词") yield() }

        assertEquals(1, loads)
        assertEquals("本地歌词", shown)
    }

    @Test
    fun returningToSongStartsFreshRequestAndRejectsItsEarlierRequest() = runBlocking {
        val first = QueueItem(1L, "第一首", "歌手")
        val second = QueueItem(2L, "第二首", "歌手")
        val firstRequest = CompletableDeferred<String>()
        val secondRequest = CompletableDeferred<String>()
        val returnedFirstRequest = CompletableDeferred<String>()
        var firstLoads = 0
        var shown: String? = null
        val coordinator = LyricLoadCoordinator(
            scope = this,
            load = { item ->
                when (item.lyricSourceKey()) {
                    first.lyricSourceKey() -> if (firstLoads++ == 0) {
                        withContext(NonCancellable) { firstRequest.await() }
                    } else {
                        returnedFirstRequest.await()
                    }
                    second.lyricSourceKey() -> secondRequest.await()
                    else -> error("unexpected lyric source")
                }
            },
            show = { _, value -> shown = value },
        )

        try {
            coordinator.updateCurrent(first)
            yield()
            val firstJob = coroutineContext[Job]!!.children.single()
            coordinator.updateCurrent(second)
            yield()
            coordinator.updateCurrent(first)
            yield()
            firstRequest.complete("第一轮第一首歌词")
            secondRequest.complete("第二首歌词")
            withTimeout(1_000) { firstJob.join() }

            assertNull(shown)

            returnedFirstRequest.complete("第二轮第一首歌词")
            withTimeout(1_000) { while (shown != "第二轮第一首歌词") yield() }

            assertEquals("第二轮第一首歌词", shown)
        } finally {
            firstRequest.complete("")
            secondRequest.complete("")
            returnedFirstRequest.complete("")
        }
    }

    @Test
    fun localCompletionCannotReplaceTheNewOnlineSong() = runBlocking {
        val local = QueueItem(-1L, "本地", "歌手", localPath = "/music/local.mp3")
        val online = QueueItem(2L, "在线", "歌手")
        val localResponse = CompletableDeferred<String>()
        val onlineResponse = CompletableDeferred<String>()
        var shown: String? = null
        val coordinator = LyricLoadCoordinator(
            scope = this,
            load = { item ->
                withContext(NonCancellable) {
                    if (item.isLocal) localResponse.await() else onlineResponse.await()
                }
            },
            show = { _, value -> shown = value },
        )

        try {
            coordinator.updateCurrent(local)
            yield()
            val localJob = coroutineContext[Job]!!.children.single()
            coordinator.updateCurrent(online)
            yield()
            localResponse.complete("过期本地歌词")
            withTimeout(1_000) { localJob.join() }
            assertNull(shown)

            onlineResponse.complete("在线歌词")
            withTimeout(1_000) { while (shown != "在线歌词") yield() }
        } finally {
            localResponse.complete("")
            onlineResponse.complete("")
        }
    }

    @Test
    fun successfulImportForCurrentSourceStartsNewSequence() = runBlocking {
        val local = QueueItem(-1L, "本地", "歌手", localPath = "/music/local.mp3")
        val first = CompletableDeferred<String>()
        val imported = CompletableDeferred<String>()
        var loads = 0
        var shown: String? = null
        val coordinator = LyricLoadCoordinator(
            scope = this,
            load = {
                if (loads++ == 0) withContext(NonCancellable) { first.await() }
                else imported.await()
            },
            show = { _, value -> shown = value },
        )

        try {
            coordinator.updateCurrent(local)
            withTimeout(1_000) { while (loads < 1) yield() }
            assertEquals(true, coordinator.reloadIfCurrent(local))
            withTimeout(1_000) { while (loads < 2) yield() }
            first.complete("旧歌词")
            imported.complete("导入歌词")
            withTimeout(1_000) { while (shown != "导入歌词") yield() }
            assertEquals("导入歌词", shown)
        } finally {
            first.complete("")
            imported.complete("")
        }
    }
}
