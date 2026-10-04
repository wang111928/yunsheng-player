package com.litemusic.app.feature.msgs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.MsgDataSource
import com.litemusic.app.data.MsgSessionPage
import com.litemusic.app.data.AuthRepository
import com.litemusic.data.prefs.AuthStore
import com.litemusic.shared.model.MsgItem
import com.litemusic.shared.model.MsgSession
import com.litemusic.shared.model.NeteaseUser
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reconciles server history with locally rendered outgoing messages.
 *
 * The private-message endpoint can acknowledge a send before the history
 * endpoint exposes that message. Negative IDs are reserved for local rows;
 * once a matching server row arrives, the server row replaces the local row.
 */
internal fun mergeChatMessages(
    history: List<MsgItem>,
    pending: List<MsgItem>,
): List<MsgItem> = reconcileChatMessages(history, pending).messages

internal data class ChatMessageReconciliation(
    val messages: List<MsgItem>,
    val pending: List<MsgItem>,
)

/**
 * A server message is authoritative when it is a server row (including legacy ID-zero rows). A local pending message is
 * replaced at most once, and only by an outgoing echo addressed to the same chat partner.
 */
internal fun reconcileChatMessages(
    history: List<MsgItem>,
    pending: List<MsgItem>,
    blockedServerIdsByPending: Map<Long, Set<Long>> = emptyMap(),
    blockedServerRowsByPending: Map<Long, Set<String>> = emptyMap(),
): ChatMessageReconciliation {
    val seenServerIds = mutableSetOf<Long>()
    val serverMessages = history.filter { message ->
        message.msgId <= 0L || seenServerIds.add(message.msgId)
    }.toMutableList()
    val unmatchedPending = pending.filter { it.msgId < 0L }.toMutableList()
    val confirmedPending = mutableSetOf<Long>()

    serverMessages.forEach { server ->
        val pendingIndex = unmatchedPending.indexOfFirst { local ->
            server.msgId !in blockedServerIdsByPending[local.msgId].orEmpty() &&
                serverMessageIdentity(server) !in blockedServerRowsByPending[local.msgId].orEmpty() &&
                local.isConfirmedBy(server)
        }
        if (pendingIndex >= 0) {
            confirmedPending += unmatchedPending.removeAt(pendingIndex).msgId
        }
    }

    return ChatMessageReconciliation(
        messages = (serverMessages + unmatchedPending)
            .sortedWith(compareBy<MsgItem> { it.time }.thenBy { it.msgId }),
        pending = pending.filter { it.msgId < 0L && it.msgId !in confirmedPending },
    )
}

private fun MsgItem.isConfirmedBy(server: MsgItem): Boolean {
    val recipient = toUser?.userId ?: return false
    val serverRecipient = server.toUser?.userId ?: return false
    val serverSender = server.fromUser?.userId ?: 0L
    return server.msgId >= 0L &&
        recipient > 0L &&
        serverRecipient == recipient &&
        serverSender > 0L &&
        serverSender != recipient &&
        msg.trim() == server.msg.trim() &&
        kotlin.math.abs(time - server.time) <= PENDING_ECHO_WINDOW_MS
}

private const val PENDING_ECHO_WINDOW_MS = 60_000L

internal data class ChatRequest(val userId: Long, val generation: Long)

/** Associates an async history/send result with one concrete open-chat visit. */
internal class ChatRequestTracker {
    private var nextGeneration = 0L
    private var active: ChatRequest? = null

    fun open(userId: Long): ChatRequest = ChatRequest(userId, ++nextGeneration).also { active = it }
    fun close() { active = null }
    fun currentFor(userId: Long): ChatRequest? = active?.takeIf { it.userId == userId }
    fun isCurrent(request: ChatRequest): Boolean = active == request
}

internal data class SessionPageRequest(
    val generation: Long,
    val lease: AuthStore.Session?,
)

internal fun olderHistoryCursor(messages: List<MsgItem>): Long? = messages
    .asSequence()
    .filter { it.msgId >= 0L && it.time > 0L }
    .minOfOrNull { it.time }

private fun serverMessageIdentity(message: MsgItem): String = if (message.msgId > 0L) {
    "id:${message.msgId}"
} else {
    listOf(
        "row",
        message.fromUser?.userId ?: 0L,
        message.toUser?.userId ?: 0L,
        message.time,
        message.type,
        message.msg,
    ).joinToString(":")
}

