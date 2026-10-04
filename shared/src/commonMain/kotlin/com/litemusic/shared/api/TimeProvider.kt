package com.litemusic.shared.api

/** 时间戳提供：JVM/Android 均返回 System.currentTimeMillis() */
expect fun currentTimeMillis(): Long

/** 当前 Unix 秒 */
fun currentEpochSeconds(): Long = currentTimeMillis() / 1000

/** 毫秒时间戳字符串（用于 requestId 等） */
fun currentTimeMillisStr(): String = currentTimeMillis().toString()

