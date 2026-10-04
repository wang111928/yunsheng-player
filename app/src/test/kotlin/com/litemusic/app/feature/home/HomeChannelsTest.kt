package com.litemusic.app.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.litemusic.app.data.mergeHomeSongs
import com.litemusic.app.data.mergeHomeSongPage
import com.litemusic.app.data.recommendationWindowIds
import com.litemusic.app.data.catalogPageHasMore
import com.litemusic.app.data.catalogPageSignature
import com.litemusic.app.data.homeSongCatalogOffset
import com.litemusic.app.data.homeSongCatalogOrder
import com.litemusic.app.data.homeSongNewCatalogCursor
import com.litemusic.app.data.playlistDetailPageError
import com.litemusic.app.data.rotateRecommendationWindow
import com.litemusic.app.data.recommendationWindowChanged
import com.litemusic.app.data.artistWindowChanged
import com.litemusic.app.data.resolveHomeRefreshContent
import com.litemusic.app.data.selectHeartThrobPlaylist
import com.litemusic.app.feature.playlist.PlaylistCatalogState
import com.litemusic.app.feature.playlist.canRequestPlaylistCatalogMore
import com.litemusic.app.feature.playlist.mergePlaylistCatalogPage
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.RadioStation
import com.litemusic.shared.model.Artist
import com.litemusic.shared.model.Song

/** 首页顶部频道 / 金刚区 / 推荐歌单刷新节流的纯逻辑测试。 */
class HomeChannelsTest {

    @Test
    fun channelOrderAndLabelsMatchMobileHome() {
        assertEquals(
            listOf("心动", "推荐", "音乐", "播客", "听书"),
            homeChannels().map { it.label },
        )
    }

    @Test
    fun defaultChannelIsRecommend() {
        assertEquals(HomeChannel.RECOMMEND, defaultHomeChannel())
    }

    @Test
    fun visibleChannelsUseTheRealHomeFeed() {
        assertEquals(
            homeChannels(),
            homeChannels().filter { it.hasRealFeed() },
        )
    }

    @Test
    fun quickEntriesKeepOnlyDiscoveryDestinations() {
        assertEquals(4, homeQuickEntries().size)
        assertEquals("每日推荐", homeQuickEntries().first().label)
        assertTrue(homeQuickEntries().any { it.label == "歌单广场" })
        assertTrue(homeQuickEntries().any { it.label == "私人FM" })
        assertFalse(homeQuickEntries().any { it.label in setOf("我喜欢的", "本地音乐", "我的", "设置") })
    }

    @Test
    fun playlistsRefreshOnColdStartAlways() {
        assertTrue(shouldRefreshPlaylists(lastRefreshAtMs = 0L, nowMs = 1_700_000_000_000L))
    }

    @Test
    fun playlistsRefreshThrottleBoundaries() {
        val min = 60_000L
        val last = 1_700_000_000_000L
        // 恰好达到节流窗口 -> 允许刷新
        assertTrue(shouldRefreshPlaylists(last, last + min, min))
        // 超窗 -> 允许刷新
        assertTrue(shouldRefreshPlaylists(last, last + min + 1, min))
        // 差 1ms 未到 -> 不刷新
        assertFalse(shouldRefreshPlaylists(last, last + min - 1, min))
        // 同一时刻 -> 不刷新
        assertFalse(shouldRefreshPlaylists(last, last, min))
    }

    @Test
    fun forcedRefreshBypassesTheForegroundThrottle() {
        val now = 1_700_000_000_000L
        assertTrue(shouldRefreshPlaylists(lastRefreshAtMs = now, nowMs = now, force = true))
    }

    @Test
    fun newerRefreshInvalidatesAnOlderResponse() {
        val generations = HomeRefreshGenerations()
        val first = generations.nextHomeRequest()
        val second = generations.nextHomeRequest()

        assertFalse(generations.isCurrentHomeRequest(first))
        assertTrue(generations.isCurrentHomeRequest(second))
    }

    @Test
    fun freshHomeReloadInvalidatesAnOlderSongAppend() {
        val generations = HomeRefreshGenerations()
        val olderAppend = generations.nextHomeSongRequest()
        val newerAppend = generations.nextHomeSongRequest()

        assertFalse(generations.isCurrentHomeSongRequest(olderAppend))
        assertTrue(generations.isCurrentHomeSongRequest(newerAppend))
    }