internal fun mergeServerHistoryPages(vararg pages: List<MsgItem>): List<MsgItem> {
    val byIdentity = LinkedHashMap<String, MsgItem>()
    pages.forEach { page -> page.forEach { message ->
        if (message.msgId >= 0L) byIdentity.putIfAbsent(serverMessageIdentity(message), message)
    } }
    return byIdentity.values.toList()
}

internal fun addedServerHistoryRowCount(
    current: List<MsgItem>,
    incoming: List<MsgItem>,
): Int {
    val existing = current.asSequence()
        .filter { it.msgId >= 0L }
        .map(::serverMessageIdentity)
        .toSet()
    return incoming.asSequence()
        .filter { it.msgId >= 0L }
        .map(::serverMessageIdentity)
        .filterNot(existing::contains)
        .distinct()
        .count()
}

/** Keeps a paged inbox tied to both its latest request and its login session. */
internal class SessionRequestTracker {
    private var nextGeneration = 0L
    private var active: SessionPageRequest? = null

    fun begin(lease: AuthStore.Session?): SessionPageRequest =
        SessionPageRequest(++nextGeneration, lease).also { active = it }

    fun current(): SessionPageRequest? = active

    fun invalidate() {
        nextGeneration++
        active = null
    }

    fun isCurrent(request: SessionPageRequest, currentLease: AuthStore.Session?): Boolean =
        active == request && if (request.lease == null) currentLease == null
        else currentLease?.sameLoginAs(request.lease) == true
}

internal data class ChatSendSuccessUpdate(
    val messages: List<MsgItem>?,
    val input: String?,
)

internal data class ChatOpenUpdate(
    val messages: List<MsgItem>,
    val pending: List<MsgItem>,
    val input: String,
)

/** Keeps a same-chat refresh stable while isolating drafts and history when changing recipients. */
internal fun reduceChatOpen(
    currentUserId: Long?,
    userId: Long,
    currentMessages: List<MsgItem>,
    pending: List<MsgItem>,
    currentInput: String,
    blockedServerIdsByPending: Map<Long, Set<Long>> = emptyMap(),
    blockedServerRowsByPending: Map<Long, Set<String>> = emptyMap(),
): ChatOpenUpdate {
    val sameChat = currentUserId == userId
    val reconciliation = reconcileChatMessages(
        history = if (sameChat) currentMessages.filter { it.msgId >= 0L } else emptyList(),
        pending = pending,
        blockedServerIdsByPending = blockedServerIdsByPending,
        blockedServerRowsByPending = blockedServerRowsByPending,
    )
    return ChatOpenUpdate(
        messages = reconciliation.messages,
        pending = reconciliation.pending,
        input = if (sameChat) currentInput else "",
    )
}

/** Reduces a completed send without changing a chat that was closed or replaced meanwhile. */
internal fun reduceChatSendSuccess(
    currentMessages: List<MsgItem>,
    pending: List<MsgItem>,
    requestIsCurrent: Boolean,
    currentInput: String,
    inputIsUnchanged: Boolean,
    blockedServerIdsByPending: Map<Long, Set<Long>> = emptyMap(),
    blockedServerRowsByPending: Map<Long, Set<String>> = emptyMap(),
): ChatSendSuccessUpdate = if (!requestIsCurrent) {
    ChatSendSuccessUpdate(messages = null, input = null)
} else {
    ChatSendSuccessUpdate(
        messages = reconcileChatMessages(
            history = currentMessages.filter { it.msgId >= 0L },
            pending = pending,
            blockedServerIdsByPending = blockedServerIdsByPending,
            blockedServerRowsByPending = blockedServerRowsByPending,
        ).messages,
        input = if (inputIsUnchanged) "" else currentInput,
    )
}

