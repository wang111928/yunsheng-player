package com.litemusic.app.feature.search

import com.litemusic.app.data.SearchRepository
import com.litemusic.shared.model.Playlist
import com.litemusic.app.data.pageHasMore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜索结果分组顺序 / 折叠展开的纯逻辑测试。 */
class SearchSectionsTest {

    @Test
    fun sectionOrderMatchesNeteaseResultPage() {
        assertEquals(
            listOf("单曲", "歌手", "歌单", "用户"),
            searchSectionOrder().map { it.title },
        )
    }

    @Test
    fun defaultCollapsedCountIsThree() {
        assertEquals(3, SEARCH_SECTION_COLLAPSED)
    }

    @Test
    fun visibleCountRespectsCollapsedAndExpanded() {
        assertEquals(3, sectionVisibleCount(total = 20, expanded = false))
        assertEquals(20, sectionVisibleCount(total = 20, expanded = true))
        // 少于折叠阈值时全部展示
        assertEquals(2, sectionVisibleCount(total = 2, expanded = false))
        assertEquals(0, sectionVisibleCount(total = 0, expanded = false))
    }

    @Test
    fun seeAllOnlyWhenExceedingCollapsed() {
        assertFalse(sectionHasSeeAll(total = 3))
        assertTrue(sectionHasSeeAll(total = 4))
        assertTrue(sectionHasSeeAll(total = 20))
    }

    @Test
    fun toggleLabelSwitchesBetweenSeeAllAndCollapse() {
        val section = SearchSection.SONG
        assertEquals("查看全部（6）", sectionToggleLabel(section, total = 6, expanded = false))
        assertEquals("收起", sectionToggleLabel(section, total = 6, expanded = true))
    }

    @Test
    fun nextSearchPageAppendsNewPlaylistRowsWithoutDuplicatingTheFirstPage() {
        val current = SearchRepository.SearchBundle(playlists = listOf(playlist(1), playlist(2)))
        val next = SearchRepository.SearchBundle(playlists = listOf(playlist(2), playlist(3)), hasMore = true)

        val merged = appendSearchPage(current, SearchSection.PLAYLIST, next)

        assertEquals(listOf(1L, 2L, 3L), merged.playlists.map { it.id })
        assertTrue(merged.playlistHasMore)
    }

    @Test
    fun countOnlySearchResponseStillMarksAnotherSongPageAvailable() {
        assertTrue(pageHasMore(offset = 0, received = 20, total = 41, serverHasMore = false))
        assertFalse(pageHasMore(offset = 20, received = 20, total = 40, serverHasMore = false))
    }

    @Test
    fun staleInitialSearchResponseCannotReplaceTheLatestKeyword() {
        assertFalse(shouldApplySearchResponse(1L, 2L, "旧关键词", "新关键词"))
        assertTrue(shouldApplySearchResponse(2L, 2L, "新关键词", "新关键词"))
    }

    private fun playlist(id: Long) = Playlist(id = id, name = "playlist-$id")
}
