package com.litemusic.app.feature.playlist.importing

import android.content.Context
import com.litemusic.app.util.writeAtomicText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/** Durable account-local snapshots; no OAuth data or credentials are saved. */
class PlaylistImportDraftStore(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "playlist-import-drafts"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized fun save(userId: Long, state: PlaylistImportViewModel.UiState, completedSongIds: Set<Long>) {
        if (userId <= 0L) return
        val draft = PlaylistImportDraft(state.input, state.sourceTitle, state.sourceTotalCount,
            state.sourceReadCompleteness, state.overflowCount, state.queries, state.matches,
            state.selectedSongIds, state.selectedDestinationId, state.newPlaylistName, completedSongIds,
            creationPending = state.creatingDestination || state.creationUnconfirmed)
        writeAtomicText(fileFor(userId), json.encodeToString(ImportDraftEnvelope(userId = userId, draft = draft)))
    }

    @Synchronized fun load(userId: Long): PlaylistImportDraft? {
        if (userId <= 0L) return null
        val file = fileFor(userId)
        if (!file.isFile || file.length() > 8L * 1024L * 1024L) return null
        return runCatching {
            val envelope = json.decodeFromString<ImportDraftEnvelope>(file.readText())
            envelope.draft.takeIf { envelope.schema == 1 && envelope.userId == userId }
        }.getOrNull()
    }
    @Synchronized fun clear(userId: Long) { if (userId > 0L) fileFor(userId).delete() }
    private fun fileFor(userId: Long) = File(directory, "draft-$userId.json")
}

@Serializable
private data class ImportDraftEnvelope(val schema: Int = 1, val userId: Long, val draft: PlaylistImportDraft)

@Serializable
data class PlaylistImportDraft(
    val input: String, val title: String, val total: Int, val completeness: ExternalPlaylistReadCompleteness,
    val overflow: Int, val queries: List<ImportedSongQuery>, val matches: List<ImportedSongMatch>, val selected: Set<Long>,
    val destination: Long?, val newName: String, val completed: Set<Long>,
    val creationPending: Boolean = false,
)
