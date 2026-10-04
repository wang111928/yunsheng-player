package com.litemusic.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlinx.coroutines.delay

/**
 * WebSocket 管理器：自动重连（指数退避 + 抖动），
 * 用于私信实时推送与一起听房间同步（Full 版）。
 */
class WebSocketManager(
    private val client: OkHttpClient,
) {
    private var socket: WebSocket? = null
    private var closed = true
    private var retry = 0

    interface Listener {
        fun onMessage(text: String)
        fun onOpen()
        fun onClosed()
        fun onFailure(t: Throwable)
    }

    fun connect(url: String, headers: Map<String, String>, listener: Listener) {
        closed = false
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        socket = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retry = 0
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                listener.onMessage(bytes.utf8())

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed = true
                listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t)
                if (!closed) scheduleReconnect(url, headers, listener)
            }
        })
    }

    private fun scheduleReconnect(url: String, headers: Map<String, String>, listener: Listener) {
        val backoffMs = (1000L shl retry.coerceAtMost(5)) + (0..500).random()
        retry++
        Thread {
            Thread.sleep(backoffMs)
            if (!closed) connect(url, headers, listener)
        }.start()
    }

    fun send(text: String): Boolean = socket?.send(text) ?: false

    fun close() {
        closed = true
        socket?.close(1000, "bye")
        socket = null
    }
}
