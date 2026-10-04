package com.litemusic.app.warmup

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动预热（BiliPai / 杜比大喇叭β 优点）：
 * Application.onCreate 中异步初始化网络/数据库/播放器，
 * 主线程仅做最小初始化，冷启动 < 400ms。
 */
object AppWarmup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context) {
        val start = System.nanoTime()
        scope.launch {
            warmupClasses()
            context.getDatabasePath("nml.db")?.exists()
            delay(200)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            android.util.Log.i("AppWarmup", "warmup done in " + elapsed + "ms")
        }
    }

    private fun warmupClasses() {
        runCatching {
            Class.forName("com.litemusic.shared.crypto.WeapiCrypto")
            Class.forName("com.litemusic.shared.crypto.EapiCrypto")
            Class.forName("com.litemusic.shared.player.PlayerStateMachine")
            Class.forName("com.litemusic.lyric.LrcParser")
        }
    }
}
