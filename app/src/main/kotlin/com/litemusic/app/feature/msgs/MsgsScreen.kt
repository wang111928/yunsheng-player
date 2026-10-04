package com.litemusic.app.feature.msgs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.litemusic.app.ui.Routes
import com.litemusic.design.components.NmlTopBar
import com.litemusic.design.components.nmlPressable
import com.litemusic.design.components.EmptyView
import com.litemusic.design.components.LoadingView
import com.litemusic.shared.util.displayEmotes
import org.koin.androidx.compose.koinViewModel

internal fun insertChatToken(draft: String, token: String): String = draft + token

internal fun canAutoLoadMsgSessions(state: MsgViewModel.UiState): Boolean =
    state.sessionsMore && !state.loadingMoreSessions && !state.loading && !state.refreshing && state.error == null

internal fun shouldAutoScrollChat(previousLastKey: String?, currentLastKey: String?, nearBottom: Boolean): Boolean =
    currentLastKey != null && currentLastKey != previousLastKey && nearBottom

/** Tokens that have a text fallback in [displayEmotes] and can therefore be safely sent in private messages. */
internal val chatEmojiTokens = listOf(
    "[认可]", "[感动]", "[偷笑]", "[大笑]", "[爱心]", "[偷窥]", "[装可爱]",
    "[可爱]", "[流泪]", "[生气]", "[呲牙]", "[亲亲]", "[惊恐]", "[酷]",
)

/**
 * Keeps a lazy-list key with the exact row that produced it.  A list can be
 * replaced while Compose is resolving item keys, so indexing a separately
 * remembered key list from the latest state list is unsafe.
 */
internal data class KeyedListItem<T>(val key: String, val item: T)

internal fun chatListItems(
    messages: List<com.litemusic.shared.model.MsgItem>,
): List<KeyedListItem<com.litemusic.shared.model.MsgItem>> {
    val keys = chatMessageKeys(messages)
    return messages.mapIndexed { index, message -> KeyedListItem(keys[index], message) }
}

internal fun sessionListItems(
    sessions: List<com.litemusic.shared.model.MsgSession>,
): List<KeyedListItem<com.litemusic.shared.model.MsgSession>> {
    val keys = sessionListKeys(sessions)
    return sessions.mapIndexed { index, session -> KeyedListItem(keys[index], session) }
}

internal fun chatMessageKeys(messages: List<com.litemusic.shared.model.MsgItem>): List<String> {
    val unknownOccurrences = mutableMapOf<String, Int>()
    return messages.map { message ->
        when {
            message.msgId > 0L -> "server:${message.msgId}"
            message.msgId < 0L -> "local:${message.msgId}"
            else -> {
                val fingerprint = listOf(
                    message.time,
                    message.fromUser?.userId ?: 0L,
                    message.toUser?.userId ?: 0L,
                    message.type,
                    message.msg,
                ).joinToString(":")
                val occurrence = unknownOccurrences.getOrDefault(fingerprint, 0)
                unknownOccurrences[fingerprint] = occurrence + 1
                "unknown:$fingerprint:$occurrence"
            }
        }
    }
}

