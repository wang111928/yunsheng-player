package com.litemusic.app.util

import android.content.Context
import java.io.File

/**
 * 调试日志：写入 App 私有目录 files/nml-debug.log。
 * OriginOS 等 ROM 会屏蔽第三方应用的 logcat，文件日志是唯一可观测通道。
 * 读取方式：adb shell run-as <pkg> cat files/nml-debug.log
 */
object DbgLog {

    @Volatile
    private var file: File? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "nml-debug.log")
    }

    fun w(tag: String, msg: String) {
        val f = file ?: return
        runCatching {
            synchronized(this) {
                if (f.length() > 1_000_000L) f.delete()
                f.appendText(System.currentTimeMillis().toString() + " [" + tag + "] " + msg + "\n")
            }
        }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        w(tag, msg + (t?.let { " !! " + describe(it) } ?: ""))
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
