package com.litemusic.app.feature.mv

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import com.litemusic.design.components.NmlButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.util.AppResult
import org.koin.compose.koinInject

/**
 * Dedicated, controllable MV surface.  Media3's controller supplies play,
 * pause and seek controls while this screen owns only the short-lived MV URL.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun MvScreen(
    navController: NavController,
    mvId: Long,
    api: NMApi = koinInject(),
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    var streamUrl by remember(mvId) { mutableStateOf<String?>(null) }
    var error by remember(mvId) { mutableStateOf<String?>(null) }
    var loading by remember(mvId) { mutableStateOf(true) }
    var requestVersion by remember(mvId) { mutableIntStateOf(0) }
    val playerViewRef = remember { arrayOfNulls<PlayerView>(1) }
    var dragStartMs by remember { mutableLongStateOf(0L) }
    var dragDistancePx by remember { mutableFloatStateOf(0f) }
    var dragTargetMs by remember { mutableLongStateOf(-1L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playbackException: androidx.media3.common.PlaybackException) {
                error = "MV 播放失败：${playbackException.errorCodeName}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(mvId, requestVersion) {
        loading = true
        error = null
        streamUrl = null
        when (val result = api.getMvUrl(mvId)) {
            is AppResult.Success -> {
                val data = result.data
                val mv = data.data
                if (data.code == 200 && !mv?.url.isNullOrBlank()) {
                    streamUrl = mv?.url
                } else {
                    error = "MV 地址读取失败(${data.code})"
                }
            }
            is AppResult.Failure -> error = result.message.ifBlank { "MV 地址读取失败" }
        }
        loading = false
    }

    LaunchedEffect(streamUrl) {
        streamUrl?.let { url ->
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) {
        if (streamUrl != null) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        playerViewRef[0] = this
                        this.player = player
                        useController = true
                        controllerAutoShow = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            // Keep the native controller's lower seek bar available. A horizontal swipe over
            // the video itself moves 10 seconds per 100 dp. Play/pause remains solely in the
            // Media3 controller so the screen never presents two competing play controls.
            Box(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .pointerInput(player) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragStartMs = player.currentPosition.coerceAtLeast(0L)
                                dragDistancePx = 0f
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                dragDistancePx += amount
                                val duration = player.duration
                                if (duration > 0) {
                                    dragTargetMs = (dragStartMs + (dragDistancePx / density * 100f).toLong())
                                        .coerceIn(0L, duration)
                                }
                            },
                            onDragEnd = {
                                if (dragTargetMs >= 0L) player.seekTo(dragTargetMs)
                                playerViewRef[0]?.showController()
                                dragTargetMs = -1L
                            },
                            onDragCancel = { dragTargetMs = -1L },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (dragTargetMs >= 0L) {
                    Text(
                        "跳转到 %02d:%02d".format(dragTargetMs / 60_000, dragTargetMs / 1_000 % 60),
                        color = Color.White,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.65f), MaterialTheme.shapes.medium)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text("正在加载 MV", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                } else {
                    Text(error ?: "暂时没有可播放的 MV", color = Color.White)
                    Button(onClick = { requestVersion += 1 }, modifier = Modifier.padding(top = 16.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("重试", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
    }
}
