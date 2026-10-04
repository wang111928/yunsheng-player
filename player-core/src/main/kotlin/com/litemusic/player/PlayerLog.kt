package com.litemusic.player

/**
 * 播放日志出口：默认写 android.util.Log；App 层可注入文件 sink。
 * OriginOS 等 ROM 屏蔽第三方 logcat 时，文件 sink 是唯一排查通道。
 */
object PlayerLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun i(msg: String) {
        android.util.Log.i("NmlPlayer", msg)
        sink?.invoke("I " + msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        runCatching { android.util.Log.e("NmlPlayer", msg, t) }
        sink?.invoke("E " + msg + (t?.let { " !! " + describe(it) } ?: ""))
    }

    private fun describe(t: Throwable): String {
        val sb = StringBuilder()
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < 5) {
            sb.append(c.javaClass.simpleName).append(": ").append(c.message).append(" <- ")
            c = c.cause
            depth++
        }
        return sb.toString()
    }
}