    @Test
    fun channelRequestsAreIndependentAndRejectStaleResponses() {
        val generations = HomeRefreshGenerations()
        val podcast = generations.nextChannelRequest(HomeChannel.PODCAST)
        val audiobook = generations.nextChannelRequest(HomeChannel.AUDIOBOOK)
        val newerPodcast = generations.nextChannelRequest(HomeChannel.PODCAST)

        assertTrue(generations.isCurrentChannelRequest(HomeChannel.AUDIOBOOK, audiobook))
        assertFalse(generations.isCurrentChannelRequest(HomeChannel.PODCAST, podcast))
        assertTrue(generations.isCurrentChannelRequest(HomeChannel.PODCAST, newerPodcast))
    }

    @Test
    fun homeSongsPutFreshFmAheadOfDailyAndRemoveDuplicates() {
        val daily = listOf(song(1), song(2), song(3))
        val fm = listOf(song(3), song(4), song(5))

        assertEquals(listOf(3L, 4L, 5L, 1L, 2L), mergeHomeSongs(fm, daily).map { it.id })
    }

    @Test
    fun homeSongsFallBackToDailyWhenNoFreshStreamIsAvailable() {
        val daily = listOf(song(1), song(2))

        assertEquals(daily, mergeHomeSongs(emptyList(), daily))
    }

    @Test
    fun freshHomeSongsRemainVisibleWhenPlaylistRefreshFails() {
        val previousPlaylists = listOf(playlist(9))
        val resolved = resolveHomeRefreshContent(
            freshHomeSongs = listOf(song(101)),
            dailySongs = emptyList(),
            freshPlaylists = null,
            previousPlaylists = previousPlaylists,
        )

        assertEquals(listOf(101L), resolved.homeSongs.map { it.id })
        assertEquals(previousPlaylists, resolved.playlists)
        assertTrue(resolved.hasUsableContent)
    }

    @Test
    fun emptyPlaylistPayloadDoesNotEraseRetainedPlaylists() {
        val previousPlaylists = listOf(playlist(9))
        val resolved = resolveHomeRefreshContent(
            freshHomeSongs = emptyList(),
            dailySongs = emptyList(),
            freshPlaylists = emptyList(),
            previousPlaylists = previousPlaylists,
        )

        assertEquals(previousPlaylists, resolved.playlists)
        assertTrue(resolved.hasUsableContent)
    }

    @Test
    fun noSongsAndNoPlaylistsMeansThereIsNoUsableHomeContent() {
        val resolved = resolveHomeRefreshContent(
            freshHomeSongs = emptyList(),
            dailySongs = emptyList(),
            freshPlaylists = null,
            previousPlaylists = emptyList(),
        )

        assertFalse(resolved.hasUsableContent)
    }

    @Test
    fun refreshedRecommendationPoolRotatesAwayFromTheVisibleSix() {
        val rotated = rotateRecommendationWindow(
            candidates = (1L..12L).map(::playlist),
            previousVisible = (1L..6L).map(::playlist),
            limit = 6,
        )

        assertEquals((7L..12L).toList(), rotated.map { it.id })
    }

    @Test
    fun subsequentRefreshContinuesPastTheSecondRecommendationWindow() {
        val rotated = rotateRecommendationWindow(
            candidates = (1L..18L).map(::playlist),
            previousVisible = (7L..12L).map(::playlist),
            limit = 6,
        )

        assertEquals((13L..18L).toList(), rotated.map { it.id })
    }

    @Test
    fun aSixItemRecommendationPoolNeedsCatalogRowsToRefresh() {
        val previous = (1L..6L).map(::playlist)
        val repeated = rotateRecommendationWindow(previous, previous, 6)
        assertFalse(recommendationWindowChanged(repeated, previous))

        val withRealCatalogRows = rotateRecommendationWindow(
            candidates = previous + (7L..12L).map(::playlist),
            previousVisible = previous,
            limit = 6,
        )
        assertEquals((7L..12L).toList(), withRealCatalogRows.map { it.id })
        assertTrue(recommendationWindowChanged(withRealCatalogRows, previous))
    }

