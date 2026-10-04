package com.litemusic.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * API 网关：相同请求 5 秒内去重（知乎++ 优点：请求合并去重、连接池复用）。
 * OkHttp 连接池上限 8 条连接在 NetworkEngine 中配置。
 */
class ApiGateway(private val dedupWindowMs: Long = 5_000L) {
    private data class Entry(val key: String, val timestamp: Long)

    private val recent = ArrayDeque<Entry>()
    private val mutex = Mutex()

    /** 返回 true 表示该请求应当直接复用缓存结果（跳过网络） */
    suspend fun shouldDedupe(key: String, nowMs: Long): Boolean = mutex.withLock {
        val it = recent.lastOrNull { e -> e.key == key && nowMs - e.timestamp < dedupWindowMs }
        if (it != null) {
            true
        } else {
            recent.addLast(Entry(key, nowMs))
            if (recent.size > 200) recent.removeFirst()
            false
        }
    }
}
