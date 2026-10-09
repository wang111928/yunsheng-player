package com.litemusic.player

import com.litemusic.data.db.PlaybackDao
import com.litemusic.data.db.PlaybackEntity
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.player.QueueSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * 播放队列持久化（FuoEvolve 优点）：进程被杀后重启自动恢复
 * 当前歌曲、播放队列、进度、播放模式。
 */
class QueuePersistence(
    private val dao: PlaybackDao,
    private val stateMachine: PlayerStateMachine,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var restoreAttempted = false
    private var loadedSnapshot: QueueSnapshot? = null

    suspend fun restore(): Boolean {
        val entity = dao.observe().first()
        restoreAttempted = true
        if (entity == null) return false
        return runCatching {
            val snapshot = json.decodeFromString(com.litemusic.shared.player.QueueSnapshot.serializer(), entity.snapshotJson)
            val validIndex = snapshot.currentIndex in snapshot.items.indices || snapshot.currentIndex == -1
            if (validIndex) loadedSnapshot = snapshot
            validIndex && stateMachine.restoreIfPristine(snapshot)
        }.getOrDefault(false)
    }

    suspend fun save(snapshot: QueueSnapshot = stateMachine.snapshot()) {
        if (snapshot.items.isEmpty() && !restoreAttempted) return
        dao.save(
            PlaybackEntity(
                id = 0,
                snapshotJson = json.encodeToString(com.litemusic.shared.player.QueueSnapshot.serializer(), snapshot),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Persist structural queue changes immediately and playback position at a modest cadence.
     * This collector must start only after [restore], otherwise the empty bootstrap state could
     * overwrite the saved queue before Room has emitted it.
     */
    suspend fun observeAndSave() {
        // The Room snapshot is the baseline even if a user changed the queue while Room was
        // loading. In particular, a later explicit empty queue must replace the old snapshot.
        var lastSaved: QueueSnapshot? = loadedSnapshot
        var lastSavedAt = 0L
        stateMachine.state.collect {
            val snapshot = stateMachine.snapshot()
            val previous = lastSaved
            if (snapshot.items.isEmpty() && previous == null) return@collect
            val structuralChange = previous == null ||
                previous.items != snapshot.items ||
                previous.currentIndex != snapshot.currentIndex ||
                previous.playMode != snapshot.playMode
            val positionDue = previous != null &&
                abs(snapshot.positionMs - previous.positionMs) >= MIN_POSITION_DELTA_MS &&
                System.currentTimeMillis() - lastSavedAt >= POSITION_SAVE_INTERVAL_MS
            if (structuralChange || positionDue) {
                save(snapshot)
                lastSaved = snapshot
                lastSavedAt = System.currentTimeMillis()
            }
        }
    }

    private companion object {
        const val POSITION_SAVE_INTERVAL_MS = 2_000L
        const val MIN_POSITION_DELTA_MS = 1_000L
    }
}
