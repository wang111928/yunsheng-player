package com.litemusic.app.feature.together

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import com.litemusic.design.components.NmlButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.litemusic.app.BuildConfig
import com.litemusic.app.data.TogetherRepository
import com.litemusic.app.ui.Routes
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import com.litemusic.player.PlaybackController
import com.litemusic.shared.player.PlayPhase
import com.litemusic.design.components.TogetherIcon

internal data class TogetherRoomPresentation(
    val memberCount: Int,
    val statusText: String,
    val canMessageMember: Boolean,
    val memberMessageLabel: String = "成员私信",
    val showsPlaybackSync: Boolean = false,
)

internal fun togetherRoomPresentation(
    room: TogetherRepository.RoomState,
): TogetherRoomPresentation {
    val memberCount = room.members.size
    val statusText = when {
        !room.inRoom -> "等待连接官方一起听房间"
        memberCount == 0 -> "房间已连接，暂无成员"
        memberCount == 1 -> "只有 1 位成员在线"
        else -> "$memberCount 位成员在线"
    }
    return TogetherRoomPresentation(
        memberCount = memberCount,
        statusText = statusText,
        canMessageMember = room.inRoom && room.members.any { it.userId > 0L },
    )
}

@Composable
fun TogetherScreen(
    navController: NavController,
    viewModel: TogetherViewModel = koinViewModel(),
) {
    TogetherContent(navController, null, viewModel)
}

