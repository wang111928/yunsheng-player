package com.litemusic.app.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import com.litemusic.lyric.LyricSection

class PlayerSeekingTest {
    @Test
    fun lyricFollowCentersAnItemUsingItsActualViewportPosition() {
        assertEquals(
            36,
            lyricCenterScrollDelta(
                itemCenter = 436,
                viewportStart = 100,
                viewportEnd = 700,
            ),
        )
    }

    @Test
    fun programmaticScrollDoesNotEnterManualLyricBrowsing() {
        val state = LyricBrowseState()

        assertEquals(state, state.afterProgrammaticScroll())
        assertTrue(state.followsPlaybackAt(nowMs = 1_000L))
    }

    @Test
    fun userReleasePausesFollowThenRestoresItAfterTimeout() {
        val browsing = LyricBrowseState().afterUserRelease(
            selectedLineIndex = 6,
            nowMs = 1_000L,
        )

        assertEquals(6, browsing.selectedLineIndex)
        assertTrue(!browsing.followsPlaybackAt(nowMs = 3_499L))
        assertEquals(
            LyricBrowseState(),
            browsing.resumeWhenDue(nowMs = 4_000L),
        )
    }

    @Test
    fun inertiaBrowseKeepsPlaybackFollowPausedForThreeSecondsAfterRelease() {
        val browsing = LyricBrowseState().afterUserRelease(
            selectedLineIndex = 6,
            nowMs = 1_000L,
        )

        assertTrue(!browsing.followsPlaybackAt(nowMs = 3_999L))
        assertEquals(LyricBrowseState(), browsing.resumeWhenDue(nowMs = 4_000L))
    }

    @Test
    fun lyricFollowPauseReportsTheRemainingDelayUntilItCanResume() {
        val browsing = LyricBrowseState().afterUserRelease(
            selectedLineIndex = 6,
            nowMs = 1_000L,
        )

        assertEquals(3_000L, browsing.remainingFollowPauseMs(nowMs = 1_000L))
        assertEquals(500L, browsing.remainingFollowPauseMs(nowMs = 3_500L))
        assertEquals(0L, browsing.remainingFollowPauseMs(nowMs = 4_000L))
    }

    @Test
    fun togetherPresenceOverlapsTheSecondAvatarInsteadOfLeavingASeparateGap() {
        assertEquals(
            listOf(0, 36),
            togetherAvatarXOffsets(memberCount = 2, avatarSizeDp = 46, overlapDp = 10),
        )
        assertEquals(
            listOf(0, 20),
            togetherAvatarXOffsets(memberCount = 2, avatarSizeDp = 28, overlapDp = 8),
        )
        assertEquals(listOf(0), togetherAvatarXOffsets(memberCount = 1))
    }

    @Test
    fun lyricFollowOffsetCentersTheTargetItemsVisualCenter() {
        assertEquals(
            -256,
            lyricCenteredItemScrollOffset(viewportSize = 600, targetItemHeight = 88),
        )
        assertEquals(
            -220,
            lyricCenteredItemScrollOffset(viewportSize = 600, targetItemHeight = 160),
        )
    }

    @Test
    fun sourceChangeClearsManualSelectionAndRestoresFollow() {
        val browsing = LyricBrowseState().afterUserRelease(
            selectedLineIndex = 4,
            nowMs = 1_000L,
        )

        assertEquals(LyricBrowseState(), browsing.resetForLyricsSource())
    }

    @Test
    fun playingSelectedLineClearsManualSelectionImmediately() {
        val browsing = LyricBrowseState().afterUserRelease(
            selectedLineIndex = 4,
            nowMs = 1_000L,
        )

        assertEquals(LyricBrowseState(), browsing.resumePlaybackFollow())
    }

    @Test
    fun nearestLyricLineUsesTheViewportCenter() {
        val result = nearestLyricLineIndex(
            itemCenters = listOf(0 to 118, 1 to 420, 2 to 720),
            viewportStart = 0,
            viewportEnd = 800,
        )

        assertEquals(1, result)
    }

    @Test
    fun nearestLyricLineIgnoresSpacerItems() {
        val result = nearestLyricLineIndex(
            itemCenters = listOf(-1 to 400),
            viewportStart = 0,
            viewportEnd = 800,
        )

        assertNull(result)
    }

    @Test
    fun chorusMarkersMapSectionStartToProgressFractions() {
        val result = sectionProgressFractions(
            sections = listOf(
                LyricSection(2, 3, 25_000, 32_000),
                LyricSection(8, 9, 75_000, 82_000),
            ),
            durationMs = 100_000,
        )

        assertEquals(listOf(0.25f, 0.75f), result)
        assertTrue(sectionProgressFractions(emptyList(), 0).isEmpty())
    }

    @Test
    fun playerProgressIsClampedForTheCircularControl() {
        assertEquals(0f, playerProgressFraction(positionMs = 0L, durationMs = 0L))
        assertEquals(0.25f, playerProgressFraction(positionMs = 25_000L, durationMs = 100_000L))
        assertEquals(1f, playerProgressFraction(positionMs = 120_000L, durationMs = 100_000L))
    }
}
