package com.litemusic.app.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.runtime.snapshotFlow
import android.os.SystemClock
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import com.litemusic.design.components.NmlButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.litemusic.app.BuildConfig
import com.litemusic.app.data.TogetherRepository
import com.litemusic.app.feature.comment.CommentSheetController
import com.litemusic.app.ui.Routes
import com.litemusic.app.ui.artSkinDrawable
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.design.components.NmHaptic
import com.litemusic.design.components.TogetherIcon
import com.litemusic.design.components.VinylDisc
import com.litemusic.design.components.WordLyricText
import com.litemusic.design.components.glassBlur
import com.litemusic.design.components.rememberHaptic
import com.litemusic.design.components.nmlPressable
import com.litemusic.design.theme.LocalNmlThemeKind
import com.litemusic.design.theme.isNmlArtTheme
import androidx.compose.material3.RadioButton
import com.litemusic.lyric.LyricDoc
import com.litemusic.lyric.LyricEngine
import com.litemusic.lyric.LyricLine
import com.litemusic.lyric.LyricSection
import com.litemusic.lyric.LyricUiLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.litemusic.shared.player.PlayMode
import com.litemusic.shared.player.PlayPhase
import com.litemusic.shared.model.Artist
import com.litemusic.shared.model.Song
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 播放页（设计规范 P4-6）：
 * 顶部栏 47dp / 黑胶 284 ⇄ 逐字歌词 双页（点黑胶进歌词、点歌词回黑胶）/ 四操作行 /
 * 进度条 / 主控制行。背景为模糊封面 + rgba(10,6,8,.35) 压暗 + 上下渐变。
 *
 * 性能（#6）：歌词文档只在歌词变化时重建一次；当前行走 derivedStateOf，
 * 进度推进只重组读取该状态的组件，不再每帧重建 LyricDoc。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    navController: NavController,
    startWithLyrics: Boolean = false,
    viewModel: PlayerViewModel = koinViewModel(),
) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    val lyricState by viewModel.lyric.collectAsStateWithLifecycle()
    val likedIds by viewModel.liked.collectAsStateWithLifecycle()
    val sleepTimer by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val lyricEngine: LyricEngine = koinInject()
    val settings: SettingsStore = koinInject()
    val together: TogetherRepository = koinInject()
    val togetherRoom by together.room.collectAsStateWithLifecycle()
    val glass by settings.glassBlur.collectAsStateWithLifecycle(initialValue = true)
    val haptic = rememberHaptic()

    var showLyrics by remember { mutableStateOf(startWithLyrics) }
    var showQueue by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showTogether by remember { mutableStateOf(false) }
    var showSongActions by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    val current = state.current
    LaunchedEffect(startWithLyrics, current?.id) {
        if (startWithLyrics && current != null) showLyrics = true
    }
    val lyricImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel.importPickedLocalLyric(uri) }

    LaunchedEffect(Unit) {
        together.refresh()
    }

    // 歌词文档：只随歌词变化重建（原先每帧 map 重建，是切歌/滚动凝滞的主因）
    val lyricDoc = remember(lyricState.lines) {
        LyricDoc(
            lines = lyricState.lines.map { LyricLine(it.timeMs, it.text) },
            wordLines = lyricState.lines.mapIndexedNotNull { i, l ->
                if (l.words.isEmpty()) null else i to l.words
            }.toMap(),
        )
    }
    val stateRef = rememberUpdatedState(state)
    val currentLine by remember(lyricDoc) {
        derivedStateOf { lyricEngine.currentLine(lyricDoc, stateRef.value.positionMs) }
    }
    val lyricSections = remember(lyricDoc) { lyricEngine.detectSections(lyricDoc.lines) }

    if (showQueue) {
        PlaybackQueueSheet(
            state = state,
            onDismiss = { showQueue = false },
            onPlay = { index -> viewModel.playIndex(index) },
            onRemove = viewModel::removeAt,
            onMove = viewModel::move,
        )
    }
    if (showQuality) {
        ModalBottomSheet(onDismissRequest = { showQuality = false }) {
            QualitySheetContent(
                currentCode = current?.quality?.code ?: "",
                onPick = { q ->
                    viewModel.setQuality(q)
                    showQuality = false
                },
            )
        }
    }
    if (showTogether) {
        ModalBottomSheet(onDismissRequest = { showTogether = false }) {
            TogetherRoomPreview(togetherRoom, navController) { showTogether = false }
        }
    }
    if (showSleepTimer) {
        ModalBottomSheet(
            onDismissRequest = { showSleepTimer = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SleepTimerSheet(
                state = sleepTimer,
                onMinutes = { viewModel.startSleepTimer(it); showSleepTimer = false },
                onCurrentTrack = { viewModel.stopAfterCurrentTrack(); showSleepTimer = false },
                onCancel = { viewModel.cancelSleepTimer(); showSleepTimer = false },
            )
        }
    }

    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无播放内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (showSongActions) {
        SongActionSheet(
            song = Song(id = current.id, name = current.title, ar = listOf(Artist(name = current.artist))),
            onDismiss = { showSongActions = false },
            onLike = { viewModel.toggleLike(current.id) },
        )
    }

    val playing = state.phase == PlayPhase.PLAYING
    val playbackPending = state.phase == PlayPhase.LOADING
    val duration = state.durationMs.takeIf { it > 0 } ?: current.durationMs
    val position = state.positionMs
    val progress = playerProgressFraction(position, duration)
    val themeKind = LocalNmlThemeKind.current
    val artSkin = artSkinDrawable(themeKind)
    val artTheme = isNmlArtTheme(themeKind)
    // Text remains white on the immersive layer; the action color follows the selected skin.
    val accent = if (artTheme) MaterialTheme.colorScheme.primary else Color(0xFFFFB4AB)
    val playerScrim = remember(artTheme) {
        val middle = if (artTheme) Color(0xB80A1B2A) else Color(0xB8081225)
        Brush.verticalGradient(listOf(Color(0xD9081225), middle, Color(0xCC081225), Color(0xF2081225)))
    }

    Box(Modifier.fillMaxSize()) {
        // 背景：模糊封面（API 31+ 走 RenderEffect 硬件加速）
        Box(Modifier.fillMaxSize().then(if (glass && artSkin == null) Modifier.glassBlur(28f) else Modifier)) {
            if (artSkin != null) {
                Image(
                    painter = painterResource(artSkin),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    model = current.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 压暗 + 上下渐变，保证文字对比度
        Box(
            Modifier.fillMaxSize().background(
                playerScrim
            ),
        )

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- 顶部栏 47dp ----
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        current.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current.artist,
                        color = Color(0xB3FFFFFF),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", tint = Color.White)
                }
                IconButton(onClick = { showSongActions = true }) {
                    Icon(Icons.Default.MoreVert, "歌曲操作", tint = Color.White)
                }
            }

            // The service already maps Media3 diagnostics to short Chinese guidance.  Keep it
            // in the player rather than hiding a failed tap behind the play button; onPlaying()
            // clears it as soon as playback recovers.
            (state.error?.takeIf { state.phase == PlayPhase.ERROR })?.let { message ->
                Text(
                    message,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xB82B1720))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }

            if (togetherRoom.inRoom && togetherRoom.members.isNotEmpty()) {
                TogetherPresence(
                    room = togetherRoom,
                    onClick = { showTogether = true },
                )
            }

            // ---- 翻页区：黑胶 ⇄ 歌词 ----
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (!showLyrics) {
                    Box(
                        Modifier.fillMaxWidth().clickable { showLyrics = true; haptic(NmHaptic.TICK) },
                        contentAlignment = Alignment.Center,
                    ) {
                        VinylDisc(
                            coverUrl = current.coverUrl,
                            spinning = playing,
                            modifier = Modifier.fillMaxWidth(0.68f),
                            accent = if (artTheme) accent else null,
                        )
                    }
                    Text(
                        "轻触唱片查看歌词",
                        color = Color(0x66FFFFFF),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    )
                } else {
                    LyricsPanel(
                        lyricEngine = lyricEngine,
                        doc = lyricDoc,
                        lyricsSourceKey = current.id,
                        lines = lyricState.lines,
                        emptyMessage = lyricState.message,
                        showLocalImport = current.isLocal,
                        showTranslation = true,
                        position = position,
                        currentLine = currentLine,
                        playing = playing,
                        accent = accent,
                        sections = lyricSections,
                        onSeekToLine = { index ->
                            lyricState.lines.getOrNull(index)?.let {
                                viewModel.seekTo(it.timeMs)
                                haptic(NmHaptic.CONFIRM)
                            }
                        },
                        onTogglePlayback = viewModel::toggle,
                        onImportLocalLyric = {
                            if (viewModel.prepareLocalLyricImport(current)) {
                                lyricImportLauncher.launch(arrayOf("*/*"))
                            }
                        },
                        onTap = { showLyrics = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        "轻触歌词返回唱片",
                        color = Color(0x4DFFFFFF),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                    )
                }
            }

            // ---- 进度条 + 拖动预览歌词 ----
            var dragValue by remember { mutableStateOf<Float?>(null) }
            val previewMs = dragValue?.let { (it * duration).toLong() }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (togetherRoom.inRoom && togetherRoom.members.isNotEmpty()) {
                    TogetherProgressPresence(
                        room = togetherRoom,
                        onClick = { showTogether = true },
                    )
                }
                previewMs?.let { ms ->
                    val previewLine = lyricEngine.currentLine(lyricDoc, ms)
                    if (previewLine >= 0) {
                        Text(
                            lyricState.lines.getOrNull(previewLine)?.text ?: "",
                            color = Color(0xCCFFFFFF),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                HighPointProgressSlider(
                    value = dragValue ?: progress,
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        dragValue?.let { viewModel.seekTo((it * duration).toLong()) }
                        dragValue = null
                    },
                    sections = lyricSections,
                    durationMs = duration,
                    accent = accent,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(position), color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelMedium)
                }
            }

            // ---- 主控制行 ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::cycleMode) {
                    Icon(
                        modeIcon(state.playMode),
                        contentDescription = state.playMode.name,
                        tint = Color(0xCCFFFFFF),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = viewModel::previous) {
                    Icon(Icons.Default.SkipPrevious, "上一首", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .size(74.dp)
                        .clip(CircleShape)
                        .nmlPressable(onClick = viewModel::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(74.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.24f),
                        strokeWidth = 3.dp,
                    )
                    Box(
                        Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                    Icon(
                        if (playing || playbackPending) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = when {
                            playing -> "暂停"
                            playbackPending -> "取消播放"
                            else -> "播放"
                        },
                        tint = accent,
                        modifier = Modifier.size(38.dp),
                    )
                    }
                }
                IconButton(onClick = viewModel::next) {
                    Icon(Icons.Default.SkipNext, "下一首", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(
                        Icons.Default.GraphicEq,
                        "歌词",
                        tint = if (showLyrics) accent else Color(0xCCFFFFFF),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // ---- 四操作行：喜欢 / 音质 / 评论 / 一起听 ----
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                val liked = current.id in likedIds
                ActionCell(
                    label = "喜欢",
                    tint = if (liked) accent else Color(0xCCFFFFFF),
                    onClick = { viewModel.toggleLike(current.id) },
                ) {
                    Icon(
                        if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "喜欢",
                        tint = if (liked) accent else Color(0xCCFFFFFF),
                        modifier = Modifier.size(23.dp),
                    )
                }
                ActionCell(label = "定时", tint = Color(0xCCFFFFFF), onClick = { showSleepTimer = true }) {
                    Text(if (sleepTimer.mode == com.litemusic.player.SleepTimerMode.OFF) "定时" else "已设", color = Color(0xE6FFFFFF), style = MaterialTheme.typography.labelMedium)
                }
                ActionCell(
                    label = "音质",
                    tint = Color(0xCCFFFFFF),
                    onClick = { showQuality = true },
                ) {
                    Text(
                        current.quality.code,
                        color = Color(0xE6FFFFFF),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (BuildConfig.FEATURE_COMMENT) {
                    ActionCell(
                        label = "评论",
                        tint = Color(0xCCFFFFFF),
                        onClick = { CommentSheetController.open(current.id, current.commentThreadId) },
                    ) {
                        Icon(Icons.Default.Comment, "评论", tint = Color(0xCCFFFFFF), modifier = Modifier.size(23.dp))
                    }
                }
                if (BuildConfig.FEATURE_TOGETHER) {
                    ActionCell(
                        label = "一起听",
                        tint = Color(0xCCFFFFFF),
                        onClick = { navController.navigate(Routes.TOGETHER) },
                    ) {
                        TogetherIcon(
                            modifier = Modifier.size(23.dp),
                            tint = Color(0xCCFFFFFF),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TogetherPresence(
    room: TogetherRepository.RoomState,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TogetherAvatarStack(
            room = room,
            avatarSize = 46.dp,
            avatarOverlap = 10.dp,
        )
    }
}

@Composable
private fun TogetherProgressPresence(
    room: TogetherRepository.RoomState,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TogetherAvatarStack(room = room, avatarSize = 28.dp, avatarOverlap = 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            "正在一起听 · ${room.members.size} 人",
            color = Color(0xCCFFFFFF),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun TogetherAvatarStack(
    room: TogetherRepository.RoomState,
    avatarSize: androidx.compose.ui.unit.Dp,
    avatarOverlap: androidx.compose.ui.unit.Dp,
) {
    val offsets = togetherAvatarXOffsets(
        memberCount = room.members.size,
        avatarSizeDp = avatarSize.value.toInt(),
        overlapDp = avatarOverlap.value.toInt(),
    )
    Box(
        Modifier.size(
            width = avatarSize + (offsets.lastOrNull() ?: 0).dp,
            height = avatarSize,
        ),
    ) {
        room.members.take(2).forEachIndexed { index, member ->
            AsyncImage(
                model = member.avatarUrl,
                contentDescription = member.nickname,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(x = offsets[index].dp)
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(Color(0x22FFFFFF))
                    .padding(1.dp),
            )
        }
    }
}

@Composable
private fun TogetherRoomPreview(room: TogetherRepository.RoomState, navController: NavController, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("一起听", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            room.members.take(2).forEach { member ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(member.avatarUrl, member.nickname, Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Text(member.nickname, Modifier.padding(top = 8.dp), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(if (room.inRoom) "${room.members.size} 人正在一起听" else "暂未连接一起听房间")
        Button(onClick = { onDismiss(); navController.navigate(Routes.TOGETHER) { launchSingleTop = true } }) { Text("房间管理") }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HighPointProgressSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    sections: List<LyricSection>,
    durationMs: Long,
    accent: Color,
) {
    val markers = remember(sections, durationMs) {
        sectionProgressFractions(sections, durationMs)
    }
    val sliderColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color(0x4DFFFFFF),
    )
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            colors = sliderColors,
            thumb = {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    colors = sliderColors,
                )
            },
        )
        markers.forEachIndexed { index, fraction ->
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = maxWidth * fraction - 3.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            if (index == 0) {
                Text(
                    "高潮",
                    color = accent,
                    fontSize = 9.sp,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = maxWidth * fraction - 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionCell(
    label: String,
    tint: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.width(64.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).nmlPressable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(23.dp), contentAlignment = Alignment.Center) { content() }
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color(0xDDFFFFFF), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun QualitySheetContent(currentCode: String, onPick: (com.litemusic.shared.util.Quality) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "音质选择",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
        )
        com.litemusic.shared.util.Quality.entries.forEach { q ->
            val picked = q.code == currentCode
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (picked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .nmlPressable(onClick = { onPick(q) })
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        q.label + "（" + q.code + "）",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (picked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when (q) {
                            com.litemusic.shared.util.Quality.STANDARD -> "128 kbps · 省流量"
                            com.litemusic.shared.util.Quality.HIGH -> "192 kbps · 均衡"
                            com.litemusic.shared.util.Quality.EXHIGH -> "320 kbps · 推荐，流量较大"
                            com.litemusic.shared.util.Quality.LOSSLESS -> "FLAC 无损 · 需 VIP，流量最大"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (picked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RadioButton(selected = picked, onClick = null)
            }
        }
        Text(
            "切换音质会重新解析播放地址，进度保持不变",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LyricsPanel(
    lyricEngine: LyricEngine,
    doc: LyricDoc,
    lyricsSourceKey: Long,
    lines: List<LyricUiLine>,
    emptyMessage: String?,
    showLocalImport: Boolean,
    showTranslation: Boolean,
    position: Long,
    currentLine: Int,
    playing: Boolean,
    accent: Color,
    sections: List<LyricSection>,
    onSeekToLine: (Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onImportLocalLyric: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A song switch must not inherit the previous song's viewport or a pending
    // automatic scroll job.
    val listState = remember(lyricsSourceKey) { androidx.compose.foundation.lazy.LazyListState() }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var browseState by remember { mutableStateOf(LyricBrowseState()) }
    var userDragActive by remember { mutableStateOf(false) }
    val fallbackLyricItemHeight = with(LocalDensity.current) { 88.dp.roundToPx() }

    // 切歌、重新载入歌词或从唱片页重新进入时都回到播放跟随状态。
    LaunchedEffect(lyricsSourceKey, doc) {
        browseState = browseState.resetForLyricsSource()
        userDragActive = false
    }

    // collectIsDraggedAsState 只代表真实手势；程序滚动不进入手动浏览状态。
    LaunchedEffect(listState, lines) {
      snapshotFlow { isUserDragging to listState.isScrollInProgress }.collect { (dragging, scrolling) ->
        if (dragging) userDragActive = true
        if (userDragActive && !dragging && !scrolling) {
            val layout = listState.layoutInfo
            val visibleCenters = layout.visibleItemsInfo.mapNotNull { item ->
                val lineIndex = item.index - 1 // 顶部虚拟留白占据第 0 项
                if (lineIndex in lines.indices) lineIndex to (item.offset + item.size / 2) else null
            }
            nearestLyricLineIndex(
                itemCenters = visibleCenters,
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
            )?.let { index ->
                browseState = browseState.afterUserRelease(index, SystemClock.elapsedRealtime())
            }
            userDragActive = false
        }
      }
    }

    // 手动浏览只暂停短暂一段时间；按照剩余时间准确唤醒，不能只等一次后遗留暂停状态。
    LaunchedEffect(browseState, userDragActive) {
        if (browseState.selectedLineIndex != null && !userDragActive) {
            val remaining = browseState.remainingFollowPauseMs(SystemClock.elapsedRealtime())
            if (remaining > 0L) delay(remaining)
            browseState = browseState.resumeWhenDue(SystemClock.elapsedRealtime())
        }
    }

    // 仅对目标行执行一次原生平滑定位。为目标设置相对视口中心的偏移，
    // 不再先瞬移到目标再做第二段中心校正。
    LaunchedEffect(lyricsSourceKey, doc, listState, currentLine, browseState, userDragActive, isUserDragging) {
        if (
            isUserDragging || userDragActive ||
            currentLine !in lines.indices ||
            !browseState.followsPlaybackAt(SystemClock.elapsedRealtime())
        ) return@LaunchedEffect
        val targetItemIndex = currentLine + 1
        val layout = snapshotFlow { listState.layoutInfo }
            .first { it.totalItemsCount >= lines.size + 2 && it.viewportEndOffset > it.viewportStartOffset }
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        val targetHeight = layout.visibleItemsInfo
            .firstOrNull { it.index == targetItemIndex }
            ?.size
            ?: fallbackLyricItemHeight
        listState.animateScrollToItem(
            index = targetItemIndex,
            scrollOffset = lyricCenteredItemScrollOffset(viewport, targetHeight),
        )
    }

    BoxWithConstraints(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().clickable(onClick = onTap),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Spacer(Modifier.height(maxHeight / 2)) }
            itemsIndexed(lines, key = { i, _ -> "l" + i }) { index, line ->
                val isCurrent = index == currentLine
                val section = sections.firstOrNull { index in it.startLine..it.endLine }
                // 只有当前行才做逐字定位，避免整列表每帧计算
                val wordIdx = if (isCurrent && line.words.isNotEmpty()) {
                    lyricEngine.currentWord(doc, index, position)
                } else -1
                val lineContent: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (section != null && index == section.startLine) {
                            Text(
                                "${section.label} · 自动识别",
                                color = accent,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        WordLyricText(
                            line = line,
                            isCurrent = isCurrent,
                            currentWord = wordIdx,
                            highlight = accent,
                            normal = Color.White,
                        )
                        if (showTranslation && line.translation.isNotBlank()) {
                            Text(
                                line.translation,
                                color = if (isCurrent) Color(0xCCFFFFFF) else Color(0x66FFFFFF),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                val selected = browseState.selectedLineIndex == index
                Row(
                    Modifier
                        .fillMaxWidth(0.94f)
                        .heightIn(min = 88.dp)
                        .animateContentSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatTime(line.timeMs), color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(52.dp).alpha(if (selected) 1f else 0f))
                    Box(Modifier.weight(1f).alpha(if (isCurrent || selected) 1f else 0.60f),
                        contentAlignment = Alignment.Center) { lineContent() }
                    IconButton(onClick = {
                        onSeekToLine(index)
                        if (!playing) onTogglePlayback()
                        browseState = browseState.resumePlaybackFollow()
                    }, enabled = selected, modifier = Modifier.size(48.dp).alpha(if (selected) 1f else 0f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = if (selected) "从这里播放" else null, tint = Color.White)
                    }
                }
            }
            item { Spacer(Modifier.height(maxHeight / 2)) }
        }
        if (lines.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    emptyMessage ?: "暂无歌词",
                    color = Color(0xCCFFFFFF),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (showLocalImport) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (lines.isNotEmpty() && !emptyMessage.isNullOrBlank()) {
                    Text(
                        emptyMessage,
                        color = Color(0xCCFFFFFF),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Button(
                    onClick = onImportLocalLyric,
                    modifier = Modifier.height(44.dp),
                ) {
                    Text("选择.lrc歌词")
                }
            }
        }
        if (browseState.selectedLineIndex != null && !browseState.followsPlaybackAt(SystemClock.elapsedRealtime())) {
            Text(
                "已暂停自动跟随 · 继续跟随",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.44f))
                    .clickable { browseState = browseState.resumePlaybackFollow() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun QueueSheetContent(
    state: com.litemusic.shared.player.PlayerUiState,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(400.dp)) {
        Text(
            "播放队列（" + state.queue.size + "）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(state.queue, key = { _, item -> item.id }) { index, item ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).clickable { onMove(index, (index - 1).coerceAtLeast(0)) },
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            item.title + (if (item.isLocal) "  [本地]" else ""),
                            style = if (index == state.currentIndex) MaterialTheme.typography.bodyLarge
                            else MaterialTheme.typography.bodyMedium,
                            color = if (index == state.currentIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).clickable { onMove(index, (index + 1).coerceAtMost(state.queue.lastIndex)) },
                    )
                    Text(
                        "✕",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).clickable { onRemove(index) },
                    )
                }
            }
        }
    }
}

private fun modeIcon(mode: PlayMode) = when (mode) {
    PlayMode.SEQUENCE -> Icons.Default.Repeat
    PlayMode.REPEAT_ALL -> Icons.Default.Repeat
    PlayMode.REPEAT_ONE -> Icons.Default.RepeatOne
    PlayMode.SHUFFLE -> Icons.Default.Shuffle
}

internal fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
}
