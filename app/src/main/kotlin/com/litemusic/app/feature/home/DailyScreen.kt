package com.litemusic.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.litemusic.design.components.nmlPressable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import com.litemusic.app.data.AuthRepository
import com.litemusic.app.data.HomeRepository
import com.litemusic.app.data.PlaylistRepository
import com.litemusic.app.feature.comment.CommentSheetController
import com.litemusic.app.feature.player.offlinePlayableQueue
import com.litemusic.app.feature.player.offlineQueueStartIndex
import com.litemusic.app.feature.player.offlineUnavailableLabel
import com.litemusic.app.feature.player.rememberOfflineUnavailableIds
import com.litemusic.app.feature.player.SongActionSheet
import com.litemusic.app.feature.playlist.rememberPlaylistPlayer
import com.litemusic.app.feature.search.ArtistInfoDialog
import com.litemusic.app.ui.Routes
import com.litemusic.app.ui.artSkinDrawable
import com.litemusic.design.theme.LocalNmlThemeKind
import com.litemusic.app.util.NetworkStatusMonitor
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.design.components.NmSnackbarHost
import com.litemusic.player.OfflinePlaybackAvailability
import com.litemusic.player.PlaybackController
import com.litemusic.shared.domain.QueueBuilder
import com.litemusic.shared.model.Artist
import com.litemusic.shared.model.DailyStyleCategory
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import com.litemusic.shared.util.Quality
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private val DailyInk: Color @Composable get() = MaterialTheme.colorScheme.background
private val DailyText: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val DailyMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val DailyRed: Color @Composable get() = MaterialTheme.colorScheme.primary
private val DailyBlue: Color @Composable get() = MaterialTheme.colorScheme.secondary

private data class DailyStylePageState(
    val categories: List<DailyStyleCategory> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val selectedTagIds: Set<Long> = emptySet(),
    val touchedCategoryIds: Set<Long> = emptySet(),
)

