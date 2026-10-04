package com.litemusic.app.feature.playlist.importing

import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PlaylistImportWriteResult(val added: Int, val failed: Int)

/** Refresh the target before every attempt; a retry must never rely on an old local snapshot. */
internal suspend fun writePlaylistImport(
    matches: List<ImportedSongMatch>,
    selected: Set<Long>,
    readExisting: suspend () -> AppResult<Set<Long>>,
    writeBatch: suspend (List<Long>) -> AppResult<Unit>,
): AppResult<PlaylistImportWriteResult> {
    val existing = when (val result = readExisting()) {
        is AppResult.Failure -> return result
        is AppResult.Success -> result.data
    }
    var added = 0
    var failed = 0
    for (batch in importBatchPlan(matches, selected, existing)) {
        currentCoroutineContext().ensureActive()
        when (writeBatch(batch)) {
            is AppResult.Success -> added += batch.size
            is AppResult.Failure -> failed += batch.size
        }
    }
    return AppResult.Success(PlaylistImportWriteResult(added, failed))
}
