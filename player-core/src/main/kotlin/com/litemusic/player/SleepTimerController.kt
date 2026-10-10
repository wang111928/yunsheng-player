package com.litemusic.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SleepTimerMode { OFF, MINUTES, CURRENT_TRACK }

/** Shared product limits for quick and manually entered sleep timers. */
object SleepTimerMinutes {
    const val MIN = 1
    const val MAX = 1_440

    fun isValid(minutes: Int): Boolean = minutes in MIN..MAX
}

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val deadlineMs: Long? = null,
) {
    fun remainingMs(nowMs: Long): Long? = deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) }
}

/** Service-owned sleep policy. Tokens make replaced timers and old end callbacks harmless. */
class SleepTimerController(private val clockMs: () -> Long) {
    private var deadlineMs: Long? = null
    private var endingMediaKey: String? = null
    private var endingSelectionGeneration: Long? = null
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state

    @Synchronized
    fun startMinutes(minutes: Int) {
        if (!SleepTimerMinutes.isValid(minutes)) return
        deadlineMs = clockMs() + minutes.toLong() * 60_000L
        endingMediaKey = null
        endingSelectionGeneration = null
        _state.value = SleepTimerState(SleepTimerMode.MINUTES, deadlineMs)
    }

    @Synchronized
    fun stopAfterCurrent(mediaKey: String, selectionGeneration: Long) {
        deadlineMs = null
        endingMediaKey = mediaKey.substringBefore('#')
        endingSelectionGeneration = selectionGeneration
        _state.value = SleepTimerState(SleepTimerMode.CURRENT_TRACK)
    }

    @Synchronized
    fun cancel() { deadlineMs = null; endingMediaKey = null; endingSelectionGeneration = null; _state.value = SleepTimerState() }

    /** Quality changes retain the song; explicitly selecting another song cancels this mode. */
    @Synchronized
    fun onSelectionChanged(mediaKey: String?, selectionGeneration: Long) {
        if (endingMediaKey != null && (endingMediaKey != mediaKey?.substringBefore('#') ||
                endingSelectionGeneration != selectionGeneration)) cancel()
    }

    @Synchronized
    fun shouldPause(nowMs: Long, endedMediaKey: String?, selectionGeneration: Long? = null): Boolean {
        val timedOut = deadlineMs?.let { nowMs >= it } == true
        val ended = endingMediaKey != null && endingMediaKey == endedMediaKey?.substringBefore('#') && endingSelectionGeneration == selectionGeneration
        if (timedOut || ended) cancel()
        return timedOut || ended
    }
}
