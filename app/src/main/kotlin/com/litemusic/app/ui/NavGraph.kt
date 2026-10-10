package com.litemusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.litemusic.app.BuildConfig
import com.litemusic.app.feature.comment.CommentSheet
import com.litemusic.app.feature.home.DailyScreen
import com.litemusic.app.feature.home.HomeScreen
import com.litemusic.app.feature.home.ToplistsScreen
import com.litemusic.app.feature.library.LibraryScreen
import com.litemusic.app.feature.library.LikedSongsScreen
import com.litemusic.app.feature.localmusic.LocalMusicScreen
import com.litemusic.app.feature.msgs.MsgsScreen
import com.litemusic.app.feature.mv.MvScreen
import com.litemusic.app.feature.notes.NotesScreen
import com.litemusic.app.feature.player.PlayerScreen
import com.litemusic.app.feature.player.PlaybackQueueSheet
import com.litemusic.app.feature.playlist.PlaylistHubScreen
import com.litemusic.app.feature.playlist.PlaylistScreen
import com.litemusic.app.feature.playlist.importing.PlaylistImportRequestStore
import com.litemusic.app.feature.playlist.importing.PlaylistImportScreen
import com.litemusic.app.feature.search.SearchScreen
import com.litemusic.app.feature.search.ArtistDetailScreen
import com.litemusic.app.feature.settings.SettingsScreen
import com.litemusic.app.feature.signin.SigninScreen
import com.litemusic.app.feature.social.FollowsScreen
import com.litemusic.app.feature.social.followsInitialTab
import com.litemusic.app.feature.social.UserProfileScreen
import com.litemusic.app.feature.together.TogetherRoomScreen
import com.litemusic.app.feature.together.TogetherScreen
import com.litemusic.design.components.MiniPlayerBar
import com.litemusic.design.components.MiniPlayerMember
import com.litemusic.design.components.nmlPressable
import com.litemusic.app.data.TogetherRepository
import com.litemusic.player.PlaybackController
import com.litemusic.shared.player.PlayPhase
import com.litemusic.design.theme.LocalNmlThemeKind
import org.koin.compose.koinInject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

object Routes {
    const val PLAYLISTS = "playlists"
    const val HOME = "home"
    const val DAILY = "daily"
    const val SEARCH = "search"
    const val ARTIST_SEARCH = "artist-search/{query}"
    const val ARTIST = "artist/{id}"
    const val NOTES = "notes"
    const val LIBRARY = "library"
    const val LIKED = "liked"
    const val TOPLISTS = "toplists"
    const val PLAYER = "player"
    const val PLAYER_DESTINATION = "player?lyrics={lyrics}"
    fun player(lyrics: Boolean) = "player?lyrics=$lyrics"
    const val PLAYLIST = "playlist/{id}"
    const val PLAYLIST_IMPORT = "playlist-import"
    const val USER = "user/{uid}"
    const val FOLLOWS = "follows/{uid}?tab={tab}"
    const val LOCAL = "local"
    const val SIGNIN = "signin"
    const val SETTINGS = "settings"
    const val MSGS = "msgs"
    const val MSGS_USER = "msgs/{uid}"
    const val TOGETHER = "together"
    const val TOGETHER_ROOM = "together/room/{code}"
    const val MV = "mv/{id}"

