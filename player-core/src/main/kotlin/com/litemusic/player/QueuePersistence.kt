package com.litemusic.player

import com.litemusic.data.db.PlaybackDao
import com.litemusic.data.db.PlaybackEntity
import com.litemusic.shared.player.PlayerStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 播放队列持久化（FuoEvolve 优点）：进程被杀后重启自动恢复
 * 当前歌曲、播放队列、进度、播放模式。
 */
class QueuePersistence(
    private val dao: PlaybackDao,
    private val stateMachine: PlayerStateMachine,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun restore() {
        val entity = dao.observe().first() ?: return
        runCatching {
            val snapshot = json.decodeFromString(com.litemusic.shared.player.QueueSnapshot.serializer(), entity.snapshotJson)
            stateMachine.restore(snapshot)
        }
    }

    suspend fun save() {
        val snapshot = stateMachine.snapshot()
        if (snapshot.items.isEmpty()) return
        dao.save(
            PlaybackEntity(
                id = 0,
                snapshotJson = json.encodeToString(com.litemusic.shared.player.QueueSnapshot.serializer(), snapshot),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}
