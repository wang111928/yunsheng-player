package com.litemusic.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerControllerTest {
    @Test fun timedTimerFiresOnlyAtItsDeadline() {
        val timer = SleepTimerController { 1_000L }
        timer.startMinutes(15)
        assertFalse(timer.shouldPause(nowMs = 900_999L, endedMediaKey = null))
        assertTrue(timer.shouldPause(nowMs = 901_000L, endedMediaKey = null))
    }

    @Test fun endOfTrackTimerCannotPauseANewerTrack() {
        val timer = SleepTimerController { 0L }
        timer.stopAfterCurrent("one#320000", 4L)
        assertFalse(timer.shouldPause(nowMs = 0L, endedMediaKey = "two#320000", selectionGeneration = 5L))
        assertTrue(timer.shouldPause(nowMs = 0L, endedMediaKey = "one#320000", selectionGeneration = 4L))
    }

    @Test fun changingQualityDoesNotDisableStopAfterCurrentSong() {
        val timer = SleepTimerController { 0L }
        timer.stopAfterCurrent("one#320000", 4L)
        timer.onSelectionChanged("one#999000", 4L)
        assertTrue(timer.shouldPause(0L, "one#999000", 4L))
        assertEquals(SleepTimerMode.OFF, timer.state.value.mode)
    }

    @Test fun explicitSkipCancelsEndModeButKeepsTimedMode() {
        val timer = SleepTimerController { 100L }
        timer.stopAfterCurrent("one#320000", 4L)
        timer.onSelectionChanged("two#320000", 5L)
        assertEquals(SleepTimerMode.OFF, timer.state.value.mode)
        timer.startMinutes(30)
        timer.onSelectionChanged("three#320000", 6L)
        assertEquals(SleepTimerMode.MINUTES, timer.state.value.mode)
        assertTrue(timer.shouldPause(1_800_100L, null))
    }

    @Test fun cancelAndReplacementCannotFireOldDeadline() {
        var now = 0L
        val timer = SleepTimerController { now }
        timer.startMinutes(15)
        timer.cancel()
        assertFalse(timer.shouldPause(900_000L, null))
        timer.startMinutes(15)
        now = 100_000L
        timer.startMinutes(60)
        assertFalse(timer.shouldPause(900_000L, null))
        assertEquals(2_800_000L, timer.state.value.remainingMs(900_000L))
    }

    @Test fun customSevenMinuteTimerFiresAtItsExactDeadline() {
        val timer = SleepTimerController { 5_000L }
        timer.startMinutes(7)
        assertFalse(timer.shouldPause(nowMs = 424_999L, endedMediaKey = null))
        assertTrue(timer.shouldPause(nowMs = 425_000L, endedMediaKey = null))
    }

    @Test fun customTimerAcceptsOneMinuteAndTwentyFourHourBoundaries() {
        val timer = SleepTimerController { 0L }
        timer.startMinutes(1)
        assertEquals(60_000L, timer.state.value.remainingMs(0L))
        timer.startMinutes(1_440)
        assertEquals(86_400_000L, timer.state.value.remainingMs(0L))
    }

    @Test fun invalidCustomMinutesKeepTheExistingTimerRunning() {
        val timer = SleepTimerController { 0L }
        timer.startMinutes(30)
        listOf(0, 1_441, Int.MAX_VALUE).forEach { invalidMinutes ->
            timer.startMinutes(invalidMinutes)
            assertEquals(SleepTimerMode.MINUTES, timer.state.value.mode)
            assertEquals(1_800_000L, timer.state.value.remainingMs(0L))
        }
    }

    @Test fun customAndQuickTimersReplaceEachOther() {
        val timer = SleepTimerController { 0L }
        timer.startMinutes(7)
        timer.startMinutes(15)
        assertFalse(timer.shouldPause(nowMs = 420_000L, endedMediaKey = null))
        assertTrue(timer.shouldPause(nowMs = 900_000L, endedMediaKey = null))
    }

    @Test fun quickTimerReplacedByCustomTimerStartsFromTheNewStartTime() {
        var nowMs = 0L
        val timer = SleepTimerController { nowMs }
        timer.startMinutes(15)
        nowMs = 100_000L
        timer.startMinutes(7)
        assertFalse(timer.shouldPause(nowMs = 519_999L, endedMediaKey = null))
        assertTrue(timer.shouldPause(nowMs = 520_000L, endedMediaKey = null))
    }

    @Test fun cancellingCustomTimerPreventsItsFormerDeadlineFromPausing() {
        val timer = SleepTimerController { 0L }
        timer.startMinutes(7)
        timer.cancel()
        assertEquals(SleepTimerMode.OFF, timer.state.value.mode)
        assertFalse(timer.shouldPause(nowMs = 420_000L, endedMediaKey = null))
    }

    @Test fun negativeCustomMinutesLeaveCurrentTrackTimerUntouched() {
        val timer = SleepTimerController { 0L }
        timer.stopAfterCurrent("one#320000", 3L)
        timer.startMinutes(-5)
        assertEquals(SleepTimerMode.CURRENT_TRACK, timer.state.value.mode)
        assertTrue(timer.shouldPause(nowMs = 0L, endedMediaKey = "one#320000", selectionGeneration = 3L))
    }
}
