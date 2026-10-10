package com.litemusic.app.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerUiPolicyTest {
    @Test fun wheelTimeRejectsZeroAndOutOfRangeValues() {
        assertNull(SleepTimerUiPolicy.minutesForWheel(hours = 0, minutes = 0))
        assertNull(SleepTimerUiPolicy.minutesForWheel(hours = 24, minutes = 1))
        assertNull(SleepTimerUiPolicy.minutesForWheel(hours = -1, minutes = 0))
        assertNull(SleepTimerUiPolicy.minutesForWheel(hours = 25, minutes = 0))
        assertNull(SleepTimerUiPolicy.minutesForWheel(hours = 1, minutes = 60))
    }

    @Test fun wheelTimeConvertsValidHourMinutePairsToMinutes() {
        assertEquals(1_440, SleepTimerUiPolicy.minutesForWheel(hours = 24, minutes = 0))
        assertEquals(73, SleepTimerUiPolicy.minutesForWheel(hours = 1, minutes = 13))
        assertEquals(1_439, SleepTimerUiPolicy.minutesForWheel(hours = 23, minutes = 59))
    }

    @Test fun remainingClockRoundsPartialSecondsUp() {
        assertEquals("00:00:01", SleepTimerUiPolicy.formatRemainingMs(1L))
        assertEquals("00:00:01", SleepTimerUiPolicy.formatRemainingMs(999L))
        assertEquals("00:01:00", SleepTimerUiPolicy.formatRemainingMs(59_001L))
        assertEquals("00:00:00", SleepTimerUiPolicy.formatRemainingMs(0L))
        assertEquals("01:01:00", SleepTimerUiPolicy.formatRemainingMs(3_660_000L))
        assertEquals("24:00:00", SleepTimerUiPolicy.formatRemainingMs(86_400_000L))
    }
}