/** Daily recommendations use their own full-screen presentation, like the reference app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyScreen(
    navController: NavController,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val player = rememberPlaylistPlayer()
    val controller: PlaybackController = koinInject()
    val playlistRepository: PlaylistRepository = koinInject()
    val homeRepository: HomeRepository = koinInject()
    val authRepository: AuthRepository = koinInject()
    val context = LocalContext.current
    val settings: SettingsStore = koinInject()
    val network: NetworkStatusMonitor = koinInject()
    val quality by settings.quality.collectAsState(initial = Quality.EXHIGH)
    val isOnline by network.isOnline.collectAsState()
    val cacheRevision by OfflinePlaybackAvailability.cacheRevision.collectAsState()
    val queueBuilder = remember { QueueBuilder() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { network.refresh() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var selectedArtist by remember { mutableStateOf<Artist?>(null) }
    var isVip by remember { mutableStateOf(false) }
    var vipRefreshUsed by remember { mutableStateOf(false) }
    var vipRefreshing by remember { mutableStateOf(false) }
    var refreshedSongs by remember { mutableStateOf<List<Song>?>(null) }
    var historyDates by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var historySongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showRefreshPrompt by remember { mutableStateOf(true) }
    var styleMode by remember { mutableStateOf(false) }
    var stylePage by remember { mutableStateOf(DailyStylePageState()) }
    var styleSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var styleLoading by remember { mutableStateOf(false) }
    var styleSaving by remember { mutableStateOf(false) }
    var styleSyncPending by remember { mutableStateOf(false) }
    var styleConfigLoading by remember { mutableStateOf(false) }
    var styleError by remember { mutableStateOf<String?>(null) }
    var styleConfigRetry by remember { mutableStateOf(0) }
    var styleFiltersExpanded by remember { mutableStateOf(false) }
    var appliedStyleTags by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var appliedStyleCategoryIndex by remember { mutableIntStateOf(0) }
    var styleRequested by remember { mutableStateOf(false) }
    var styleRequestId by remember { mutableIntStateOf(0) }

    fun loadStyleSongs() {
        val requestId = ++styleRequestId
        styleLoading = true
        styleError = null
        scope.launch {
            when (val result = homeRepository.dailyStyleSongs(null, emptyList())) {
                is AppResult.Success -> if (requestId == styleRequestId) {
                    styleSongs = result.data.dailySongs
                    val savedIds = result.data.tags?.tags.orEmpty()
                        .filter { it.isChoose && it.tagId > 0L }
                        .map { it.tagId }
                        .toSet()
                    appliedStyleTags = savedIds
                    stylePage = stylePage.copy(selectedTagIds = savedIds, touchedCategoryIds = emptySet())
                    styleSyncPending = false
                }
                is AppResult.Failure -> if (requestId == styleRequestId) styleError = result.message
            }
            if (requestId == styleRequestId) styleLoading = false
        }
    }

    LaunchedEffect(Unit) {
        isVip = authRepository.currentSession().vip
        if (isVip) {
            vipRefreshUsed = homeRepository.hasUsedVipDailyRefresh()
            when (val result = homeRepository.dailyHistoryDates()) {
                is AppResult.Success -> historyDates = result.data.take(14)
                is AppResult.Failure -> historyError = result.message
            }
        }
    }

    LaunchedEffect(styleMode, styleConfigRetry) {
        if (!styleMode) return@LaunchedEffect
        if (!styleRequested) {
            styleRequested = true
            loadStyleSongs()
        }
        if (stylePage.categories.isNotEmpty() || styleConfigLoading) return@LaunchedEffect
        styleConfigLoading = true
        when (val result = homeRepository.dailyStyleConfig()) {
            is AppResult.Success -> {
                stylePage = stylePage.copy(categories = result.data)
            }
            is AppResult.Failure -> if (styleSongs.isEmpty() && !styleLoading) styleError = result.message
        }
        styleConfigLoading = false
    }

    val daily = when {
        selectedDate != null && historyError == null -> historySongs
        else -> refreshedSongs ?: state.data?.daily.orEmpty()
    }
    val shownDate = if (styleMode) LocalDate.now()
        else selectedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()

    fun refreshForVip() {
        if (!isVip || vipRefreshUsed || vipRefreshing) return
        vipRefreshing = true
        scope.launch {
            try {
                when (val result = homeRepository.refreshDailyForVip()) {
                    is AppResult.Success -> {
                        refreshedSongs = result.data
                        selectedDate = null
                        vipRefreshUsed = true
                        viewModel.load(force = true)
                    }
                    is AppResult.Failure -> snackbar.showSnackbar(result.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbar.showSnackbar("重新推荐暂不可用，请稍后重试")
            } finally {
                vipRefreshing = false
            }
        }
    }

    fun selectHistory(date: String?) {
        showHistory = false
        if (date == null) {
            selectedDate = null
            historyLoading = false
            historyError = null
            return
        }
        selectedDate = date
        historySongs = emptyList()
        historyLoading = true
        historyError = null
        scope.launch {
            when (val result = homeRepository.dailyHistoryDetail(date)) {
                is AppResult.Success -> if (selectedDate == date) historySongs = result.data
                is AppResult.Failure -> if (selectedDate == date) historyError = result.message
            }
            if (selectedDate == date) historyLoading = false
        }
    }

    selectedArtist?.let { artist ->
        ArtistInfoDialog(artist, onDismiss = { selectedArtist = null }, onOpenDetail = {
            selectedArtist = null
            navController.navigate(Routes.artist(artist.id))
        })
    }
    actionSong?.let { song ->
        SongActionSheet(
            song = song,
            onDismiss = { actionSong = null },
            onPlayNext = {
                player.enqueueNext(song) { message -> scope.launch { snackbar.showSnackbar(message) } }
            },
            onLike = {
                scope.launch {
                    val liked = song.id in playlistRepository.likedIds.value
                    when (val result = playlistRepository.like(song.id, !liked)) {
                        is AppResult.Failure -> snackbar.showSnackbar(result.message)
                        is AppResult.Success -> Unit
                    }
                }
            },
            onComment = { CommentSheetController.open(song.id) },
            onArtist = song.ar.firstOrNull()?.takeIf { it.id > 0 }?.let { artist ->
                { selectedArtist = artist }
            },
            onUnavailable = { action -> scope.launch { snackbar.showSnackbar("${action.label} 暂无可用接口") } },
        )
    }

    val visibleSongs = if (styleMode) styleSongs else daily
    val unavailableSongIds = rememberOfflineUnavailableIds(
        songs = visibleSongs,
        isOnline = isOnline,
        quality = quality,
        cacheRevision = cacheRevision,
        context = context,
        queueBuilder = queueBuilder,
    )
    val playableVisibleSongs = remember(visibleSongs, isOnline, unavailableSongIds) {
        offlinePlayableQueue(visibleSongs, isOnline, unavailableSongIds)
    }
    Box(Modifier.fillMaxSize().background(if (artSkinDrawable(LocalNmlThemeKind.current) != null) Color.Transparent else DailyInk)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (!styleMode && isVip && !vipRefreshUsed && showRefreshPrompt) 106.dp else 28.dp),
        ) {
            item(key = "hero") {
                Box(
                    Modifier.fillMaxWidth().height(362.dp).background(
                        Brush.verticalGradient(
                            if (artSkinDrawable(LocalNmlThemeKind.current) != null) listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.60f))
                            else listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant, DailyInk),
                        ),
                    ),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = DailyText)
                            }
                            Row(
                                modifier = Modifier.weight(1f).padding(horizontal = 14.dp)
                                    .clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.80f))
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
                                        .background(if (!styleMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).height(42.dp)
                                        .dailyPressable { styleMode = false },
                                    contentAlignment = Alignment.Center,
                                ) { Text("默认推荐", color = if (!styleMode) MaterialTheme.colorScheme.onPrimaryContainer else DailyText, fontWeight = FontWeight.Bold) }
                                Box(
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
                                        .background(if (styleMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).height(42.dp)
                                        .dailyPressable { styleMode = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("风格推荐", color = if (styleMode) MaterialTheme.colorScheme.onPrimaryContainer else DailyText, style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (styleMode) FontWeight.Bold else null)
                                    Text(
                                        "NEW", color = MaterialTheme.colorScheme.onPrimary, fontSize = 9.sp,
                                        modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(6.dp))
                                            .background(DailyRed).padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            if (!styleMode && isVip) Box {
                                IconButton(onClick = { showMore = true }) {
                                    Icon(Icons.Default.MoreVert, "更多", tint = DailyText)
                                }
                                DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                                    DropdownMenuItem(text = { Text(if (vipRefreshUsed) "今日已重新推荐" else "重新推荐一次") },
                                        enabled = !vipRefreshUsed && !vipRefreshing,
                                        onClick = { showMore = false; refreshForVip() })
                                }
                            }
                            else if (styleMode) IconButton(
                                onClick = { styleFiltersExpanded = !styleFiltersExpanded },
                                enabled = !styleSaving,
                            ) {
                                Icon(Icons.Default.MoreVert, "展开风格筛选", tint = DailyText)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(shownDate.dayOfMonth.toString().padStart(2, '0'),
                                        color = DailyText, fontSize = 54.sp, lineHeight = 58.sp,
                                        fontWeight = FontWeight.Light)
                                    Text("/${shownDate.monthValue.toString().padStart(2, '0')}",
                                        color = DailyText, fontSize = 24.sp,
                                        modifier = Modifier.padding(bottom = 8.dp))
                                }
                                Text(
                                    when {
                                        styleMode -> "定制你今日的专属好音乐～"
                                        selectedDate == null -> "根据你的音乐口味"
                                        else -> "历史日推 · $selectedDate"
                                    },
                                    color = DailyText.copy(alpha = 0.87f), style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.weight(1f))
                            if (!styleMode) Row(
                                modifier = Modifier.clip(RoundedCornerShape(28.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                    .dailyPressable {
                                        if (isVip) showHistory = true
                                        else scope.launch { snackbar.showSnackbar("历史日推需会员") }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.History, null, tint = DailyText, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("历史日推", color = DailyText, style = MaterialTheme.typography.labelMedium)
                                if (isVip) Text("  VIP", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            item(key = "controls") {
                Row(
                    modifier = Modifier.fillMaxWidth().height(66.dp).background(MaterialTheme.colorScheme.surface)
                        .dailyPressable(enabled = playableVisibleSongs.isNotEmpty()) {
                            player.playSongs(navController, playableVisibleSongs, 0)
                        }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(DailyRed), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, "播放全部", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("播放全部", color = DailyText, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (isVip && !styleMode) {
                        IconButton(onClick = { refreshForVip() }, enabled = !vipRefreshUsed && !vipRefreshing) {
                            if (vipRefreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = DailyText)
                            else Icon(if (vipRefreshUsed) Icons.Default.Check else Icons.Default.Replay,
                                if (vipRefreshUsed) "今日已重新推荐" else "会员重新推荐一次",
                                tint = if (vipRefreshUsed) DailyMuted else DailyText)
                        }
                        Text(if (vipRefreshUsed) "已用" else "1次", color = DailyMuted,
                            style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text("${visibleSongs.size} 首", color = DailyMuted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (!styleMode) item(key = "description") {
                Text(
                    if (selectedDate == null) "ⓘ  根据你的音乐口味生成，每日 6:00 更新"
                    else "ⓘ  $selectedDate 的每日推荐 · ${daily.size} 首",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    color = DailyMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (styleMode) {
                item(key = "style-filters") {
                    DailyStyleFilters(
                        page = stylePage,
                        loading = styleConfigLoading,
                        saving = styleSaving,
                        locked = styleSaving || styleSyncPending,
                        expanded = styleFiltersExpanded,
                        onToggle = { if (!styleSaving && !styleSyncPending) styleFiltersExpanded = !styleFiltersExpanded },
                        onCategory = { index ->
                            if (!styleSaving && !styleSyncPending) stylePage = stylePage.copy(selectedCategoryIndex = index)
                        },
                        onTag = { tagId ->
                            if (!styleSaving && !styleSyncPending) {
                                val categoryId = stylePage.categories.getOrNull(stylePage.selectedCategoryIndex)?.categoryId
                                stylePage = stylePage.copy(
                                    selectedTagIds = if (tagId in stylePage.selectedTagIds) {
                                        stylePage.selectedTagIds - tagId
                                    } else {
                                        stylePage.selectedTagIds + tagId
                                    },
                                    touchedCategoryIds = if (categoryId != null) stylePage.touchedCategoryIds + categoryId
                                        else stylePage.touchedCategoryIds,
                                )
                            }
                        },
                        onConfirm = {
                            if (!styleSaving && !styleSyncPending) {
                                val selection = stylePage
                                styleSaving = true
                                styleRequestId++
                                styleError = null
                                scope.launch {
                                    var failure: String? = null
                                    for (category in selection.categories.filter { it.categoryId in selection.touchedCategoryIds }) {
                                        val ids = category.tags.map { it.tagId }.filter { it in selection.selectedTagIds }
                                        when (val result = homeRepository.saveDailyStyleTags(category.categoryId, ids)) {
                                            is AppResult.Failure -> { failure = result.message; break }
                                            is AppResult.Success -> Unit
                                        }
                                    }
                                    if (failure == null) {
                                        appliedStyleTags = selection.selectedTagIds
                                        appliedStyleCategoryIndex = selection.selectedCategoryIndex
                                        stylePage = selection.copy(touchedCategoryIds = emptySet())
                                        styleFiltersExpanded = false
                                        loadStyleSongs()
                                    } else {
                                        // Earlier categories may already be committed. Read the server's
                                        // actual selection before allowing another edit or cancel.
                                        when (val current = homeRepository.dailyStyleSongs(null, emptyList())) {
                                            is AppResult.Success -> {
                                                val savedIds = current.data.tags?.tags.orEmpty()
                                                    .filter { it.isChoose && it.tagId > 0L }
                                                    .map { it.tagId }
                                                    .toSet()
                                                styleSongs = current.data.dailySongs
                                                appliedStyleTags = savedIds
                                                appliedStyleCategoryIndex = selection.selectedCategoryIndex
                                                stylePage = selection.copy(
                                                    selectedTagIds = savedIds,
                                                    touchedCategoryIds = emptySet(),
                                                )
                                                styleFiltersExpanded = false
                                                styleSyncPending = false
                                                styleError = "保存中断，已同步当前设置：$failure"
                                            }
                                            is AppResult.Failure -> {
                                                styleFiltersExpanded = false
                                                styleSyncPending = true
                                                styleError = "保存中断且无法确认当前设置，请点击重试同步"
                                            }
                                        }
                                    }
                                    styleSaving = false
                                }
                            }
                        },
                        onCancel = {
                            if (!styleSaving && !styleSyncPending) {
                                stylePage = stylePage.copy(
                                    selectedCategoryIndex = appliedStyleCategoryIndex,
                                    selectedTagIds = appliedStyleTags,
                                    touchedCategoryIds = emptySet(),
                                )
                                styleFiltersExpanded = false
                            }
                        },
                        onRetryConfig = {
                            stylePage = DailyStylePageState()
                            styleError = null
                            styleConfigRetry++
                        },
                    )
                }
                when {
                    styleConfigLoading -> item { DailyStatus("正在读取风格标签…") }
                    styleError != null -> item {
                        DailyStatus("$styleError · 点击重试", DailyRed, Modifier.dailyPressable {
                            if (stylePage.categories.isEmpty()) {
                                stylePage = DailyStylePageState()
                                styleError = null
                                styleConfigRetry++
                            } else loadStyleSongs()
                        })
                    }
                    styleLoading -> item { DailyStatus("正在生成风格推荐…") }
                    styleSongs.isEmpty() -> item {
                        DailyStatus("当前风格暂无推荐歌曲 · 点击重试", modifier = Modifier.dailyPressable { loadStyleSongs() })
                    }
                    else -> itemsIndexed(styleSongs, key = { _, song -> "style-${song.id}" }) { index, song ->
                        val queueIndex = offlineQueueStartIndex(
                            songs = styleSongs,
                            sourceIndex = index,
                            isOnline = isOnline,
                            unavailableIds = unavailableSongIds,
                        )
                        DailySongRow(
                            song = song,
                            enabled = queueIndex != null,
                            disabledReason = offlineUnavailableLabel(isOnline, unavailableSongIds, song.id),
                            onClick = {
                                queueIndex?.let { player.playSongs(navController, playableVisibleSongs, it) }
                            },
                            onMore = { actionSong = song },
                            onMv = song.mv.takeIf { it > 0L }?.let { mvId -> { navController.navigate(Routes.mv(mvId)) } },
                        )
                    }
                }
            } else {
            if (selectedDate != null && historyLoading) item {
                DailyStatus("正在读取 $selectedDate 的推荐…")
            }
            if (selectedDate != null && historyError != null) item {
                DailyStatus("$selectedDate 暂不可用，下面显示今日推荐", DailyRed)
            }
            when {
                selectedDate != null && historyLoading -> Unit
                state.loading && state.data == null -> item { DailyStatus("每日推荐加载中…") }
                state.error != null && state.data == null -> item {
                    DailyStatus("${state.error} · 点击重试", DailyRed,
                        Modifier.dailyPressable { viewModel.load(force = true) })
                }
                daily.isEmpty() -> item {
                    DailyStatus(if (selectedDate == null) "今日推荐还未生成，稍后再来看看" else "该日期暂无推荐歌曲")
                }
                else -> itemsIndexed(daily, key = { _, song -> song.id }) { index, song ->
                    val queueIndex = offlineQueueStartIndex(
                        songs = daily,
                        sourceIndex = index,
                        isOnline = isOnline,
                        unavailableIds = unavailableSongIds,
                    )
                    DailySongRow(
                        song = song,
                        enabled = queueIndex != null,
                        disabledReason = offlineUnavailableLabel(isOnline, unavailableSongIds, song.id),
                        onClick = {
                            queueIndex?.let { player.playSongs(navController, playableVisibleSongs, it) }
                        },
                        onMore = { actionSong = song },
                        onMv = song.mv.takeIf { it > 0L }?.let { mvId ->
                            { navController.navigate(Routes.mv(mvId)) }
                        },
                    )
                }
            }
            }
        }
        if (!styleMode && isVip && !vipRefreshUsed && showRefreshPrompt && selectedDate == null) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp)
                    .clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("不喜欢推荐的歌曲？", color = DailyText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f), maxLines = 1)
                Text("重新推荐", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .dailyPressable(enabled = !vipRefreshing) { refreshForVip() }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelMedium)
                Text("取消", color = DailyText,
                    modifier = Modifier.padding(start = 10.dp).dailyPressable { showRefreshPrompt = false },
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        NmSnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(
                start = 16.dp,
                end = 16.dp,
                bottom = if (!styleMode && isVip && !vipRefreshUsed && showRefreshPrompt && selectedDate == null) 96.dp else 18.dp,
            ),
        )
    }

    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = DailyText,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 30.dp)) {
                Text("历史日推", modifier = Modifier.padding(horizontal = 22.dp),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("选择日期查看当天的推荐歌曲", modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = DailyMuted)
                if (historyDates.isEmpty()) {
                    DailyStatus(historyError ?: "暂无可查看的历史日期")
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { HistoryDateChip("今天", selectedDate == null) { selectHistory(null) } }
                        items(historyDates, key = { it }) { date ->
                            HistoryDateChip(date, selectedDate == date) { selectHistory(date) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyStatus(message: String, color: Color = DailyMuted, modifier: Modifier = Modifier) {
    Text(message, modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun HistoryDateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label,
        modifier = Modifier.clip(RoundedCornerShape(24.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .dailyPressable(onClick = onClick).padding(horizontal = 15.dp, vertical = 10.dp),
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else DailyText,
        style = MaterialTheme.typography.labelLarge)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailyStyleFilters(
    page: DailyStylePageState,
    loading: Boolean,
    saving: Boolean,
    locked: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCategory: (Int) -> Unit,
    onTag: (Long) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRetryConfig: () -> Unit,
) {
    if (loading && page.categories.isEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DailyRed)
            Text("  正在读取风格筛选…", color = DailyMuted, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (page.categories.isEmpty()) {
        Text(
            "风格标签暂不可用，点击重新读取",
            modifier = Modifier.fillMaxWidth().dailyPressable(onClick = onRetryConfig).padding(horizontal = 20.dp, vertical = 14.dp),
            color = DailyMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val selectedCategory = page.categories.getOrNull(page.selectedCategoryIndex) ?: return
    val selectedNames = page.categories.flatMap { it.tags }
        .filter { it.tagId in page.selectedTagIds }.map { it.tagName }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .dailyPressable(enabled = !locked, onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            selectedNames.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "选择喜欢的音乐风格",
            color = DailyText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(if (expanded) "⌃" else "⌄", color = DailyMuted,
            style = MaterialTheme.typography.titleMedium)
    }
    if (!expanded) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        itemsIndexed(page.categories, key = { _, category -> category.categoryId }) { index, category ->
            Text(
                category.categoryName,
                modifier = Modifier.dailyPressable(enabled = !locked) { onCategory(index) }.padding(vertical = 8.dp),
                color = if (index == page.selectedCategoryIndex) DailyText else DailyMuted,
                fontWeight = if (index == page.selectedCategoryIndex) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        maxItemsInEachRow = 4,
    ) {
        selectedCategory.tags.forEach { tag ->
            val selected = tag.tagId in page.selectedTagIds
            Text(
                tag.tagName,
                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, if (selected) DailyRed else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                    .dailyPressable(enabled = !locked) { onTag(tag.tagId) }.padding(horizontal = 16.dp, vertical = 8.dp),
                color = if (selected) DailyRed else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        Text(
            "取消",
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, DailyMuted, RoundedCornerShape(20.dp))
                .dailyPressable(enabled = !locked, onClick = onCancel).padding(horizontal = 20.dp, vertical = 10.dp),
            color = DailyText,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            if (saving) "保存中…" else if (locked) "请先同步" else "确认",
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(DailyRed)
                .dailyPressable(enabled = !locked, onClick = onConfirm).padding(horizontal = 20.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun DailySongRow(
    song: Song,
    enabled: Boolean,
    disabledReason: String?,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onMv: (() -> Unit)?,
) {
    val contentColor = if (enabled) DailyText else DailyMuted
    val accentColor = if (enabled) DailyRed else DailyMuted
    Row(
        modifier = Modifier.fillMaxWidth().height(78.dp).dailyPressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.coverThumbUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (enabled) null else ColorFilter.colorMatrix(
                ColorMatrix().apply { setToSaturation(0f) },
            ),
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.name, color = contentColor, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                disabledReason?.let { reason ->
                    Text(reason, color = DailyMuted, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(5.dp))
                }
                val vip = song.privilege?.fee == 1 || song.fee == 1
                if (vip) {
                    val vipColor = if (enabled) MaterialTheme.colorScheme.tertiary else DailyMuted
                    Text("VIP", color = vipColor,
                        modifier = Modifier.border(1.dp, vipColor, RoundedCornerShape(3.dp))
                            .padding(horizontal = 3.dp),
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(5.dp))
                }
                song.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Text(reason, color = accentColor, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 100.dp).clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 3.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(song.artistNames + song.albumName.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty(),
                    color = DailyMuted, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onMv != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).dailyPressable(onClick = onMv)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.PlayArrow, "播放MV", tint = DailyMuted,
                    modifier = Modifier.size(21.dp).border(1.dp, DailyMuted, CircleShape))
                Text("MV", color = DailyMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreVert, "更多歌曲操作", tint = DailyMuted)
        }
    }
}

@Composable
private fun Modifier.dailyPressable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    nmlPressable(onClick = onClick, enabled = enabled)
