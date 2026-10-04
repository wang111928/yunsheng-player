package com.litemusic.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.litemusic.app.data.AuthRepository
import com.litemusic.app.data.PlaylistRepository
import com.litemusic.app.data.SocialRepository
import com.litemusic.data.prefs.AuthStore
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** A failed or partial response must never blank an already verified profile field. */
internal fun nonBlankOrCurrent(incoming: String?, current: String): String =
    incoming?.takeIf { it.isNotBlank() } ?: current

class LibraryViewModel(
    private val auth: AuthRepository,
    private val playlists: PlaylistRepository,
    private val social: SocialRepository,
) : ViewModel() {

    data class UiState(
        val session: AuthStore.Session? = null,
        val myPlaylists: List<Playlist> = emptyList(),
        val likedCount: Int = 0,
        val follows: Int = 0,
        val followeds: Int = 0,
        val level: Int = 0,
        val signature: String = "",
        val membership: String = "会员资料待确认",
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val refreshCoordinator = CoalescedRefreshCoordinator(viewModelScope) { refreshNow() }

    init {
        viewModelScope.launch {
            auth.session.collect { s ->
                val previous = _state.value.session
                val changedLogin = previous == null || !previous.sameLoginAs(s)
                if (changedLogin) refreshCoordinator.invalidate()
                _state.update { if (changedLogin) UiState(session = s, loading = s.loggedIn) else it.copy(session = s) }
                if (s.loggedIn && changedLogin) {
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        refreshCoordinator.request()
    }

    private suspend fun refreshNow() {
        _state.update { it.copy(loading = true, error = null) }
        val uid = auth.ensureUserId()
        if (uid == 0L) {
            _state.update { it.copy(loading = false, error = "未登录") }
            return
        }
        val expected = auth.currentSession()
        val profileRefresh = auth.refreshProfile()
        val listR = playlists.myPlaylists()
        val likedR = playlists.loadLikedIds(force = true)
        val membershipR = auth.membership(uid)
        val detailR = social.userDetail(uid)
        val detail = (detailR as? AppResult.Success)?.data?.takeIf { it.code == 200 }
        val profile = detail?.profile
        val level = detail?.level
        _state.update { current ->
            if (current.session?.userId != uid || !current.session.sameLoginAs(expected)) return@update current
            current.copy(
                loading = false,
                session = (profileRefresh as? AppResult.Success)?.data ?: current.session,
                membership = nonBlankOrCurrent(
                    (membershipR as? AppResult.Success)?.data,
                    current.membership,
                ),
                myPlaylists = if (listR is AppResult.Success) listR.data else current.myPlaylists,
                likedCount = likedR.size,
                follows = profile?.follows?.toInt() ?: current.follows,
                followeds = profile?.followeds?.toInt() ?: current.followeds,
                level = level ?: current.level,
                signature = nonBlankOrCurrent(profile?.signature, current.signature),
                error = listOfNotNull(
                    (profileRefresh as? AppResult.Failure)?.message?.takeIf { it.isNotBlank() },
                    (detailR as? AppResult.Failure)?.message?.takeIf { it.isNotBlank() },
                    (listR as? AppResult.Failure)?.message?.takeIf { it.isNotBlank() },
                ).firstOrNull(),
            )
        }
    }

    fun createPlaylist(name: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val r = playlists.create(name)
            onDone(r is AppResult.Success)
            refresh()
        }
    }
}

/**
 * Coalesces refresh requests made while an existing read is active. A cancelled login generation
 * cannot schedule its deferred refresh after a different account takes over.
 */
internal class CoalescedRefreshCoordinator(
    private val scope: CoroutineScope,
    private val context: CoroutineContext = EmptyCoroutineContext,
    private val refresh: suspend () -> Unit,
) {
    private var activeJob: Job? = null
    private var generation = 0L
    private var pending = false

    fun request() {
        if (activeJob?.isActive == true) {
            pending = true
        } else {
            start()
        }
    }

    fun invalidate() {
        generation += 1
        pending = false
        activeJob?.cancel()
        activeJob = null
    }

    fun close() = invalidate()

    private fun start() {
        val requestGeneration = ++generation
        activeJob = scope.launch(context) {
            try {
                refresh()
            } finally {
                if (generation == requestGeneration && pending) {
                    pending = false
                    start()
                }
            }
        }
    }
}
