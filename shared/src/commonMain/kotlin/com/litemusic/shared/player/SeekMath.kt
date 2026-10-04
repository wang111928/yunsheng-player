package com.litemusic.shared.player

fun seekPositionMs(progress: Float, durationMs: Long): Long {
    if (durationMs <= 0L) return 0L
    return (progress.coerceIn(0f, 1f) * durationMs).toLong().coerceIn(0L, durationMs)
}