/** The outgoing bubble belongs to its sender, never to the recipient. */
internal fun chatAvatarUser(
    message: com.litemusic.shared.model.MsgItem,
    fromOther: Boolean,
): com.litemusic.shared.model.NeteaseUser? =
    message.fromUser ?: if (fromOther) message.toUser else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MsgsScreen(
    navController: NavController,
    initialUserId: Long? = null,
    viewModel: MsgViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val sessionListState = rememberLazyListState()
    val sessionRows = remember(state.sessions) { sessionListItems(state.sessions) }

    LaunchedEffect(initialUserId) {
        if (initialUserId != null && initialUserId > 0L) viewModel.openChat(initialUserId)
        else viewModel.loadSessions()
    }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            viewModel.toastShown()
        }
    }
    LaunchedEffect(sessionListState, state.sessionsMore, state.loadingMoreSessions, state.error, state.loading, state.refreshing) {
        if (!canAutoLoadMsgSessions(state)) return@LaunchedEffect
        snapshotFlow {
            val layout = sessionListState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            layout.totalItemsCount > 0 && last >= layout.totalItemsCount - 3
        }.collect { nearEnd ->
            if (nearEnd && canAutoLoadMsgSessions(state)) viewModel.loadMoreSessions()
        }
    }

    if (state.chatUser != null) {
        var emojiPanelVisible by remember(state.chatUser) { mutableStateOf(false) }
        LifecycleStartEffect(state.chatUser) {
            viewModel.syncChat()
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                while (true) { delay(5_000); viewModel.syncChat() }
            }
            onStopOrDispose { job.cancel(); viewModel.stopChatSync() }
        }
        val chatListState = rememberLazyListState()
        var nearBottom by remember { mutableStateOf(true) }
        LaunchedEffect(chatListState) {
            snapshotFlow {
                val info = chatListState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= info.totalItemsCount - 2
            }.collect { nearBottom = it }
        }
        val messageRows = remember(state.chatMessages) { chatListItems(state.chatMessages) }
        var previousLastKey by remember(state.chatUser) { mutableStateOf<String?>(null) }
        val currentLastKey = messageRows.lastOrNull()?.key
        LaunchedEffect(state.chatUser, currentLastKey) {
            if (shouldAutoScrollChat(previousLastKey, currentLastKey, nearBottom)) {
                chatListState.animateScrollToItem((chatListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            }
            previousLastKey = currentLastKey
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                NmlTopBar("私信", onBack = {
                    if (initialUserId != null) navController.popBackStack() else viewModel.closeChat()
                })
                state.error?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = chatListState,
                    reverseLayout = false,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.hasOlder) item {
                        TextButton(onClick = viewModel::loadOlder, enabled = !state.loadingOlder, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.loadingOlder) "加载中…" else "加载更早消息")
                        }
                    }
                    items(messageRows, key = { it.key }) { row ->
                        val msg = row.item
                        val fromOther = messageIsFromOther(msg.fromUser?.userId, state.chatUser ?: 0L)
                        val participant = chatAvatarUser(msg, fromOther)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (fromOther) Arrangement.Start else Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (fromOther) {
                                AsyncImage(
                                    model = participant?.avatarUrl,
                                    contentDescription = "打开${participant?.nickname ?: "用户"}主页",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.padding(end = 8.dp).size(48.dp).clip(CircleShape)
                                        .nmlPressable(enabled = (participant?.userId ?: 0L) > 0L, onClick = {
                                            navController.navigate(Routes.user(participant!!.userId))
                                        }),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .widthIn(max = 280.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (fromOther) MaterialTheme.colorScheme.surface
                                        else MaterialTheme.colorScheme.primary,
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    displayEmotes(msg.msg),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (fromOther) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onPrimary
                                    },
                                )
                            }
                            if (!fromOther) {
                                AsyncImage(
                                    model = participant?.avatarUrl,
                                    contentDescription = "打开${participant?.nickname ?: "用户"}主页",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.padding(start = 8.dp).size(48.dp).clip(CircleShape)
                                        .nmlPressable(enabled = (participant?.userId ?: 0L) > 0L, onClick = {
                                            navController.navigate(Routes.user(participant!!.userId))
                                        }),
                                )
                            }
                        }
                    }
                }
                if (emojiPanelVisible) {
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 128.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        chatEmojiTokens.forEach { token ->
                            TextButton(onClick = { viewModel.setInput(insertChatToken(state.input, token)) }) {
                                Text(displayEmotes(token))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { emojiPanelVisible = !emojiPanelVisible }) { Text("表情") }
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = viewModel::setInput,
                        placeholder = { Text("发送消息") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(22.dp),
                        maxLines = 4,
                    )
                    TextButton(
                        onClick = viewModel::send,
                        enabled = state.input.isNotBlank() && !state.isSubmitting,
                        modifier = Modifier.padding(start = 4.dp),
                    ) { Text(if (state.isSubmitting) "发送中" else "发送") }
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 68.dp),
            )
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NmlTopBar("私信", onBack = { navController.popBackStack() })
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.loadSessions(refresh = true) },
                modifier = Modifier.weight(1f),
            ) {
                when {
                state.loading -> LoadingView()
                state.error != null && state.sessions.isEmpty() -> EmptyView(state.error.orEmpty())
                state.sessions.isEmpty() -> EmptyView("暂无私信会话")
                else -> LazyColumn(Modifier.fillMaxSize(), state = sessionListState,
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessionRows, key = { it.key }) { row ->
                        val session = row.item
                        val user = when {
                            session.fromUser?.userId == state.currentUserId -> session.toUser
                            else -> session.fromUser ?: session.toUser
                        }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface).nmlPressable(onClick = {
                                user?.userId?.let(viewModel::openChat)
                            }).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = user?.avatarUrl,
                                contentDescription = "打开${user?.nickname ?: "用户"}主页",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp).clip(CircleShape)
                                    .nmlPressable(enabled = (user?.userId ?: 0L) > 0L, onClick = {
                                        navController.navigate(Routes.user(user!!.userId))
                                    }),
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(user?.nickname ?: "未知用户", style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    session.lastMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (session.unread > 0) {
                                Text(
                                    session.unread.toString(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                                        .padding(horizontal = 7.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                    if (state.loadingMoreSessions) item(key = "sessions-loading-more") {
                        Text(
                            "正在加载更多私信…",
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.error?.let { message -> item(key = "sessions-page-error") {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(message, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { viewModel.loadSessions(refresh = true) },
                                enabled = !state.loadingMoreSessions) { Text("重新获取会话") }
                        }
                    } }
                }
            }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 68.dp),
        )
    }
}
