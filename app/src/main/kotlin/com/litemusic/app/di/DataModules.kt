package com.litemusic.app.di

import androidx.room.Room
import com.litemusic.data.auth.CookieStoreImpl
import com.litemusic.data.auth.CredentialBackup
import com.litemusic.data.cache.ContentCache
import com.litemusic.data.db.AppDatabase
import com.litemusic.data.prefs.AuthStore
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.player.QueuePersistence
import com.litemusic.app.util.NetworkStatusMonitor
import com.litemusic.shared.player.PlayerStateMachine
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** data-core 装配（Room / DataStore / 缓存 / 凭证） */
val dataModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "nml.db")
            .fallbackToDestructiveMigration()
            .build()
    }
    single { get<AppDatabase>().playbackDao() }
    single { get<AppDatabase>().localMetaDao() }
    single { get<AppDatabase>().signinHistoryDao() }
    single { get<AppDatabase>().searchHistoryDao() }
    single { get<AppDatabase>().blockedKeywordDao() }
    single { get<AppDatabase>().cacheIndexDao() }
    single { SettingsStore(androidContext()) }
    single { AuthStore(androidContext()) }
    single<com.litemusic.network.CookieStore> { CookieStoreImpl(get()) }
    single { CredentialBackup(get()) }
    single { ContentCache(androidContext()) }
    single { NetworkStatusMonitor(androidContext()) }
}

/** player-core 装配 */
val playerModule = module {
    single { PlayerStateMachine() }
    single { QueuePersistence(get(), get()) }
}