    @Test
    fun artistRefreshFeedbackUsesTheVisibleArtistIds() {
        val previous = listOf(Artist(id = 7, name = "旧歌手"))

        assertFalse(artistWindowChanged(previous, previous))
        assertTrue(artistWindowChanged(listOf(Artist(id = 8, name = "新歌手")), previous))
    }

    @Test
    fun persistedRecommendationWindowKeepsOnlyUsableUniqueIds() {
        assertEquals(
            listOf(7L, 8L),
            recommendationWindowIds("7,invalid,0,8,7,-1"),
        )
    }

    @Test
    fun musicCatalogUsesTotalToKeepLoadingTheNextOffsetPage() {
        assertTrue(hasMoreCatalogRows(total = 80, offset = 0, received = 30))
        assertFalse(hasMoreCatalogRows(total = 30, offset = 0, received = 30))
        assertFalse(hasMoreCatalogRows(total = 80, offset = 30, received = 0, serverMore = true))
    }

    @Test
    fun repeatedMusicCatalogPageStopsAutomaticPagination() {
        val current = ChannelFeedState(
            playlists = listOf(Playlist(id = 7, name = "已加载")),
            nextOffset = 30,
            hasMore = true,
        )

        val next = mergeCatalogPage(current, listOf(Playlist(id = 7, name = "重复")), total = 100, serverMore = true, appending = true)

        assertEquals(listOf(7L), next.playlists.map { it.id })
        assertEquals(31, next.nextOffset)
        assertFalse(next.hasMore)
    }

    @Test
    fun playlistHubCatalogAppendsUniqueRowsAndStopsOnARepeatedPage() {
        val current = PlaylistCatalogState(
            rows = listOf(playlist(7)),
            offset = 30,
            hasMore = true,
        )

        val next = mergePlaylistCatalogPage(
            current = current,
            page = listOf(playlist(7)),
            total = 90,
            serverMore = true,
            append = true,
        )

        assertEquals(listOf(7L), next.rows.map { it.id })
        assertEquals(31, next.offset)
        assertFalse(next.hasMore)
    }

    @Test
    fun playlistHubNearEndObserverCannotQueueTheSamePageTwice() {
        val ready = PlaylistCatalogState(rows = listOf(playlist(7)), offset = 30, hasMore = true)

        assertTrue(canRequestPlaylistCatalogMore(ready, requestPending = false))
        assertFalse(canRequestPlaylistCatalogMore(ready, requestPending = true))
    }

    @Test
    fun playlistHubCatalogRefreshReplacesTheOldWindow() {
        val next = mergePlaylistCatalogPage(
            current = PlaylistCatalogState(rows = listOf(playlist(7)), offset = 30, hasMore = true),
            page = listOf(playlist(9), playlist(10)),
            total = 90,
            serverMore = true,
            append = false,
        )

        assertEquals(listOf(9L, 10L), next.rows.map { it.id })
        assertEquals(2, next.offset)
        assertTrue(next.hasMore)
    }

    @Test
    fun homeSongCatalogWithoutPaginationMetadataContinuesUntilTheServerActuallyStops() {
        assertTrue(catalogPageHasMore(received = 6, nextOffset = 6, total = 0, serverMore = false))
        assertFalse(catalogPageHasMore(received = 0, nextOffset = 6, total = 0, serverMore = false))
    }

    @Test
    fun overlapOnlyPageWithAnAdvancedCursorKeepsHomePagingAlive() {
        assertTrue(
            canContinueHomeSongPaging(
                serverHasMore = true,
                previousOffset = 18,
                nextOffset = 36,
                previousSongCount = 30,
                mergedSongCount = 30,
            ),
        )
    }

    @Test
    fun overlapOnlyPageWithAStuckCursorStopsHomePaging() {
        assertFalse(
            canContinueHomeSongPaging(
                serverHasMore = true,
                previousOffset = 18,
                nextOffset = 18,
                previousSongCount = 30,
                mergedSongCount = 30,
            ),
        )
    }

    @Test
    fun homeSongFeedContinuesInNewCatalogAfterHotCatalogEnds() {
        val newFirstPage = homeSongNewCatalogCursor(0)
        assertEquals("new", homeSongCatalogOrder(newFirstPage))
        assertEquals(0, homeSongCatalogOffset(newFirstPage))
        val newSecondPage = homeSongNewCatalogCursor(30)
        assertEquals(30, homeSongCatalogOffset(newSecondPage))
        assertTrue(canContinueHomeSongPaging(
            serverHasMore = true,
            previousOffset = 30,
            nextOffset = newFirstPage,
            previousSongCount = 30,
            mergedSongCount = 30,
        ))
    }

