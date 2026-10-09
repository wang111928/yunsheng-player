package com.litemusic.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import android.graphics.Color as AndroidColor
import android.os.Build
import android.content.ContextWrapper
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.litemusic.app.feature.auth.LoginScreen
import com.litemusic.data.prefs.AuthStore
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.design.theme.NmTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import org.koin.compose.koinInject

@Composable
fun AppRoot(
    playerEntry: Boolean? = null,
    onPlayerEntryConsumed: () -> Unit = {},
    startupTheme: String = "light",
    showStartupOnCreate: Boolean = true,
) {
    val settings: SettingsStore = koinInject()
    val themeFlow = remember(settings) { settings.theme.catch { emit("light") } }
    val theme by themeFlow.collectAsStateWithLifecycle(initialValue = "")
    val auth: AuthStore = koinInject()
    val sessionFlow = remember(auth) {
        auth.session.map<AuthStore.Session, AuthStore.Session?> { it }.catch { emit(AuthStore.Session()) }
    }
    val session by sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    val activeSession: AuthStore.Session? = session

    // Settings are asynchronous, but the synchronous mirror supplies the prior theme for this
    // first frame. This also covers the unauthenticated screen while the session is loading.
    val displayedTheme = theme.ifBlank { startupTheme }
    val contentReady = theme.isNotBlank() && session != null
    // Keep the overlay out of normal Activity restoration and task returns. A cold start fades
    // away only after its artwork has been drawn and both persisted flows are ready.
    var showStartupSkin by remember(showStartupOnCreate) {
        mutableStateOf(showStartupOnCreate)
    }
    LaunchedEffect(contentReady) {
        if (contentReady && showStartupSkin) {
            withFrameNanos { }
            showStartupSkin = false
        }
    }

    NmTheme(themeKind = displayedTheme) {
        Box(Modifier.fillMaxSize()) {
            when {
                activeSession?.loggedIn == true -> MainNavHost(playerEntry, onPlayerEntryConsumed)
                activeSession != null -> LoginScreen()
                else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
            AnimatedVisibility(
                visible = showStartupSkin,
                exit = fadeOut(animationSpec = tween(160)),
            ) {
                StartupSkin(displayedTheme)
            }
        }
    }
}

@Composable
private fun StartupSkin(theme: String) {
    val art = startupSkinDrawable(theme)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (art != null) {
            Image(
                painter = painterResource(art),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun SystemBarAppearance(
    darkIcons: Boolean,
    transparentPlayerBars: Boolean = false,
    transparentStatusBar: Boolean = false,
    statusBarColorOverride: Int? = null,
) {
    val view = LocalView.current
    val opaqueStatusBarColor = statusBarColorOverride ?: pageTopColor().toArgb()
    SideEffect {
        var activityContext = view.context
        while (activityContext is ContextWrapper && activityContext !is Activity) activityContext = activityContext.baseContext
        (activityContext as? Activity)?.window?.let { window ->
            if (transparentPlayerBars) {
                window.statusBarColor = AndroidColor.TRANSPARENT
                window.navigationBarColor = AndroidColor.TRANSPARENT
            } else {
                // Artwork is drawn by MainNavHost before Scaffold applies its safe drawing
                // padding, so the status area can share the same image without a solid seam.
                // Other pages keep their opaque color: some Android 15/16 devices add a
                // contrast scrim above a transparent safe inset, which otherwise reads as a
                // blank strip.
                window.statusBarColor = if (transparentStatusBar) AndroidColor.TRANSPARENT else opaqueStatusBarColor
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = !transparentPlayerBars
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = darkIcons
                isAppearanceLightNavigationBars = darkIcons
            }
        }
    }
}
