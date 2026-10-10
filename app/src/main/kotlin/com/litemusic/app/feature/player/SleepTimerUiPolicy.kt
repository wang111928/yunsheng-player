package com.litemusic.app.feature.player

import com.litemusic.player.SleepTimerMinutes
import java.util.Locale

/** Presentation-only time conversion used by the sleep timer sheet. */
internal object SleepTimerUiPolicy {
    fun minutesForWheel(hours: Int, minutes: Int): Int? {
        if (hours !in 0..24 || minutes !in 0..59) return null
        val total = hours * 60 + minutes
        return total.takeIf(SleepTimerMinutes::isValid)
    }

    fun formatRemainingMs(remainingMs: Long): String {
        val nonNegativeMs = remainingMs.coerceAtLeast(0L)
        val totalSeconds = nonNegativeMs / 1_000L + if (nonNegativeMs % 1_000L == 0L) 0L else 1L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
