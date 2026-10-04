package com.litemusic.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.litemusic.app.feature.playlist.importing.PlaylistImportRequestStore
import com.litemusic.app.feature.playlist.importing.readImportBytes
import com.litemusic.app.feature.playlist.importing.shouldApplyImportPreview
import com.litemusic.app.ui.AppRoot
import com.litemusic.app.ui.StartupPresentationTracker
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.data.prefs.StartupThemePreferences
import com.litemusic.player.PlaybackController
import com.litemusic.player.PlaybackService
import org.koin.android.ext.android.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private var playerEntry by mutableStateOf<Boolean?>(null)
    private var startupTheme by mutableStateOf("light")
    private var waitingForStartupThemeMirror = false
    private var importEntryGeneration = 0L
    private var importEntryJob: Job? = null
    private var pendingImportSource: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemSplash = installSplashScreen()
        // Android 12's system splash can only display a window background and an icon. Remove
        // it as soon as Compose is ready; themed full-screen artwork is rendered by AppRoot.
        systemSplash.setOnExitAnimationListener { splashScreenViewProvider ->
            splashScreenViewProvider.remove()
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        startupTheme = StartupThemePreferences.read(this)
        waitingForStartupThemeMirror = !StartupThemePreferences.hasSnapshot(this)
        systemSplash.setKeepOnScreenCondition { waitingForStartupThemeMirror }
        if (waitingForStartupThemeMirror) {
            // Existing installations get a one-time DataStore-to-mirror migration before their
            // first themed launch. This waits for data only, never for login or a timer.
            lifecycleScope.launch {
                startupTheme = runCatching { get<SettingsStore>().theme.first() }
                    .getOrDefault(startupTheme)
                waitingForStartupThemeMirror = false
            }
        }
        val showStartupOnCreate = StartupPresentationTracker.beginMainActivity()
        readPlaybackEntry(intent)
        if (savedInstanceState == null) {
            readPlaylistImportEntry(intent)
        } else {
            savedInstanceState.getString("pendingPlaylistText")?.let(PlaylistImportRequestStore::submit)
            @Suppress("DEPRECATION")
            val pendingSource = savedInstanceState.getParcelable<Intent>("pendingPlaylistSource")
            pendingSource?.let(::readPlaylistImportEntry)
        }

        // Android 16 通知权限：播放控制通知 / 私信通知 / 签到提醒
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        setContent {
            LaunchedEffect(Unit) {
                runCatching { this@MainActivity.get<PlaybackController>().connect() }
            }
            AppRoot(
                playerEntry = playerEntry,
                onPlayerEntryConsumed = { playerEntry = null },
                startupTheme = startupTheme,
                showStartupOnCreate = showStartupOnCreate,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPlaybackEntry(intent)
        readPlaylistImportEntry(intent)
    }

    override fun onDestroy() {
        StartupPresentationTracker.endMainActivity(isChangingConfigurations)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        PlaylistImportRequestStore.pendingText.value?.let { outState.putString("pendingPlaylistText", it) }
        if (importEntryJob?.isActive == true) {
            pendingImportSource?.let { outState.putParcelable("pendingPlaylistSource", it) }
        }
        super.onSaveInstanceState(outState)
    }

    private fun readPlaybackEntry(intent: Intent?) {
        if (intent?.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false) == true) {
            playerEntry = intent.getBooleanExtra(PlaybackService.EXTRA_SHOW_LYRICS, false)
            intent.removeExtra(PlaybackService.EXTRA_OPEN_PLAYER)
            intent.removeExtra(PlaybackService.EXTRA_SHOW_LYRICS)
        }
    }

    /** Only playlist input is handed to the preview. A received share never writes the account. */
    private fun readPlaylistImportEntry(source: Intent?) {
        if (source?.action != Intent.ACTION_VIEW && source?.action != Intent.ACTION_SEND) return
        val generation = ++importEntryGeneration
        importEntryJob?.cancel()
        pendingImportSource = source
        when (source?.action) {
            Intent.ACTION_VIEW -> PlaylistImportRequestStore.submit(source.dataString)
            Intent.ACTION_SEND -> {
                val text = source.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (!text.isNullOrBlank()) {
                    PlaylistImportRequestStore.submit(text)
                } else {
                    val uri = if (Build.VERSION.SDK_INT >= 33) {
                        source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    }
                    if (uri?.scheme != "content") return
                    importEntryJob = lifecycleScope.launch {
                        val content = withContext(Dispatchers.IO) {
                            runCatching {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    val bytes = readImportBytes(stream, 1_048_576)
                                        ?: throw IllegalArgumentException("歌单文件最多 1 MB")
                                    bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                                }
                            }.getOrNull()
                        }
                        if (shouldApplyImportPreview(generation, importEntryGeneration)) {
                            PlaylistImportRequestStore.submit(content)
                            pendingImportSource = null
                        }
                    }
                }
            }
        }
    }

}