class MsgViewModel(
    private val repo: MsgDataSource,
    private val auth: AuthRepository? = null,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val error: String? = null,
        val sessions: List<MsgSession> = emptyList(),
        val sessionsMore: Boolean = false,
        val sessionsOffset: Int = 0,
        val loadingMoreSessions: Boolean = false,
        val chatUser: Long? = null,
        val chatMessages: List<MsgItem> = emptyList(),
        val input: String = "",
        val isSubmitting: Boolean = false,
        val toast: String? = null,
        val hasOlder: Boolean = true,
        val loadingOlder: Boolean = false,
        val currentUserId: Long = 0L,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var nextPendingId = -1L
    private val chatRequests = ChatRequestTracker()
    private var inputGeneration = 0L
    private val pendingByUser = mutableMapOf<Long, MutableList<MsgItem>>()
    private val blockedServerIdsByPending = mutableMapOf<Long, Set<Long>>()
    private val blockedServerRowsByPending = mutableMapOf<Long, Set<String>>()
    private var accountId = 0L
    private var loginSession: AuthStore.Session? = null
    private var syncGeneration = 0L
    private var olderGeneration = 0L
    private val sessionRequests = SessionRequestTracker()

    init {
        auth?.let { source -> viewModelScope.launch {
            source.session.collect { session ->
                val next = session.userId.takeIf { session.loggedIn } ?: 0L
                if (loginSession != null && !session.sameLoginAs(loginSession!!)) {
                    chatRequests.close(); pendingByUser.clear(); blockedServerIdsByPending.clear(); blockedServerRowsByPending.clear()
                    syncGeneration++
                    olderGeneration++
                    sessionRequests.invalidate()
                    _state.value = UiState(loading = false)
                }
                loginSession = session
                accountId = next
                _state.update { it.copy(currentUserId = next) }
            }
        } }
    }

    private fun sameLease(expected: AuthStore.Session?): Boolean =
        if (expected == null) loginSession == null else loginSession?.sameLoginAs(expected) == true

    private fun keepPending(userId: Long, pending: List<MsgItem>) {
        pendingByUser[userId] = pending.toMutableList()
        val allPendingIds = pendingByUser.values.flatten().map { it.msgId }.toSet()
        blockedServerIdsByPending.keys.retainAll(allPendingIds)
        blockedServerRowsByPending.keys.retainAll(allPendingIds)
    }

    fun loadSessions(refresh: Boolean = false) {
        viewModelScope.launch {
            val lease = auth?.currentSession() ?: loginSession
            if (lease != null && loginSession == null) loginSession = lease
            val request = sessionRequests.begin(lease)
            _state.update { current ->
                current.copy(
                    loading = if (refresh && current.sessions.isNotEmpty()) false else true,
                    refreshing = refresh,
                    loadingMoreSessions = false,
                    error = null,
                )
            }
            when (val result = repo.sessions()) {
                is AppResult.Success -> _state.update {
                    if (sessionRequests.isCurrent(request, loginSession)) it.copy(
                        loading = false,
                        refreshing = false,
                        sessions = result.data.sessions,
                        sessionsMore = result.data.more,
                        sessionsOffset = result.data.nextOffset,
                        loadingMoreSessions = false,
                        error = null,
                    ) else it
                }
                is AppResult.Failure -> _state.update {
                    if (sessionRequests.isCurrent(request, loginSession)) it.copy(loading = false, refreshing = false, error = result.message.ifBlank { "私信同步失败" }) else it
                }
            }
        }
    }

    fun loadMoreSessions() {
        val current = _state.value
        if (!current.sessionsMore || current.loadingMoreSessions || current.loading || current.refreshing) return
        val request = sessionRequests.current() ?: return
        val offset = current.sessionsOffset
        _state.update { it.copy(loadingMoreSessions = true) }
        viewModelScope.launch {
            when (val result = repo.sessions(offset)) {
                is AppResult.Success -> _state.update { state ->
                    if (!sessionRequests.isCurrent(request, loginSession)) state else state.copy(
                        sessions = mergeSessions(state.sessions, result.data.sessions),
                        sessionsMore = result.data.more,
                        sessionsOffset = result.data.nextOffset,
                        loadingMoreSessions = false,
                        error = null,
                    )
                }
                is AppResult.Failure -> _state.update { state ->
                    if (!sessionRequests.isCurrent(request, loginSession)) state else state.copy(
                        loadingMoreSessions = false,
                        error = result.message.ifBlank { "更多私信读取失败" },
                    )
                }
            }
        }
    }

    fun openChat(userId: Long) {
        if (userId <= 0L) return
        val generation = ++syncGeneration
        ++olderGeneration
        val request = chatRequests.open(userId)
        if (_state.value.chatUser != userId) inputGeneration++
        _state.update { current ->
            val update = reduceChatOpen(
                currentUserId = current.chatUser,
                userId = userId,
                currentMessages = current.chatMessages,
                pending = pendingByUser[userId].orEmpty(),
                currentInput = current.input,
                blockedServerIdsByPending = blockedServerIdsByPending,
                blockedServerRowsByPending = blockedServerRowsByPending,
            )
            keepPending(userId, update.pending)
            current.copy(chatUser = userId, chatMessages = update.messages, input = update.input, error = null, hasOlder = true, loadingOlder = false)
        }
        viewModelScope.launch {
            when (val result = repo.history(userId)) {
                is AppResult.Success -> _state.update { current ->
                    if (!chatRequests.isCurrent(request) || generation != syncGeneration) {
                        current
                    } else {
                        val reconciliation = reconcileChatMessages(
                            result.data.messages.reversed(),
                            pendingByUser[userId].orEmpty(),
                            blockedServerIdsByPending,
                            blockedServerRowsByPending,
                        )
                        keepPending(userId, reconciliation.pending)
                        current.copy(
                            chatMessages = reconciliation.messages,
                            hasOlder = result.data.hasMore,
                            error = null,
                        )
                    }
                }
                is AppResult.Failure -> _state.update { current ->
                    if (chatRequests.isCurrent(request)) current.copy(error = result.message.ifBlank { "私信历史读取失败" }) else current
                }
            }
        }
    }

    /** Foreground read-only sync; keeps draft and pending send rows intact. */
    fun syncChat() {
        val userId = _state.value.chatUser ?: return
        val request = chatRequests.currentFor(userId) ?: return
        val generation = ++syncGeneration
        viewModelScope.launch {
            when (val result = repo.history(userId)) {
                is AppResult.Success -> _state.update { current ->
                    if (!chatRequests.isCurrent(request) || generation != syncGeneration) current else {
                        val latest = result.data.messages.reversed()
                        val serverHistory = mergeServerHistoryPages(
                            latest,
                            current.chatMessages.filter { it.msgId >= 0L },
                        )
                        val reconciliation = reconcileChatMessages(serverHistory, pendingByUser[userId].orEmpty(), blockedServerIdsByPending, blockedServerRowsByPending)
                        keepPending(userId, reconciliation.pending)
                        current.copy(
                            chatMessages = reconciliation.messages,
                            hasOlder = result.data.hasMore,
                            error = null,
                        )
                    }
                }
                is AppResult.Failure -> _state.update { current ->
                    if (chatRequests.isCurrent(request) && generation == syncGeneration) {
                        current.copy(error = result.message.ifBlank { "私信同步失败" })
                    } else current
                }
            }
        }
    }

    fun loadOlder() {
        val userId = _state.value.chatUser ?: return
        if (_state.value.loadingOlder || !_state.value.hasOlder) return
        val request = chatRequests.currentFor(userId) ?: return
        val before = olderHistoryCursor(_state.value.chatMessages) ?: return
        val generation = ++olderGeneration
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            when (val result = repo.history(userId, before)) {
                is AppResult.Success -> _state.update { current ->
                    if (!chatRequests.isCurrent(request) || generation != olderGeneration) current else {
                        val incoming = result.data.messages.reversed()
                        val currentServerRows = current.chatMessages.filter { it.msgId >= 0L }
                        val added = addedServerHistoryRowCount(currentServerRows, incoming)
                        val serverHistory = mergeServerHistoryPages(incoming, currentServerRows)
                        val reconciliation = reconcileChatMessages(serverHistory, pendingByUser[userId].orEmpty(), blockedServerIdsByPending, blockedServerRowsByPending)
                        keepPending(userId, reconciliation.pending)
                        current.copy(
                            chatMessages = reconciliation.messages,
                            hasOlder = result.data.hasMore && added > 0,
                            loadingOlder = false,
                            error = null,
                        )
                    }
                }
                is AppResult.Failure -> _state.update { current -> if (chatRequests.isCurrent(request) && generation == olderGeneration) current.copy(loadingOlder = false, toast = result.message) else current }
            }
        }
    }

    fun setInput(value: String) {
        inputGeneration++
        _state.update { it.copy(input = value) }
    }

    fun send() {
        val snapshot = _state.value
        val text = snapshot.input.trim()
        val user = snapshot.chatUser ?: return
        val request = chatRequests.currentFor(user) ?: return
        if (text.isBlank()) return
        if (snapshot.isSubmitting) return
        val sendInputGeneration = inputGeneration
        val lease = loginSession
        _state.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            when (val result = repo.send(listOf(user), text)) {
                is AppResult.Success -> {
                    if (result.data.code == 200 && sameLease(lease)) {
                        val localMessage = MsgItem(
                            msgId = nextPendingId--,
                            fromUser = NeteaseUser(
                                userId = accountId,
                                nickname = loginSession?.nickname.orEmpty(),
                                avatarUrl = loginSession?.avatar.orEmpty(),
                            ),
                            toUser = NeteaseUser(userId = user),
                            msg = text,
                            time = System.currentTimeMillis(),
                        )
                        val knownServerIds = snapshot.chatMessages.mapNotNull { it.msgId.takeIf { id -> id > 0L } }.toSet()
                        val knownServerRows = snapshot.chatMessages.asSequence()
                            .filter { it.msgId >= 0L }
                            .map(::serverMessageIdentity)
                            .toSet()
                        pendingByUser.getOrPut(user) { mutableListOf() } += localMessage
                        blockedServerIdsByPending[localMessage.msgId] = knownServerIds
                        blockedServerRowsByPending[localMessage.msgId] = knownServerRows
                        _state.update { current ->
                            val update = reduceChatSendSuccess(
                                currentMessages = current.chatMessages,
                                pending = pendingByUser[user].orEmpty(),
                                requestIsCurrent = chatRequests.isCurrent(request),
                                currentInput = current.input,
                                inputIsUnchanged = inputGeneration == sendInputGeneration,
                                blockedServerIdsByPending = blockedServerIdsByPending,
                                blockedServerRowsByPending = blockedServerRowsByPending,
                            )
                            if (update.messages != null) {
                                current.copy(
                                    input = update.input ?: current.input,
                                    chatMessages = update.messages,
                                    isSubmitting = false,
                                    toast = "Message sent",
                                )
                            } else {
                                current.copy(isSubmitting = false)
                            }
                        }
                        syncChat()
                    } else {
                        _state.update { current ->
                            if (chatRequests.isCurrent(request)) current.copy(
                                isSubmitting = false,
                                toast = result.data.message.ifBlank { "Message send failed" },
                            ) else current.copy(isSubmitting = false)
                        }
                    }
                }
                is AppResult.Failure -> _state.update { current ->
                    if (chatRequests.isCurrent(request)) current.copy(
                        isSubmitting = false,
                        toast = result.message.ifBlank { "Message send failed" },
                    ) else current.copy(isSubmitting = false)
                }
            }
        }
    }

    fun closeChat() {
        chatRequests.close()
        syncGeneration++
        olderGeneration++
        _state.update { it.copy(chatUser = null, chatMessages = emptyList(), input = "", hasOlder = true, loadingOlder = false) }
    }
    fun stopChatSync() {
        syncGeneration++
        olderGeneration++
        _state.update { it.copy(loadingOlder = false) }
    }
    fun toastShown() = _state.update { it.copy(toast = null) }
}

internal fun mergeSessions(current: List<MsgSession>, incoming: List<MsgSession>): List<MsgSession> {
    val byId = LinkedHashMap<String, MsgSession>(current.size + incoming.size)
    current.forEach { byId[sessionIdentity(it)] = it }
    incoming.forEach { byId[sessionIdentity(it)] = it }
    return byId.values.toList()
}

internal fun sessionIdentity(session: MsgSession): String {
    if (session.id > 0L) return "id:${session.id}"
    val participants = listOfNotNull(
        session.fromUser?.userId?.takeIf { it > 0L },
        session.toUser?.userId?.takeIf { it > 0L },
    ).distinct().sorted()
    if (participants.isNotEmpty()) return "users:${participants.joinToString(",")}"
    return "unknown:${session.lastMsgTime}:${session.lastMsg.hashCode()}"
}

internal fun sessionListKeys(sessions: List<MsgSession>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return sessions.map { session ->
        val base = sessionIdentity(session)
        val occurrence = occurrences.getOrDefault(base, 0)
        occurrences[base] = occurrence + 1
        if (occurrence == 0) base else "$base#$occurrence"
    }
}