    @Test
    fun failedSongDetailsStopAutomaticFeedScanningButPartialSuccessDoesNot() {
        assertEquals(
            "歌单歌曲读取失败：网络错误",
            playlistDetailPageError(listOf(emptyList<Song>() to "网络错误", emptyList<Song>() to "超时")),
        )
        assertEquals(
            null,
            playlistDetailPageError(listOf(listOf(Song(id = 7L)) to null, emptyList<Song>() to "超时")),
        )
    }

    @Test
    fun repeatedCatalogWindowHasAStableSignatureForLoopDetection() {
        assertEquals(listOf(7L, 8L), catalogPageSignature(listOf(playlist(7), playlist(8))))
    }

    @Test
    fun failedStationAppendKeepsTheRowsAndCursorAlreadyShown() {
        val current = ChannelFeedState(
            stations = listOf(RadioStation(id = 7, name = "已加载电台")),
            nextOffset = 30,
            hasMore = true,
            loadingMore = true,
        )

        val next = channelPageFailure(current, "下一页失败", appending = true)

        assertEquals(listOf(7L), next.stations.map { it.id })
        assertEquals(30, next.nextOffset)
        assertTrue(next.hasMore)
        assertFalse(next.loadingMore)
        assertEquals("下一页失败", next.error)
    }

    @Test
    fun repeatedStationPageStopsPaginationInsteadOfSpinningForever() {
        val current = ChannelFeedState(
            stations = listOf(RadioStation(id = 7, name = "已加载电台")),
            nextOffset = 30,
            hasMore = true,
        )

        val next = mergeStationPage(current, listOf(RadioStation(id = 7, name = "重复电台")))

        assertEquals(listOf(7L), next.stations.map { it.id })
        assertFalse(next.hasMore)
        assertFalse(next.loadingMore)
    }

    @Test
    fun shortStationPageKeepsPagingUntilAnEmptyOrRepeatedWindow() {
        val current = ChannelFeedState(
            stations = listOf(RadioStation(id = 7, name = "首页电台")),
            nextOffset = 30,
            hasMore = true,
            loadingMore = true,
        )

        val next = mergeStationPage(
            current,
            listOf(
                RadioStation(id = 8, name = "短页一"),
                RadioStation(id = 9, name = "短页二"),
            ),
        )

        assertEquals(listOf(7L, 8L, 9L), next.stations.map { it.id })
        assertEquals(32, next.nextOffset)
        assertTrue(next.hasMore)
        assertFalse(next.loadingMore)
    }

    @Test
    fun emptyStationPageStopsPaging() {
        val current = ChannelFeedState(
            stations = listOf(RadioStation(id = 7, name = "首页电台")),
            nextOffset = 30,
            hasMore = true,
        )

        val next = mergeStationPage(current, emptyList())

        assertEquals(listOf(7L), next.stations.map { it.id })
        assertEquals(30, next.nextOffset)
        assertFalse(next.hasMore)
    }

    @Test
    fun radioRefreshMessageDistinguishesChangedAndUnchangedFirstPages() {
        val previous = listOf(RadioStation(id = 7, name = "旧电台"))

        assertEquals(
            "暂无新内容，已重新获取",
            radioRefreshMessage(previous, listOf(RadioStation(id = 7, name = "旧电台"))),
        )
        assertEquals(
            "内容已更新",
            radioRefreshMessage(previous, listOf(RadioStation(id = 8, name = "新电台"))),
        )
    }

    @Test
    fun refreshingFeedCannotStartAnotherPageRequest() {
        assertFalse(
            canLoadMoreChannel(
                ChannelFeedState(
                    stations = listOf(RadioStation(id = 7, name = "正在刷新")),
                    hasMore = true,
                    refreshing = true,
                ),
            ),
        )
    }

    @Test
    fun settledFeedWithMoreRowsCanStartAnotherPageRequest() {
        assertTrue(
            canLoadMoreChannel(
                ChannelFeedState(
                    stations = listOf(RadioStation(id = 7, name = "已加载")),
                    hasMore = true,
                ),
            ),
        )
    }

