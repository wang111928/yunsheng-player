package com.litemusic.app

import android.app.Application
import com.litemusic.app.di.appModule
import com.litemusic.app.di.dataModule
import com.litemusic.app.di.networkModule
import com.litemusic.app.di.playerModule
import com.litemusic.app.di.playerBootstrapModule
import com.litemusic.app.di.repositoriesModule
import com.litemusic.app.di.viewModelsModule
import com.litemusic.app.warmup.AppWarmup
import com.litemusic.app.data.AuthRepository
import com.litemusic.app.util.DbgLog
import com.litemusic.player.PlayerLog
import com.litemusic.player.PlaybackBridge
import com.litemusic.player.PlaybackService
import com.litemusic.player.QueuePersistence
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.player.QueueItem
import com.litemusic.shared.api.ApiClient
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.android.ext.android.get
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DbgLog.init(this)
        DbgLog.w("App", "onCreate / process start")
        startKoin {
            if (BuildConfig.DEBUG) androidLogger(Level.ERROR)
            androidContext(this@App)
            modules(
                appModule,
                networkModule,
                dataModule,
                playerModule,
                playerBootstrapModule,
                repositoriesModule,
                viewModelsModule,
            )
        }
        // 日志出口：ROM 屏蔽 logcat 时写私有文件
        runCatching { get<ApiClient>().logger = { DbgLog.w("net", it) } }
        val stateMachine = get<PlayerStateMachine>()
        val queuePersistence = get<QueuePersistence>()
        val queueRestored = CompletableDeferred<Unit>()
        val songs = get<com.litemusic.app.data.SongRepository>()
        val playlists = get<com.litemusic.app.data.PlaylistRepository>()
        PlaybackService.bridge = object : PlaybackBridge {
            override val stateMachine: PlayerStateMachine = stateMachine
            override suspend fun awaitInitialQueueRestore() = queueRestored.await()
            override suspend fun resolveUrl(item: QueueItem, forceRefresh: Boolean): String =
                songs.resolveUrl(item, forceRefresh)
            override val favoriteIds = playlists.likedIds
            override suspend fun setFavorite(item: QueueItem, liked: Boolean): Boolean {
                if (item.isLocal) return false
                return playlists.like(item.id, liked) is AppResult.Success
            }
        }
        PlayerLog.sink = { DbgLog.w("player", it) }
        // Restore before observing state. This preserves a paused current item and its position
        // across process death without allowing the empty bootstrap state to replace Room data.
        appScope.launch {
            try {
                val restored = queuePersistence.restore()
                DbgLog.w("Playback", "queue restore restored=$restored items=${stateMachine.state.value.queue.size}")
            } finally {
                queueRestored.complete(Unit)
            }
            queuePersistence.observeAndSave()
        }
        // 登录态自愈：本地有 Cookie 但 uid 缺失（旧版扫码登录未落库）时补拉账号信息
        runCatching {
            val auth = get<AuthRepository>()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    val s = auth.session.first()
                    DbgLog.w("Auth", "boot session loggedIn=" + s.loggedIn + " uid=" + s.userId + " nick=" + s.nickname)
                    if (s.loggedIn && s.userId == 0L) {
                        when (val r = auth.refreshProfile()) {
                            is AppResult.Success -> DbgLog.w("Auth", "self-heal refreshProfile uid=" + r.data.userId + " nick=" + r.data.nickname)
                            is AppResult.Failure -> DbgLog.w("Auth", "self-heal refreshProfile FAIL " + r.code + " " + r.message)
                        }
                    }
                }
            }
        }
        AppWarmup.start(this)
    }
}
