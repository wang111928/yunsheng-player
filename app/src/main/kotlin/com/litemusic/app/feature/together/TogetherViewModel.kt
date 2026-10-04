package com.litemusic.app.feature.together

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.TogetherRepository
import com.litemusic.data.prefs.AuthStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal suspend fun publishTogetherOperationResult(
    state: MutableStateFlow<TogetherViewModel.UiState>,
    accountId: Long,
    operation: suspend () -> TogetherRepository.OperationResult,
) {
    val result = operation()
    state.update { current ->
        if (!result.isStale && current.myUid == accountId) current.copy(toast = result.message) else current
    }
}

internal class TogetherPollingLifecycle {
    data class SessionUpdate(
        val clearAccountState: Boolean,
        val pollUserId: Long?,
    )

    val owner = Any()
    private var pageVisible = false
    private var sessionReceived = false
    private var userId = 0L

    fun onSessionChanged(newUserId: Long): SessionUpdate {
        val accountChanged = sessionReceived && userId != newUserId
        sessionReceived = true
        userId = newUserId
        return SessionUpdate(
            clearAccountState = accountChanged,
            pollUserId = newUserId.takeIf { pageVisible },
        )
    }

    fun onPageVisible(): Long? {
        pageVisible = true
        return userId
    }

    fun onPageHidden() {
        pageVisible = false
    }
}

class TogetherViewModel(
    private val repo: TogetherRepository,
    private val auth: AuthStore,
) : ViewModel() {

    data class UiState(
        val room: TogetherRepository.RoomState = TogetherRepository.RoomState(),
        val myUid: Long = 0,
        val followLocked: Boolean = true,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val pollingLifecycle = TogetherPollingLifecycle()

    init {
        viewModelScope.launch {
            auth.session
                .distinctUntilChanged { old, new -> old.userId == new.userId }
                .collect { session ->
                    val userId = session.userId.takeIf { session.loggedIn } ?: 0L
                    _state.update { it.copy(myUid = userId) }
                    val update = pollingLifecycle.onSessionChanged(userId)
                    update.pollUserId?.let { repo.startPolling(it, pollingLifecycle.owner) }
                }
        }
        viewModelScope.launch { repo.room.collect { room -> _state.update { it.copy(room = room) } } }
    }

    fun onPageVisible() {
        pollingLifecycle.onPageVisible()?.let { repo.startPolling(it, pollingLifecycle.owner) }
    }

    fun onPageHidden() {
        pollingLifecycle.onPageHidden()
        repo.stopPolling(pollingLifecycle.owner)
    }

    fun create() {
        if (_state.value.myUid == 0L) {
            _state.update { it.copy(toast = "请先登录") }
            return
        }
        launchOperation { repo.createRoom() }
    }

    fun join(code: String) {
        if (_state.value.myUid == 0L) {
            _state.update { it.copy(toast = "请先登录") }
            return
        }
        launchOperation { repo.joinRoom(code) }
    }

    fun refresh() {
        launchOperation { repo.refresh() }
    }

    fun invite(acceptorId: String) {
        val id = acceptorId.trim().toLongOrNull() ?: 0L
        launchOperation { repo.sendInvite(id) }
    }

    fun leave() {
        launchOperation { repo.leave() }
    }

    fun setFollowLock(value: Boolean) = _state.update { it.copy(followLocked = value) }
    fun toastShown() = _state.update { it.copy(toast = null) }

    private fun launchOperation(operation: suspend () -> TogetherRepository.OperationResult) {
        val accountId = _state.value.myUid
        viewModelScope.launch {
            publishTogetherOperationResult(_state, accountId, operation)
        }
    }

    override fun onCleared() {
        repo.stopPolling(pollingLifecycle.owner)
        super.onCleared()
    }
}
