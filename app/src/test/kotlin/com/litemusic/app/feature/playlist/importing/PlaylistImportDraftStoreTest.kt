package com.litemusic.app.feature.playlist.importing

import com.litemusic.shared.model.Album
import com.litemusic.shared.model.Artist
import com.litemusic.shared.model.Song
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistImportDraftStoreTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun aMatchingCheckpointRetainsConfirmedSongsAndUnfinishedQueries() {
        val queries = listOf(ImportedSongQuery("一", "歌手", fromScreenshot = true), ImportedSongQuery("二", "歌手", fromScreenshot = true))
        val confirmed = ImportedSongMatch(queries[0], Song(id = 42, name = "一"), 100)
        val waiting = ImportedSongMatch(queries[1], reason = "等待匹配", searchIncomplete = true)
        val directory = folder.newFolder()
        PlaylistImportDraftStore(directory).save(11, PlaylistImportViewModel.UiState(queries = queries,
            matches = listOf(confirmed, waiting), selectedSongIds = setOf(42)), emptySet())
        val restored = PlaylistImportDraftStore(directory).load(11)!!
        assertEquals(queries, restored.queries)
        assertEquals(42L, restored.matches[0].song?.id)
        assertFalse(restored.matches[0].searchIncomplete)
        assertTrue(restored.matches[1].searchIncomplete)
        assertEquals(setOf(42L), restored.selected)
    }
    @Test fun coldRecreationPreservesOcrEvidenceSelectionTargetAndCompletedWrites() {
        val query = ImportedSongQuery("标题", "歌手", "专辑", listOf("备选标题"), listOf("备选歌手"),
            listOf("备选专辑"), listOf(ImportedSongMetadataAlternative("歌手2", "专辑2")), true)
        val song = Song(id = 42, name = "完整歌曲名", ar = listOf(Artist(id = 5, name = "歌手")), al = Album(id = 6, name = "专辑"))
        val match = ImportedSongMatch(query, song, 100, "已核对版本", true)
        val directory = folder.newFolder()
        val state = PlaylistImportViewModel.UiState(input = "图片识别内容", sourceTitle = "图片导入", queries = listOf(query),
            matches = listOf(match), selectedSongIds = setOf(42), selectedDestinationId = 91, newPlaylistName = "自己的歌单")
        PlaylistImportDraftStore(directory).save(11, state, setOf(42))
        val restored = PlaylistImportDraftStore(directory).load(11)!!
        assertEquals(query, restored.queries.single())
        assertEquals(match, restored.matches.single())
        assertEquals(setOf(42L), restored.selected)
        assertEquals(setOf(42L), restored.completed)
        assertEquals(91L, restored.destination)
        assertNull(PlaylistImportDraftStore(directory).load(12))
    }

    @Test fun plainInputAndAmbiguousCreationSurviveWithoutCreatingAnotherTarget() {
        val store = PlaylistImportDraftStore(folder.newFolder())
        store.save(11, PlaylistImportViewModel.UiState(input = "还未开始匹配", creatingDestination = true), emptySet())
        assertEquals("还未开始匹配", store.load(11)!!.input)
        assertTrue(store.load(11)!!.creationPending)
        store.clear(11)
        assertNull(store.load(11))
    }

    @Test fun replacementAndCorruptionDoNotLeakAnotherAccount() {
        val directory = folder.newFolder()
        val store = PlaylistImportDraftStore(directory)
        store.save(11, PlaylistImportViewModel.UiState(input = "旧输入"), emptySet())
        store.save(11, PlaylistImportViewModel.UiState(input = "新输入"), emptySet())
        assertEquals("新输入", store.load(11)!!.input)
        java.io.File(directory, "draft-11.json").writeText("broken")
        assertNull(store.load(11))
        assertNull(store.load(0))
    }
}