    @Test
    fun pendingRefreshTakesPriorityOverANearEndPageRequest() {
        assertFalse(
            shouldAppendChannelRequest(
                refreshCount = 2,
                handledRefreshCount = 1,
                loadMoreCount = 4,
                handledLoadMoreCount = 3,
            ),
        )
    }

    @Test
    fun pagingStillAdvancesAfterRefreshingAnAlreadyPagedChannel() {
        val channel = HomeChannel.PODCAST
        val before = ChannelFeedSession(
            refreshes = mapOf(channel to 1),
            handledRefreshes = mapOf(channel to 1),
            loadMoreRequests = mapOf(channel to 3),
            handledLoadMoreRequests = mapOf(channel to 3),
        )
        val refreshed = before.requestRefresh(channel)
        assertEquals(3, refreshed.loadMoreRequests[channel])
        assertFalse(shouldAppendChannelRequest(2, 1, 3, 3))
        assertTrue(shouldStartChannelRequest(true, 2, 2, 4, 3))
        assertTrue(shouldAppendChannelRequest(2, 2, 4, 3))
    }

    @Test
    fun switchingBackToACachedChannelDoesNotReplayItsOldRefreshOrPagingEvent() {
        assertFalse(
            shouldStartChannelRequest(
                hasCachedFeed = true,
                refreshCount = 1,
                handledRefreshCount = 1,
                loadMoreCount = 2,
                handledLoadMoreCount = 2,
            ),
        )
        assertTrue(
            shouldStartChannelRequest(
                hasCachedFeed = true,
                refreshCount = 2,
                handledRefreshCount = 1,
                loadMoreCount = 2,
                handledLoadMoreCount = 2,
            ),
        )
        assertTrue(
            shouldStartChannelRequest(
                hasCachedFeed = false,
                refreshCount = 0,
                handledRefreshCount = 0,
                loadMoreCount = 0,
                handledLoadMoreCount = 0,
            ),
        )
    }

    @Test
    fun retainedHeartThrobSessionDoesNotBecomeColdAfterPlayerNavigation() {
        val session = ChannelFeedSession(
            feeds = mapOf(HomeChannel.HEARTTHROB to ChannelFeedState(songs = listOf(song(7)))),
            handledRefreshes = mapOf(HomeChannel.HEARTTHROB to 0),
            handledLoadMoreRequests = mapOf(HomeChannel.HEARTTHROB to 0),
        )

        assertFalse(
            shouldStartChannelRequest(
                hasCachedFeed = HomeChannel.HEARTTHROB in session.feeds,
                refreshCount = session.refreshes[HomeChannel.HEARTTHROB] ?: 0,
                handledRefreshCount = session.handledRefreshes[HomeChannel.HEARTTHROB] ?: 0,
                loadMoreCount = session.loadMoreRequests[HomeChannel.HEARTTHROB] ?: 0,
                handledLoadMoreCount = session.handledLoadMoreRequests[HomeChannel.HEARTTHROB] ?: 0,
            ),
        )
    }

    @Test
    fun additionalHomeSongsKeepExistingRowsAndRemoveDuplicateIds() {
        val merged = mergeHomeSongPage(
            current = listOf(song(1), song(2)),
            incoming = listOf(song(2), song(3)),
        )

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
    }

    @Test
    fun heartThrobSelectsLikedPlaylistBeforeOtherOwnedPlaylists() {
        val selection = selectHeartThrobPlaylist(
            playlists = listOf(
                Playlist(id = 1, name = "我创建的歌单", trackCount = 12, userId = 9),
                Playlist(id = 2, name = "小明喜欢的音乐", trackCount = 30, userId = 9),
            ),
            userId = 9,
        )

        assertEquals(2L, selection?.id)
    }

    @Test
    fun malformedRadioProgramPayloadIsNeverShownAsRawJsonToTheUser() {
        val message = radioProgramLoadErrorMessage(
            "Unexpected JSON token at offset 4300: Expected beginning of the string, but got [ at path: $.programs[0].programDesc",
        )

        assertEquals("节目内容读取失败，请稍后重试", message)
    }

    private fun song(id: Long) = Song(id = id, name = "song-$id")

    private fun playlist(id: Long) = Playlist(id = id, name = "playlist-$id", trackCount = 1)
}
