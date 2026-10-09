package com.litemusic.app.di

import com.litemusic.app.BuildConfig
import com.litemusic.app.data.*
import com.litemusic.app.feature.auth.LoginViewModel
import com.litemusic.app.feature.comment.CommentViewModel
import com.litemusic.app.feature.home.HomeViewModel
import com.litemusic.app.feature.home.ToplistsViewModel
import com.litemusic.app.feature.library.LibraryViewModel
import com.litemusic.app.feature.library.LikedSongsViewModel
import com.litemusic.app.feature.localmusic.LocalMusicViewModel
import com.litemusic.app.feature.msgs.MsgViewModel
import com.litemusic.app.feature.notes.NotesViewModel
import com.litemusic.app.feature.player.PlayerViewModel
import com.litemusic.app.feature.playlist.PlaylistViewModel
import com.litemusic.app.feature.playlist.importing.PlaylistImportViewModel
import com.litemusic.app.feature.playlist.importing.ExternalPlaylistReader
import com.litemusic.app.feature.playlist.importing.PublicPlaylistReader
import com.litemusic.app.feature.search.SearchViewModel
import com.litemusic.app.feature.settings.SettingsViewModel
import com.litemusic.app.feature.update.GithubUpdateRepository
import com.litemusic.app.feature.signin.SigninViewModel
import com.litemusic.app.feature.social.SocialViewModel
import com.litemusic.app.feature.together.TogetherViewModel
import com.litemusic.data.prefs.AuthStore
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.lyric.LyricEngine
import com.litemusic.network.ApiGateway
import com.litemusic.network.CookieStore
import com.litemusic.network.NetworkEngine
import com.litemusic.network.PersistentCookieJar
import com.litemusic.network.WebSocketManager
import com.litemusic.player.PlaybackController
import com.litemusic.shared.api.ApiClient
import com.litemusic.shared.api.NMApi
import kotlinx.coroutines.flow.map
import org.koin.core.module.dsl.viewModelOf
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single { LyricEngine() }
    single { ApiGateway() }
}

val networkModule = module {
    single { NetworkEngine(PersistentCookieJar(get<CookieStore>()), BuildConfig.DEBUG) }
    single { get<NetworkEngine>().okHttpClient() }
    single(named("githubUpdateClient")) {
        get<NetworkEngine>().okHttpClientNoCookies().newBuilder().followSslRedirects(false).build()
    }
    single {
        ApiClient(
            // 必须使用「无 cookieJar」的 OkHttp：否则 BridgeInterceptor 会覆盖显式 Cookie 头，
            // 抹掉 eapi 的设备指纹，导致服务端按 Web 客户端返回带 authSecret 的地址（CDN 403）。
            engine = get<NetworkEngine>().ktorEngine(get<NetworkEngine>().okHttpClientNoCookies()),
            cookieProvider = { get<CookieStore>().all() },
        )
    }
    single { NMApi(get()) }
    single { WebSocketManager(get()) }
}

val repositoriesModule = module {
    single { GithubUpdateRepository(androidContext(), get(named("githubUpdateClient"))) }
    single { AuthRepository(get(), get(), get()) }
    single { HomeRepository(get(), get(), get(), get(), get()) }
    single { SearchRepository(get(), get()) }
    single { PlaylistRepository(get(), get(), get()) }
    single<ExternalPlaylistReader> { PublicPlaylistReader(get()) }
    single { SongRepository(get(), get()) }
    single { CommentRepository(get(), get(), get()) }
    single { NotesRepository(get()) }
    single { SocialRepository(get(), get()) }
    single { SigninRepository(get(), get(), get()) }
    single { LocalMusicRepository(get()) }
    single { LocalLyricRepository(get()) }
    single<com.litemusic.app.data.MsgDataSource> { MsgRepository(get()) }
    single<com.litemusic.app.data.TogetherRemote> { NMApiTogetherRemote(get()) }
    single { TogetherRepository(
        get(),
        get(),
        get<AuthStore>().session.map { if (it.loggedIn && it.userId > 0) it.userId else 0L },
    ) }
}

val playerBootstrapModule = module {
    single { PlaybackController(get(), get()) }
}

val viewModelsModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::ToplistsViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::LibraryViewModel)
    viewModelOf(::LikedSongsViewModel)
    viewModelOf(::PlayerViewModel)
    viewModelOf(::PlaylistViewModel)
    viewModelOf(::PlaylistImportViewModel)
    viewModelOf(::LocalMusicViewModel)
    viewModelOf(::SigninViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::CommentViewModel)
    viewModelOf(::NotesViewModel)
    viewModelOf(::SocialViewModel)
    viewModelOf(::MsgViewModel)
    viewModelOf(::TogetherViewModel)
}
