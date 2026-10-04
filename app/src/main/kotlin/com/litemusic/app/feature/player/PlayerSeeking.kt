package com.litemusic.app.feature.player

import com.litemusic.lyric.LyricSection
import kotlin.math.abs

/** Keeps transient lyric browsing separate from playback-driven scrolling. */
internal data class LyricBrowseState(
    val selectedLineIndex: Int? = null,
    private val followResumeAtMs: Long? = null,
) {
    fun afterUserRelease(
        selectedLineIndex: Int,
        nowMs: Long,
        resumeDelayMs: Long = DEFAULT_FOLLOW_RESUME_DELAY_MS,
    ): LyricBrowseState = copy(
        selectedLineIndex = selectedLineIndex,
        followResumeAtMs = nowMs + resumeDelayMs,
    )

    /** Programmatic scrolling must never be mistaken for a user browse gesture. */
    fun afterProgrammaticScroll(): LyricBrowseState = this

    fun followsPlaybackAt(nowMs: Long): Boolean =
        followResumeAtMs == null || nowMs >= followResumeAtMs

    /** Exact remaining delay lets the UI resume once, even after a long fling. */
    fun remainingFollowPauseMs(nowMs: Long): Long =
        ((followResumeAtMs ?: nowMs) - nowMs).coerceAtLeast(0L)

    fun resumeWhenDue(nowMs: Long): LyricBrowseState =
        if (followsPlaybackAt(nowMs)) LyricBrowseState() else this

    fun resumePlaybackFollow(): LyricBrowseState = LyricBrowseState()

    fun resetForLyricsSource(): LyricBrowseState = LyricBrowseState()

    private companion object {
        // Let both finger drag and fling settle before playback takes the list back.
        const val DEFAULT_FOLLOW_RESUME_DELAY_MS = 3_000L
    }
}

/** Returns the scroll distance that places an already rendered lyric item at viewport center. */
internal fun lyricCenterScrollDelta(
    itemCenter: Int,
    viewportStart: Int,
    viewportEnd: Int,
): Int = itemCenter - (viewportStart + viewportEnd) / 2

/** Returns the lyric index whose rendered center is closest to the viewport center. */
internal fun nearestLyricLineIndex(
    itemCenters: List<Pair<Int, Int>>,
    viewportStart: Int,
    viewportEnd: Int,
): Int? {
    if (viewportEnd <= viewportStart) return null
    val viewportCenter = (viewportStart + viewportEnd) / 2
    return itemCenters
        .asSequence()
        .filter { (index, _) -> index >= 0 }
        .minByOrNull { (_, center) -> abs(center - viewportCenter) }
        ?.first
}

internal fun sectionProgressFractions(
    sections: List<LyricSection>,
    durationMs: Long,
): List<Float> {
    if (durationMs <= 0L) return emptyList()
    return sections
        .asSequence()
        .map { it.startTimeMs.toFloat() / durationMs.toFloat() }
        .filter { it in 0f..1f }
        .distinct()
        .sorted()
        .toList()
}

/** The mini player and full player both present progress as a bounded fraction. */
internal fun playerProgressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** Two people share a compact stack; the offset derives from the rendered size. */
internal fun togetherAvatarXOffsets(
    memberCount: Int,
    avatarSizeDp: Int = 46,
    overlapDp: Int = 10,
): List<Int> {
    val step = (avatarSizeDp - overlapDp).coerceAtLeast(0)
    return List(memberCount.coerceIn(0, 2)) { index -> index * step }
}

/**
 * Lazy-list scroll offsets address the item's leading edge.  Shift it by half
 * the item height so the item's visual center, not its top, reaches center.
 */
internal fun lyricCenteredItemScrollOffset(
    viewportSize: Int,
    targetItemHeight: Int,
): Int = -((viewportSize - targetItemHeight.coerceAtLeast(0)) / 2)
