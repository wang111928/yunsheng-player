package com.litemusic.app.feature.player

import com.litemusic.lyric.LyricUiLine
import com.litemusic.shared.player.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricImportCoordinatorTest {
    private val localA = QueueItem(-1L, "A", "歌手", localPath = "/music/a.mp3")
    private val localB = QueueItem(-2L, "B", "歌手", localPath = "/music/b.mp3")

    @Test
    fun importForSongAAfterSwitchingToBDoesNotReloadOrMessageB() {
        val imports = LocalLyricImportCoordinator<String>()
        assertTrue(imports.prepare(localA))
        val target = imports.accept("content://picked-a")
        assertEquals(localA, target)

        assertEquals(
            LocalLyricImportCoordinator.Completion.NO_CURRENT_UPDATE,
            imports.finish(target!!, succeeded = false, current = localB),
        )
        assertNull(imports.failureMessageFor(localB))
    }

    @Test
    fun cancelAndInFlightGuardAllowOnlyOneImportAtATime() {
        val imports = LocalLyricImportCoordinator<String>()
        assertTrue(imports.prepare(localA))
        assertFalse(imports.prepare(localA))
        assertNull(imports.accept(null))
        assertTrue(imports.prepare(localA))
        val target = imports.accept("content://picked-a")
        assertFalse(imports.prepare(localA))

        assertEquals(
            LocalLyricImportCoordinator.Completion.RELOAD_CURRENT,
            imports.finish(target!!, succeeded = true, current = localA),
        )
        assertTrue(imports.prepare(localA))
    }

    @Test
    fun importFailureIsMergedIntoLateAutomaticLoadWithoutDiscardingLyrics() {
        val imports = LocalLyricImportCoordinator<String>()
        assertTrue(imports.prepare(localA))
        val target = imports.accept("content://picked-a")!!
        assertEquals(
            LocalLyricImportCoordinator.Completion.SHOW_FAILURE,
            imports.finish(target, succeeded = false, current = localA),
        )
        val automaticLoad = PlayerViewModel.LyricUi(
            lines = listOf(LyricUiLine(1_000, "已加载歌词")),
            hasTranslation = true,
        )

        val shownAfterLateLoad = automaticLoad.withLocalImportMessage(localA, imports)
        assertEquals(automaticLoad.lines, shownAfterLateLoad.lines)
        assertTrue(shownAfterLateLoad.hasTranslation)
        assertEquals(LocalLyricImportCoordinator.IMPORT_FAILURE_MESSAGE, shownAfterLateLoad.message)

        imports.onCurrentChanged(localB)
        assertNull(imports.failureMessageFor(localB))
    }

    @Test
    fun noFailureDoesNotMessageNullSongOrClearTheLoaderMessage() {
        val imports = LocalLyricImportCoordinator<String>()
        val missing = PlayerViewModel.LyricUi(message = "未找到歌词，可选择 .lrc 文件")

        assertNull(imports.failureMessageFor(null))
        assertEquals(missing.message, missing.withLocalImportMessage(null, imports).message)
    }
}
