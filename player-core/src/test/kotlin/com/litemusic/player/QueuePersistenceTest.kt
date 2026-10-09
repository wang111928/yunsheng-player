package com.litemusic.player

import com.litemusic.data.db.PlaybackDao
import com.litemusic.data.db.PlaybackEntity
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.player.QueueSnapshot
import com.litemusic.shared.player.PlayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePersistenceTest {
    @Test
    fun restoresQueueAndPositionBeforeThePlayerServiceMapsItToMediaItems() = runBlocking {
        val source = PlayerStateMachine().also {
            it.setQueue(listOf(QueueItem(42, "cached", "artist")))
            it.playIndex(0)
            it.updatePosition(12_345)
        }
        val snapshotJson = Json.encodeToString(
            com.litemusic.shared.player.QueueSnapshot.serializer(),
            source.snapshot(),
        )
        val dao = FakePlaybackDao(PlaybackEntity(snapshotJson = snapshotJson, updatedAt = 1))
        val target = PlayerStateMachine()

        assertTrue(QueuePersistence(dao, target).restore())
        assertEquals(42L, target.state.value.current?.id)
        assertEquals(12_345L, target.state.value.positionMs)
    }

    @Test
    fun emptyBootstrapStateNeverOverwritesAnExistingSnapshot() = runBlocking {
        val existing = PlaybackEntity(snapshotJson = "saved", updatedAt = 1)
        val dao = FakePlaybackDao(existing)

        QueuePersistence(dao, PlayerStateMachine()).save()

        assertEquals(existing, dao.current.value)
        assertFalse(dao.saved)
    }

    @Test
    fun clearingRestoredQueuePersistsAnEmptyTombstone() = runBlocking {
        val original = QueueSnapshot(listOf(QueueItem(42, "cached", "artist")), 0, 12_345, PlayMode.SEQUENCE)
        val dao = FakePlaybackDao(PlaybackEntity(snapshotJson = Json.encodeToString(QueueSnapshot.serializer(), original), updatedAt = 1))
        val machine = PlayerStateMachine()
        val persistence = QueuePersistence(dao, machine)
        assertTrue(persistence.restore())

        val observer = launch(start = CoroutineStart.UNDISPATCHED) { persistence.observeAndSave() }
        machine.setQueue(emptyList())
        yield()
        observer.cancelAndJoin()

        assertEquals(emptyList<QueueItem>(), Json.decodeFromString(QueueSnapshot.serializer(), dao.current.value!!.snapshotJson).items)
        assertTrue(QueuePersistence(dao, PlayerStateMachine()).restore())
    }

    @Test
    fun queueWithoutCurrentSongIsRestoredWithoutDiscardingItsItems() = runBlocking {
        val snapshot = QueueSnapshot(listOf(QueueItem(42, "cached", "artist")), -1, 0, PlayMode.SEQUENCE)
        val dao = FakePlaybackDao(PlaybackEntity(snapshotJson = Json.encodeToString(QueueSnapshot.serializer(), snapshot), updatedAt = 1))
        val machine = PlayerStateMachine()

        assertTrue(QueuePersistence(dao, machine).restore())
        assertEquals(1, machine.state.value.queue.size)
        assertEquals(-1, machine.state.value.currentIndex)
    }

    private class FakePlaybackDao(initial: PlaybackEntity?) : PlaybackDao {
        val current = MutableStateFlow(initial)
        var saved = false

        override fun observe(): Flow<PlaybackEntity?> = current

        override suspend fun save(entity: PlaybackEntity) {
            saved = true
            current.value = entity
        }
    }
}