@Composable
fun TogetherRoomScreen(
    code: String,
    viewModel: TogetherViewModel = koinViewModel(),
) {
    LaunchedEffect(code) { viewModel.join(code) }
    TogetherContent(null, code, viewModel)
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun TogetherContent(
    navController: NavController?,
    initialRoomCode: String?,
    viewModel: TogetherViewModel,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LifecycleStartEffect(viewModel, lifecycleOwner = lifecycleOwner) {
        viewModel.onPageVisible()
        onStopOrDispose { viewModel.onPageHidden() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var roomCode by remember(initialRoomCode) { mutableStateOf(initialRoomCode.orEmpty()) }
    var inviteUid by remember { mutableStateOf("") }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            viewModel.toastShown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        PullToRefreshBox(
            isRefreshing = state.room.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TogetherHeader(navController)

                if (state.room.inRoom) {
                    ActiveRoomCard(
                        state = state,
                        inviteUid = inviteUid,
                        onInviteUidChange = { inviteUid = it },
                        viewModel = viewModel,
                        navController = navController,
                    )
                } else {
                    EmptyRoomCard(
                        onOpenOfficial = {
                            val intent = context.packageManager
                                .getLaunchIntentForPackage("com.netease.cloudmusic")
                            if (intent != null) context.startActivity(intent) else viewModel.create()
                        },
                    )
                    RoomCodeCard(
                        roomCode = roomCode,
                        onRoomCodeChange = { roomCode = it },
                        onJoin = { viewModel.join(roomCode) },
                        enabled = roomCode.isNotBlank() && !state.room.isRefreshing,
                    )
                }

                state.room.error?.let {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (state.room.lastSyncedAt > 0L) {
                    Text(
                        "房间成员状态已更新",
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun TogetherHeader(
    navController: NavController?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navController?.let {
            IconButton(onClick = it::popBackStack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }
        Column(Modifier.weight(1f)) {
            Text("一起听", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "和朋友分享此刻的音乐",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyRoomCard(
    onOpenOfficial: () -> Unit,
) {
    RoomSurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        TogetherIcon(contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("一起听房间", style = MaterialTheme.typography.titleLarge)
                    Text("未连接官方房间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "Lite 只展示官方接口返回的真实房间状态。创建房间、接受邀请和播放同步请在网易云官方 App 完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onOpenOfficial,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
            ) { Text("打开官方网易云") }
        }
    }
}

@Composable
private fun RoomCodeCard(
    roomCode: String,
    onRoomCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    enabled: Boolean,
) {
    RoomSurface {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("连接已有房间", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = roomCode,
                onValueChange = onRoomCodeChange,
                label = { Text("官方房间 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            Button(
                onClick = onJoin,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) { Text("检查并连接") }
            Text(
                "仅凭房间 ID 不能绕过官方邀请流程。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveRoomCard(
    state: TogetherViewModel.UiState,
    inviteUid: String,
    onInviteUidChange: (String) -> Unit,
    viewModel: TogetherViewModel,
    navController: NavController?,
) {
    val room = state.room
    val presentation = togetherRoomPresentation(room)
    val messageableMember = room.members.firstOrNull { it.userId > 0L && it.userId != state.myUid }
    var showManagement by remember { mutableStateOf(false) }

    RoomSurface {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("已连接一起听", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(presentation.statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        "在线",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            TogetherListeningHero(room, navController)
            TextButton(onClick = { showManagement = !showManagement }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (showManagement) "收起房间管理" else "邀请与房间管理")
            }
            if (showManagement) {
            Text("房间 ${room.code}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = inviteUid,
                onValueChange = onInviteUidChange,
                label = { Text("邀请用户 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            Button(
                onClick = { viewModel.invite(inviteUid) },
                enabled = inviteUid.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) { Text("发送一起听邀请") }
            }

            if (BuildConfig.FEATURE_MSG && navController != null && messageableMember != null) {
                OutlinedButton(
                    onClick = { navController.navigate(Routes.msgs(messageableMember.userId)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(presentation.memberMessageLabel)
                }
            }

            if (navController != null) {
                OutlinedButton(
                    onClick = { navController.navigate(Routes.PLAYER) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Default.QueueMusic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("打开播放页面")
                }
            }

            if (showManagement) {
                Button(
                    onClick = viewModel::leave,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                ) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("退出房间")
                }
            }
        }
    }
}

@Composable
private fun TogetherListeningHero(room: TogetherRepository.RoomState, navController: NavController?) {
    val controller: PlaybackController = koinInject()
    val playback by controller.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            room.members.take(2).forEachIndexed { index, member ->
                if (index == 1) TogetherIcon(Modifier.size(30.dp), MaterialTheme.colorScheme.primary, null)
                TogetherMember(member, 84.dp)
            }
        }
        if (room.members.isEmpty()) Text("等待成员资料", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (room.members.size > 2) Text("还有 ${room.members.size - 2} 位成员", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Box(Modifier.size(232.dp).clip(CircleShape).background(Color(0xFF111419)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                repeat(9) { ring -> drawCircle(Color.White.copy(alpha = 0.04f), radius = size.minDimension * (0.30f + ring * 0.022f), style = Stroke(1.dp.toPx())) }
            }
            Box(Modifier.size(118.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, Modifier.size(40.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                AsyncImage(playback.current?.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(playback.current?.title ?: "选择一首歌", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(playback.current?.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            IconButton(onClick = controller::previous, enabled = playback.current != null) { Icon(Icons.Default.SkipPrevious, "上一首") }
            IconButton(onClick = controller::toggle, enabled = playback.current != null, modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) {
                Icon(if (playback.phase == PlayPhase.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow, if (playback.phase == PlayPhase.PLAYING) "暂停" else "播放", Modifier.size(34.dp), MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = controller::next, enabled = playback.current != null) { Icon(Icons.Default.SkipNext, "下一首") }
        }
        Text("房间成员来自网易云 · 当前音乐由 Lite 播放", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (playback.current == null && navController != null) TextButton(onClick = { navController.navigate(Routes.HOME) }) { Text("选择音乐") }
    }
}

@Composable
private fun TogetherMember(member: TogetherRepository.RoomMember, avatarSize: Dp = 52.dp) {
    Column(
        modifier = Modifier.width(avatarSize + 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                member.nickname.take(1).ifBlank { "云" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
            AsyncImage(
                model = member.avatarUrl,
                contentDescription = member.nickname,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
        Text(
            member.nickname.ifBlank { "网易云用户" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun RoomSurface(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}
