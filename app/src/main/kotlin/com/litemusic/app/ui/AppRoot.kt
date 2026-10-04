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
import androidx.compose.ui.graphics.Color
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
fun AppRoot(playerEntry: Boolean? = null, onPlayerEntryConsumed: () -> Unit = {}) {
    val settings: SettingsStore = koinInject()
    val themeFlow = remember(settings) { settings.theme.catch { emit("light") } }
    val theme by themeFlow.collectAsStateWithLifecycle(initialValue = "")
    val auth: AuthStore = koinInject()
    val sessionFlow = remember(auth) {
        auth.session.map<AuthStore.Session, AuthStore.Session?> { it }.catch { emit(AuthStore.Session()) }
    }
    val session by sessionFlow.collectAsStateWithLifecycle(initialValue = null)

    // Wait for persisted settings before the first app page. This prevents a light flash when an
    // art skin (or a dark skin) was selected on the previous launch.
    if (theme.isBlank() || session == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF101923)))
        return
    }
    val activeSession = session ?: return
    // Kept outside the theme key: changing a skin must not recreate the navigation graph or
    // replay the cold-start overlay while the user is already browsing a page.
    var showStartupSkin by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        showStartupSkin = false
    }

    NmTheme(themeKind = theme) {
        Box(Modifier.fillMaxSize()) {
            if (activeSession.loggedIn) {
                MainNavHost(playerEntry, onPlayerEntryConsumed)
            } else {
                LoginScreen()
            }
            AnimatedVisibility(
                visible = showStartupSkin,
                exit = fadeOut(animationSpec = tween(160)),
            ) {
                StartupSkin(theme)
            }
        }
    }
}

@Composable
private fun StartupSkin(theme: String) {
    val art = artSkinDrawable(theme)
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
                // A transparent bar above Scaffold's safe inset exposes the system contrast
                // scrim on some Android 15/16 devices, which appeared as a blank white strip.
                window.statusBarColor = opaqueStatusBarColor
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
