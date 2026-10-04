package com.litemusic.app.data

import com.litemusic.shared.api.NMApi
import com.litemusic.shared.api.TogetherActionResponse
import com.litemusic.shared.api.TogetherStatusResponse
import com.litemusic.shared.player.PlayPhase
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

interface TogetherRemote {
    suspend fun status(): AppResult<TogetherStatusResponse>
    suspend fun sendInvite(roomId: String, acceptorId: Long): AppResult<TogetherActionResponse>
    suspend fun leave(roomId: String): AppResult<TogetherActionResponse>
}

class NMApiTogetherRemote(private val api: NMApi) : TogetherRemote {
    override suspend fun status() = api.togetherStatus()
    override suspend fun sendInvite(roomId: String, acceptorId: Long) = api.sendTogetherInvite(roomId, acceptorId)
    override suspend fun leave(roomId: String) = api.leaveTogether(roomId)
}

/**
 * 官方一起听状态适配器。
 *
 * 网易云公开可验证的接口只提供当前账号的房间状态、邀请和离开动作；
 * 创建房间、接受邀请和播放上报仍由官方客户端内部 action/heartbeat 完成，
 * 因此这里绝不生成本地假房间，也不伪造跨设备播放同步。
 */
class TogetherRepository(
    private val remote: TogetherRemote,
    private val stateMachine: PlayerStateMachine,
    /**
     * Production binds this to the authenticated account. Tests and legacy callers may omit it,
     * in which case polling's explicit uid remains the identity source.
     */
    private val sessionUserIds: Flow<Long>? = null,
) {
    data class RoomMember(
        val userId: Long,
        val nickname: String,
        val avatarUrl: String? = null,
    )

    data class RoomState(
        val code: String = "",
        val hostId: Long = 0,
        val members: List<RoomMember> = emptyList(),
        val serverStatus: String = "",
        val roomType: String = "",
        val inRoom: Boolean = false,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val lastSyncedAt: Long = 0,
        val localSongId: Long = 0,
        val localPositionMs: Long = 0,
        val localIsPlaying: Boolean = false,
    )

    data class OperationResult(
        val ok: Boolean,
        val message: String,
        /** The response belonged to a superseded account, room, or polling generation. */
        val isStale: Boolean = false,
    )

    private val _room = MutableStateFlow(RoomState())
    val room: StateFlow<RoomState> = _room

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private var polling: Job? = null
    private var activeUserId = 0L
    private var activePollingOwner: Any? = null
    private var roomOwnerUserId = 0L
    private var lifecycleGeneration = 0L
    private var latestRefreshSequence = 0L
    private val requiresSessionIdentity = sessionUserIds != null
    /** null means that the bound session has not emitted yet; zero is a confirmed logout. */
    private var sessionUserId: Long? = if (requiresSessionIdentity) null else 0L
    private val sessionIdentityReady = CompletableDeferred<Unit>()

    private val legacyPollingOwner = Any()

    init {
        sessionUserIds?.let { userIds ->
            scope.launch {
                userIds.collect { userId -> onSessionUserIdChanged(userId.coerceAtLeast(0L)) }
            }
        }
    }

    fun startPolling(myUid: Long) = startPolling(myUid, legacyPollingOwner)

    /**
     * Starts polling for a visible UI owner. Releasing a previous route's owner cannot stop
     * the newer route that replaced it.
     */
    fun startPolling(myUid: Long, owner: Any) {
        synchronized(stateLock) {
            val userId = requestedUserIdLocked(myUid)
            if (userId == null) {
                // A visible page can arrive before DataStore's first value. Keep its lease so
                // the session collector can start it after identity becomes known, but never
                // issue an identity-free request.
                // The latest visible route takes over even when an older route has not yet
                // received its hide callback. That older route cannot later release this lease.
                stopPollingLocked(releaseOwner = false)
                activePollingOwner = owner
                roomOwnerUserId = 0L
                _room.value = RoomState(error = sessionIdentityErrorLocked())
                return
            }
            startPollingLocked(userId, owner)
        }
    }

    private fun startPollingLocked(myUid: Long, owner: Any) {
        if (myUid == 0L) {
            synchronized(stateLock) {
                if (activePollingOwner != null && activePollingOwner !== owner) return
                stopPollingLocked()
                roomOwnerUserId = 0L
                _room.value = RoomState(error = "请先登录")
            }
            return
        }
        if (polling?.isActive == true && activeUserId == myUid && activePollingOwner === owner) return
        stopPollingLocked()
        activeUserId = myUid
        activePollingOwner = owner
        if (roomOwnerUserId != myUid) {
            roomOwnerUserId = myUid
            _room.value = RoomState()
        }
        val generation = lifecycleGeneration
        polling = scope.launch {
            refresh(myUid, generation, owner)
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                refresh(myUid, generation, owner)
            }
        }
    }

    fun stopPolling() {
        synchronized(stateLock) { stopPollingLocked() }
    }

    /** Releases polling only when [owner] still owns the active polling job. */
    fun stopPolling(owner: Any) {
        synchronized(stateLock) {
            if (activePollingOwner === owner) stopPollingLocked()
        }
    }

    /** Clears account-bound state without performing a server-side room action. */
    fun clearForAccountChange() {
        synchronized(stateLock) {
            stopPollingLocked()
            roomOwnerUserId = 0L
            _room.value = RoomState()
        }
    }

    /**
     * Clears account-bound state when [owner] is active or when no route owns polling.
     * A stale route cannot clear state after a newer owner has taken over.
     */
    fun clearForAccountChange(owner: Any) {
        synchronized(stateLock) {
            if (activePollingOwner != null && activePollingOwner !== owner) return
            stopPollingLocked()
            roomOwnerUserId = 0L
            _room.value = RoomState()
        }
    }

    suspend fun refresh(): OperationResult {
        awaitSessionIdentity()
        val request = synchronized(stateLock) {
            val userId = currentSessionUserIdLocked()
                ?: return OperationResult(false, sessionIdentityErrorLocked())
            RefreshRequest(userId, lifecycleGeneration, activePollingOwner, ++latestRefreshSequence)
        }
        return refresh(request)
    }

    private suspend fun refresh(ownerId: Long, generation: Long, pollingOwner: Any): OperationResult {
        val request = synchronized(stateLock) {
            if (activeUserId != ownerId || lifecycleGeneration != generation || activePollingOwner !== pollingOwner ||
                !isSessionUserCurrentLocked(ownerId)
            ) {
                return OperationResult(false, "一起听状态已过期", isStale = true)
            }
            RefreshRequest(ownerId, generation, pollingOwner, ++latestRefreshSequence)
        }
        return refresh(request)
    }

    private suspend fun refresh(request: RefreshRequest): OperationResult {
        val current = stateMachine.state.value
        synchronized(stateLock) {
            if (!isCurrentLocked(request)) return OperationResult(false, "一起听状态已过期", isStale = true)
            _room.update {
                it.copy(
                    isRefreshing = true,
                    error = null,
                    localSongId = current.current?.id ?: 0,
                    localPositionMs = current.positionMs,
                    localIsPlaying = current.phase == PlayPhase.PLAYING,
                )
            }
        }
        return when (val result = remote.status()) {
            is AppResult.Success -> {
                if (result.data.code != 200) {
                    val message = result.data.message.ifBlank { "一起听状态获取失败(${result.data.code})" }
                    synchronized(stateLock) {
                        if (!isCurrentLocked(request)) return OperationResult(false, "一起听状态已过期", isStale = true)
                        _room.update { it.copy(isRefreshing = false, error = message) }
                    }
                    return OperationResult(false, message)
                }
                val data = result.data.data
                val info = data?.roomInfo
                if (data?.inRoom == true && info != null && info.roomId.isNotBlank()) {
                    synchronized(stateLock) {
                        if (!isCurrentLocked(request)) return OperationResult(false, "一起听状态已过期", isStale = true)
                        _room.value = RoomState(
                            code = info.roomId,
                            hostId = info.creatorId,
                            members = info.roomUsers.map {
                                RoomMember(it.userId, it.nickname.ifBlank { "网易云用户" }, it.avatarUrl)
                            },
                            serverStatus = data.status,
                            roomType = info.roomType,
                            inRoom = true,
                            lastSyncedAt = System.currentTimeMillis(),
                            localSongId = current.current?.id ?: 0,
                            localPositionMs = current.positionMs,
                            localIsPlaying = current.phase == PlayPhase.PLAYING,
                        )
                        roomOwnerUserId = request.ownerId
                    }
                    OperationResult(true, "一起听状态已更新")
                } else {
                    synchronized(stateLock) {
                        if (!isCurrentLocked(request)) return OperationResult(false, "一起听状态已过期", isStale = true)
                        _room.value = RoomState(
                            lastSyncedAt = System.currentTimeMillis(),
                            localSongId = current.current?.id ?: 0,
                            localPositionMs = current.positionMs,
                            localIsPlaying = current.phase == PlayPhase.PLAYING,
                        )
                        roomOwnerUserId = request.ownerId
                    }
                    OperationResult(true, "当前账号不在一起听房间")
                }
            }
            is AppResult.Failure -> {
                val message = result.message.ifBlank { "一起听状态获取失败" }
                synchronized(stateLock) {
                    if (!isCurrentLocked(request)) return OperationResult(false, "一起听状态已过期", isStale = true)
                    _room.update { it.copy(isRefreshing = false, error = message) }
                }
                OperationResult(false, message)
            }
        }
    }

    suspend fun createRoom(): OperationResult =
        OperationResult(false, "当前版本无法独立创建官方一起听房间，请先在网易云官方 App 创建房间")

    suspend fun joinRoom(code: String): OperationResult {
        val normalized = code.trim()
        if (normalized.isBlank()) return OperationResult(false, "请输入房间 ID")
        val refreshed = refresh()
        if (!refreshed.ok) return refreshed
        return if (_room.value.inRoom && _room.value.code == normalized) {
            OperationResult(true, "已连接到当前一起听房间")
        } else {
            OperationResult(false, "官方接口不支持 Lite 直接用房间 ID 加入，请在网易云官方 App 接受邀请")
        }
    }

    suspend fun sendInvite(acceptorId: Long): OperationResult {
        val request = synchronized(stateLock) {
            val userId = currentSessionUserIdLocked()
                ?: return OperationResult(false, sessionIdentityErrorLocked())
            val current = _room.value
            if (!current.inRoom || current.code.isBlank() || (roomOwnerUserId != 0L && roomOwnerUserId != userId)) {
                return OperationResult(false, "当前不在一起听房间")
            }
            AccountOperationRequest(current.code, userId, lifecycleGeneration)
        }
        if (acceptorId <= 0) return OperationResult(false, "请输入有效的用户 ID")
        return when (val result = remote.sendInvite(request.roomId, acceptorId)) {
            is AppResult.Success -> {
                synchronized(stateLock) {
                    if (!isCurrentAccountOperationLocked(request)) return OperationResult(false, "邀请结果已过期", isStale = true)
                    if (result.data.code == 200) OperationResult(true, "邀请已发送")
                    else OperationResult(false, result.data.message.ifBlank { "邀请发送失败(${result.data.code})" })
                }
            }
            is AppResult.Failure -> synchronized(stateLock) {
                if (!isCurrentAccountOperationLocked(request)) OperationResult(false, "邀请结果已过期", isStale = true)
                else OperationResult(false, result.message)
            }
        }
    }

    suspend fun leave(): OperationResult {
        val request = synchronized(stateLock) {
            val userId = currentSessionUserIdLocked()
                ?: return OperationResult(false, sessionIdentityErrorLocked())
            val current = _room.value
            if (!current.inRoom || current.code.isBlank()) {
                stopPollingLocked()
                roomOwnerUserId = 0L
                _room.value = RoomState()
                return OperationResult(true, "当前不在一起听房间")
            }
            if (roomOwnerUserId != 0L && roomOwnerUserId != userId) {
                return OperationResult(false, "一起听状态已过期")
            }
            LeaveRequest(current.code, userId, lifecycleGeneration)
        }
        return when (val result = remote.leave(request.roomId)) {
            is AppResult.Success -> {
                if (result.data.code != 200) {
                    synchronized(stateLock) {
                        if (!isCurrentAccountOperationLocked(request)) OperationResult(false, "退出结果已过期", isStale = true)
                        else OperationResult(false, result.data.message.ifBlank { "退出一起听失败(${result.data.code})" })
                    }
                } else {
                    synchronized(stateLock) {
                        val current = _room.value
                        if (!isSessionUserCurrentLocked(request.ownerId) ||
                            lifecycleGeneration != request.generation ||
                            (roomOwnerUserId != 0L && roomOwnerUserId != request.ownerId) ||
                            !current.inRoom ||
                            current.code != request.roomId
                        ) {
                            return OperationResult(false, "退出结果已过期", isStale = true)
                        }
                        stopPollingLocked()
                        roomOwnerUserId = 0L
                        _room.value = RoomState()
                    }
                    OperationResult(true, "已退出一起听房间")
                }
            }
            is AppResult.Failure -> synchronized(stateLock) {
                if (!isCurrentAccountOperationLocked(request)) OperationResult(false, "退出结果已过期", isStale = true)
                else OperationResult(false, result.message)
            }
        }
    }

    private data class RefreshRequest(
        val ownerId: Long,
        val generation: Long,
        val pollingOwner: Any?,
        val sequence: Long,
    )
    private data class LeaveRequest(val roomId: String, val ownerId: Long, val generation: Long)
    private data class AccountOperationRequest(val roomId: String, val ownerId: Long, val generation: Long)

    private fun isCurrentLocked(request: RefreshRequest): Boolean =
        (!requiresSessionIdentity || sessionUserId == request.ownerId) &&
            lifecycleGeneration == request.generation &&
            activePollingOwner === request.pollingOwner &&
            latestRefreshSequence == request.sequence

    private fun stopPollingLocked(releaseOwner: Boolean = true) {
        polling?.cancel()
        polling = null
        activeUserId = 0L
        if (releaseOwner) activePollingOwner = null
        lifecycleGeneration++
    }

    private fun onSessionUserIdChanged(newUserId: Long) {
        synchronized(stateLock) {
            if (sessionUserId == newUserId) return
            sessionUserId = newUserId
            sessionIdentityReady.complete(Unit)
            val visibleOwner = activePollingOwner
            stopPollingLocked(releaseOwner = false)
            roomOwnerUserId = 0L
            _room.value = RoomState()
            if (newUserId > 0L && visibleOwner != null) {
                startPollingLocked(newUserId, visibleOwner)
            }
        }
    }

    private fun requestedUserIdLocked(requestedUserId: Long): Long? {
        if (!requiresSessionIdentity) return requestedUserId
        // ViewModels receive the same asynchronous session source. Treat their uid as a UI
        // hint only: the repository's bound stream is the sole authority for HTTP and cache.
        return sessionUserId?.takeIf { it > 0L }
    }

    private fun currentSessionUserIdLocked(): Long? =
        if (requiresSessionIdentity) sessionUserId?.takeIf { it > 0L } else activeUserId

    private fun isSessionUserCurrentLocked(userId: Long): Boolean =
        !requiresSessionIdentity || sessionUserId == userId

    private fun isCurrentAccountOperationLocked(request: AccountOperationRequest): Boolean =
        isSessionUserCurrentLocked(request.ownerId) &&
            lifecycleGeneration == request.generation &&
            _room.value.code == request.roomId &&
            (roomOwnerUserId == 0L || roomOwnerUserId == request.ownerId)

    private fun isCurrentAccountOperationLocked(request: LeaveRequest): Boolean =
        isSessionUserCurrentLocked(request.ownerId) && lifecycleGeneration == request.generation

    private fun sessionIdentityErrorLocked(): String = when {
        !requiresSessionIdentity || sessionUserId == 0L -> "请先登录"
        sessionUserId == null -> "登录状态加载中"
        else -> "登录账号已切换"
    }

    /** A cold-start manual refresh waits briefly for DataStore's first identity, never for HTTP. */
    private suspend fun awaitSessionIdentity() {
        if (requiresSessionIdentity && synchronized(stateLock) { sessionUserId == null }) {
            withTimeoutOrNull(SESSION_IDENTITY_WAIT_MS) { sessionIdentityReady.await() }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
        private const val SESSION_IDENTITY_WAIT_MS = 3_000L
    }
}