    fun playlist(id: Long) = "playlist/$id"
    fun user(uid: Long) = "user/$uid"
    fun msgs(uid: Long) = "msgs/$uid"
    fun follows(uid: Long, followers: Boolean = false) = "follows/$uid?tab=${if (followers) 1 else 0}"
    fun togetherRoom(code: String) = "together/room/$code"
    fun mv(id: Long) = "mv/$id"
    fun artistSearch(name: String) = "artist-search/${android.net.Uri.encode(name)}"
    fun artist(id: Long) = "artist/$id"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

/** Use one back-stack policy for the bottom bar and every shortcut that opens a bottom tab. */
fun NavController.navigateToBottomTab(route: String) {
    check(bottomTabSpecs().any { it.route == route }) { "Not a bottom-tab route: $route" }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun FloatingBottomBar(
    tabs: List<TabItem>,
    currentDestination: androidx.navigation.NavDestination?,
    onClick: (TabItem) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .shadow(2.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp))
            .padding(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                Box(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        )
                        .nmlPressable(onClick = { if (!selected) onClick(tab) }, pressScale = 0.96f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        androidx.compose.material3.Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(23.dp),
                        )
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainNavHost(playerEntry: Boolean? = null, onPlayerEntryConsumed: () -> Unit = {}) {
    val navController = rememberNavController()
    val controller: PlaybackController = koinInject()
    val hasTrack by remember(controller) {
        controller.state.map { it.current != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = controller.state.value.current != null)
    val togetherRepository: TogetherRepository = koinInject()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val route = currentDestination?.route
    val isFullscreenPlayer = route == Routes.PLAYER_DESTINATION || route == Routes.MV
    // Draw an art skin once for the whole activity, before Scaffold consumes the status-bar
    // inset. Drawing it only inside Scaffold starts the crop below the status bar and leaves a
    // conspicuous solid-colour strip above it.
    val useFullWindowArtSkin = !isFullscreenPlayer &&
        artSkinDrawable(LocalNmlThemeKind.current) != null
    val scaffoldContainerColor = when {
        useFullWindowArtSkin -> Color.Transparent
        isFullscreenPlayer -> Color(0xFF0B1018)
        else -> MaterialTheme.colorScheme.background
    }
    SystemBarAppearance(
        darkIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f &&
            !isFullscreenPlayer,
        transparentPlayerBars = isFullscreenPlayer,
        transparentStatusBar = useFullWindowArtSkin,
    )
    LaunchedEffect(playerEntry) {
        playerEntry?.let { lyrics ->
            navController.navigate(Routes.player(lyrics)) { launchSingleTop = true }
            onPlayerEntryConsumed()
        }
    }
    val pendingImport by PlaylistImportRequestStore.pendingText.collectAsStateWithLifecycle()
    LaunchedEffect(pendingImport) {
        if (!pendingImport.isNullOrBlank() && currentDestination?.route != Routes.PLAYLIST_IMPORT) {
            navController.navigate(Routes.PLAYLIST_IMPORT) { launchSingleTop = true }
        }
    }
    var showQueue by remember { mutableStateOf(false) }
    val roomPollingOwner = remember(togetherRepository) { Any() }
    val trackRoom = BuildConfig.FEATURE_TOGETHER && hasTrack &&
        currentDestination?.route != Routes.TOGETHER && currentDestination?.route != Routes.TOGETHER_ROOM
    LifecycleStartEffect(trackRoom, togetherRepository) {
        if (trackRoom) togetherRepository.startPolling(0L, roomPollingOwner)
        onStopOrDispose { if (trackRoom) togetherRepository.stopPolling(roomPollingOwner) }
    }

    val tabs = remember { bottomTabSpecs().map { spec ->
        TabItem(
            route = spec.route,
            label = spec.label,
            icon = when (spec.route) {
                Routes.HOME -> Icons.Default.Home
                Routes.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
                Routes.NOTES -> Icons.Default.EditNote
                else -> Icons.Default.LibraryMusic
            },
        )
    } }

    val showBottomBar = tabs.any { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route } == true
    }
    val showMiniPlayer = hasTrack && route != null && !isFullscreenPlayer

    Box(
        Modifier
            .fillMaxSize()
            .then(if (useFullWindowArtSkin) Modifier.nmlPageBackground() else Modifier),
    ) {
        Scaffold(
            contentWindowInsets = if (isFullscreenPlayer) WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) else WindowInsets.safeDrawing,
            containerColor = scaffoldContainerColor,
            // Transparent art pages otherwise inherit a black default content colour from
            // Scaffold, leaving labels unreadable over dark paintings.
            contentColor = if (useFullWindowArtSkin) MaterialTheme.colorScheme.onSurface
                else contentColorFor(scaffoldContainerColor),
            bottomBar = {
                if (showMiniPlayer || showBottomBar) {
                    Column {
                        if (showMiniPlayer) {
                            LiveMiniPlayer(
                                controller = controller,
                                togetherRepository = togetherRepository,
                                onClick = { navController.navigate(Routes.PLAYER) },
                                onToggle = { controller.toggle() },
                                onQueue = { showQueue = true },
                            )
                        }
                        if (showBottomBar) {
                            FloatingBottomBar(
                                tabs = tabs,
                                currentDestination = currentDestination,
                                onClick = { tab ->
                                    navController.navigateToBottomTab(tab.route)
                                },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .then(if (useFullWindowArtSkin) Modifier else Modifier.nmlPageBackground()),
            ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                enterTransition = {
                    fadeIn(animationSpec = tween(180)) +
                        slideInHorizontally(animationSpec = tween(200)) { it / 14 }
                },
                exitTransition = { fadeOut(animationSpec = tween(140)) },
                popEnterTransition = { fadeIn(animationSpec = tween(160)) },
                popExitTransition = {
                    fadeOut(animationSpec = tween(140)) +
                        slideOutHorizontally(animationSpec = tween(180)) { it / 18 }
                },
            ) {
                composable(Routes.HOME) { HomeScreen(navController) }
                composable(Routes.DAILY) { DailyScreen(navController) }
                composable(Routes.SEARCH) { SearchScreen(navController) }
                composable(Routes.ARTIST_SEARCH) { entry ->
                    SearchScreen(navController, initialKeyword = entry.arguments?.getString("query").orEmpty())
                }
                composable(
                    Routes.ARTIST,
                    arguments = listOf(navArgument("id") { type = androidx.navigation.NavType.LongType }),
                ) { entry -> ArtistDetailScreen(navController, entry.arguments?.getLong("id") ?: 0L) }
                composable(Routes.PLAYLISTS) { PlaylistHubScreen(navController) }
                composable(Routes.NOTES) { NotesScreen(navController) }
                composable(Routes.LIBRARY) { LibraryScreen(navController) }
                composable(Routes.PLAYLIST_IMPORT) { PlaylistImportScreen(navController) }
                composable(Routes.LIKED) { LikedSongsScreen(navController) }
                composable(Routes.TOPLISTS) { ToplistsScreen(navController) }
                composable(
                    Routes.PLAYER_DESTINATION,
                    arguments = listOf(navArgument("lyrics") { type = androidx.navigation.NavType.BoolType; defaultValue = false }),
                ) { entry -> PlayerScreen(navController, startWithLyrics = entry.arguments?.getBoolean("lyrics") == true) }
                composable(
                    Routes.PLAYLIST,
                    arguments = listOf(navArgument("id") { type = androidx.navigation.NavType.LongType }),
                ) { entry ->
                    PlaylistScreen(navController, entry.arguments?.getLong("id") ?: 0)
                }
                composable(
                    Routes.MV,
                    arguments = listOf(navArgument("id") { type = androidx.navigation.NavType.LongType }),
                ) { entry ->
                    MvScreen(navController, entry.arguments?.getLong("id") ?: 0L)
                }
                composable(
                    Routes.USER,
                    arguments = listOf(navArgument("uid") { type = androidx.navigation.NavType.LongType }),
                ) { entry -> UserProfileScreen(navController, entry.arguments?.getLong("uid") ?: 0) }
                composable(
                    Routes.FOLLOWS,
                    arguments = listOf(
                        navArgument("uid") { type = androidx.navigation.NavType.LongType },
                        navArgument("tab") {
                            type = androidx.navigation.NavType.IntType
                            defaultValue = 0
                        },
                    ),
                ) { entry ->
                    FollowsScreen(
                        navController = navController,
                        uid = entry.arguments?.getLong("uid") ?: 0,
                        initialTab = followsInitialTab(entry.arguments?.getInt("tab") ?: 0),
                    )
                }
                composable(Routes.LOCAL) { LocalMusicScreen(navController) }
                composable(Routes.SIGNIN) { SigninScreen(navController) }
                composable(Routes.SETTINGS) { SettingsScreen(navController) }
                if (BuildConfig.FEATURE_MSG) {
                    composable(Routes.MSGS) { MsgsScreen(navController) }
                    composable(
                        Routes.MSGS_USER,
                        arguments = listOf(navArgument("uid") { type = androidx.navigation.NavType.LongType }),
                    ) { entry ->
                        MsgsScreen(navController, initialUserId = entry.arguments?.getLong("uid"))
                    }
                }
                if (BuildConfig.FEATURE_TOGETHER) {
                    composable(Routes.TOGETHER) { TogetherScreen(navController) }
                    composable(
                        Routes.TOGETHER_ROOM,
                        arguments = listOf(navArgument("code") { type = androidx.navigation.NavType.StringType }),
                    ) { entry -> TogetherRoomScreen(entry.arguments?.getString("code") ?: "") }
                }
            }

            if (showQueue) {
                LivePlaybackQueue(
                    controller = controller,
                    onDismiss = { showQueue = false },
                    onPlay = { index ->
                        controller.playIndex(index)
                        showQueue = false
                    },
                    onRemove = controller::removeAt,
                    onMove = controller::move,
                )
            }
            }
        }
    }

    // 评论底栏（Full 版，从播放页呼出）
    if (BuildConfig.FEATURE_COMMENT) {
        CommentSheet()
    }
}

@Composable
private fun LiveMiniPlayer(
    controller: PlaybackController,
    togetherRepository: TogetherRepository,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onQueue: () -> Unit,
) {
    val metadata by remember(controller) { controller.state.playbackMetadata() }
        .collectAsStateWithLifecycle(initialValue = controller.state.value.copy(positionMs = 0L, bufferedMs = 0L))
    // Keep the State object unread here: only the progress indicator observes its value.
    val position = remember(controller) { controller.state.map { it.positionMs }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = controller.state.value.positionMs)
    val room by togetherRepository.room.collectAsStateWithLifecycle()
    MiniPlayerBar(
        state = metadata,
        playing = metadata.phase == PlayPhase.PLAYING,
        onClick = onClick,
        onToggle = onToggle,
        onQueue = onQueue,
        togetherMembers = if (BuildConfig.FEATURE_TOGETHER && room.inRoom) {
            room.members.take(2).map { MiniPlayerMember(it.nickname, it.avatarUrl) }
        } else emptyList(),
        positionMs = remember(position) { { position.value } },
    )
}

@Composable
private fun LivePlaybackQueue(
    controller: PlaybackController,
    onDismiss: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val metadata by remember(controller) { controller.state.playbackMetadata() }
        .collectAsStateWithLifecycle(initialValue = controller.state.value.copy(positionMs = 0L, bufferedMs = 0L))
    PlaybackQueueSheet(metadata, onDismiss, onPlay, onRemove, onMove)
}
