package com.litemusic.app.feature.playlist.importing

import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistImportWriterTest {
    @Test fun partialFailureRetryRefreshesTargetAndWritesOnlyMissingSongs() = runTest {
        val rows = (1L..101L).map { ImportedSongMatch(ImportedSongQuery("歌曲$it"), Song(id = it), 100) }
        val target = mutableSetOf<Long>()
        val writes = mutableListOf<List<Long>>()
        var reads = 0
        suspend fun attempt(failLast: Boolean) = writePlaylistImport(
            rows, (1L..101L).toSet(),
            readExisting = { reads++; AppResult.Success(target.toSet()) },
            writeBatch = { ids ->
                writes += ids
                if (failLast && ids == listOf(101L)) AppResult.Failure(500, "模拟服务端失败")
                else { target.addAll(ids); AppResult.Success(Unit) }
            },
        )
        assertEquals(PlaylistImportWriteResult(100, 1), (attempt(true) as AppResult.Success).data)
        assertEquals(PlaylistImportWriteResult(1, 0), (attempt(false) as AppResult.Success).data)
        assertEquals(listOf(100, 1, 1), writes.map { it.size })
        assertEquals(2, reads)
        assertEquals(101, target.size)
    }

    @Test fun unreadableTargetStopsBeforeAnyWrite() = runTest {
        var writes = 0
        val result = writePlaylistImport(
            listOf(ImportedSongMatch(ImportedSongQuery("歌曲"), Song(id = 1), 100)), setOf(1L),
            readExisting = { AppResult.Failure(403, "目标无法读取") },
            writeBatch = { writes++; AppResult.Success(Unit) },
        )
        assertEquals(403, (result as AppResult.Failure).code)
        assertEquals(0, writes)
    }
}
