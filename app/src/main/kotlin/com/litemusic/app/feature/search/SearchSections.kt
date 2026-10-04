package com.litemusic.app.feature.search

import com.litemusic.app.data.SearchRepository

/** 搜索结果分组（对齐网易云结果页：单曲 / 歌手 / 歌单 / 用户）。 */
enum class SearchSection(val title: String) {
    SONG("单曲"),
    ARTIST("歌手"),
    PLAYLIST("歌单"),
    USER("用户"),
}

/** 结果页分组展示顺序：单曲 → 歌手 → 歌单 → 用户。 */
fun searchSectionOrder(): List<SearchSection> = SearchSection.entries.toList()

/** 分组折叠时默认展示的条数。 */
const val SEARCH_SECTION_COLLAPSED = 3

/** 分组内实际展示的条数（展开则全部）。 */
fun sectionVisibleCount(
    total: Int,
    expanded: Boolean,
    collapsed: Int = SEARCH_SECTION_COLLAPSED,
): Int = if (expanded) total else minOf(total, collapsed)

/** 是否展示「查看全部」/「收起」入口。 */
fun sectionHasSeeAll(total: Int, collapsed: Int = SEARCH_SECTION_COLLAPSED): Boolean = total > collapsed

/** 「查看全部」/「收起」按钮文案。 */
fun sectionToggleLabel(section: SearchSection, total: Int, expanded: Boolean): String =
    if (expanded) "收起" else "查看全部（$total）"

fun sectionHasMore(bundle: SearchRepository.SearchBundle, section: SearchSection): Boolean = when (section) {
    SearchSection.SONG -> bundle.songHasMore
    SearchSection.ARTIST -> bundle.artistHasMore
    SearchSection.PLAYLIST -> bundle.playlistHasMore
    SearchSection.USER -> bundle.userHasMore
}

/** Merges one server page into its matching section and advances only that section's pagination. */
fun appendSearchPage(
    current: SearchRepository.SearchBundle,
    section: SearchSection,
    page: SearchRepository.SearchBundle,
): SearchRepository.SearchBundle = when (section) {
    SearchSection.SONG -> current.copy(
        songs = (current.songs + page.songs).distinctBy { it.id },
        songHasMore = page.songHasMore || page.hasMore,
    )
    SearchSection.ARTIST -> current.copy(
        artists = (current.artists + page.artists).distinctBy { it.id },
        artistHasMore = page.artistHasMore || page.hasMore,
    )
    SearchSection.PLAYLIST -> current.copy(
        playlists = (current.playlists + page.playlists).distinctBy { it.id },
        playlistHasMore = page.playlistHasMore || page.hasMore,
    )
    SearchSection.USER -> current.copy(
        users = (current.users + page.users).distinctBy { it.userId },
        userHasMore = page.userHasMore || page.hasMore,
    )
}
