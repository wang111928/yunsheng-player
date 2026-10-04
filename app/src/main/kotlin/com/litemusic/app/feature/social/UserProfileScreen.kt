package com.litemusic.app.feature.social

import android.content.Intent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.litemusic.design.components.NmlButton as FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.litemusic.design.components.nmlPressable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.litemusic.app.BuildConfig
import com.litemusic.app.data.SocialRepository
import com.litemusic.app.data.UserEventPage
import com.litemusic.app.data.NotesRepository
import com.litemusic.app.feature.notes.EventCommentsState
import com.litemusic.app.feature.notes.EventPostDetail
import com.litemusic.app.feature.notes.appendCommentPage
import com.litemusic.app.feature.notes.afterComment
import com.litemusic.app.feature.notes.afterLike
import com.litemusic.app.feature.notes.commentPageFailure
import com.litemusic.app.feature.notes.commentStateForThread
import com.litemusic.app.feature.notes.eventShareText
import com.litemusic.app.feature.playlist.rememberPlaylistPlayer
import com.litemusic.app.ui.Routes
import com.litemusic.design.components.LoadingView
import com.litemusic.shared.api.EventPost
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.Comment
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal fun canStartProfileFollow(state: UserProfileViewModel.UiState): Boolean =
    state.userId > 0L && !state.isSelf && !state.followInFlight

internal fun isOwnProfile(profileUserId: Long, accountUserId: Long): Boolean =
    profileUserId > 0L && accountUserId > 0L && profileUserId == accountUserId

internal fun profileStateAfterLoadFailure(
    previous: UserProfileViewModel.UiState?,
    userId: Long,
    message: String,
    isSelf: Boolean,
): UserProfileViewModel.UiState =
    (previous?.takeIf { it.userId == userId } ?: UserProfileViewModel.UiState(userId = userId)).copy(
        loading = false,
        error = message,
        followInFlight = false,
        isSelf = isSelf,
    )

internal fun profileFactRows(
    level: Int,
    listenSongs: Int,
    eventCount: Int,
    gender: Int,
    birthday: Long,
    playlistCount: Int,
): List<String> = buildList {
    if (level > 0) add("等级 $level")
    if (listenSongs > 0) add("听歌 $listenSongs 首")
    add("动态 $eventCount 条")
    when (gender) {
        1 -> add("男")
        2 -> add("女")
    }
    if (birthday > 0L) {
        val date = Instant.ofEpochMilli(birthday).atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
        add("生日 $date")
    }
    add("歌单 $playlistCount 个")
}

internal enum class ProfileStatAction { FOLLOWS, FOLLOWEDS }

internal data class ProfileHeaderStat(
    val label: String,
    val value: String,
    val action: ProfileStatAction? = null,
)

/** Keep the four primary account figures distinct so they do not collapse into one narrow text block. */
internal fun profileHeaderStats(
    follows: Int,
    followeds: Int,
    level: Int,
    listenSongs: Int,
): List<ProfileHeaderStat> = listOf(
    ProfileHeaderStat("关注", follows.toString(), ProfileStatAction.FOLLOWS),
    ProfileHeaderStat("粉丝", followeds.toString(), ProfileStatAction.FOLLOWEDS),
    ProfileHeaderStat("等级", "Lv.$level"),
    ProfileHeaderStat("听歌", listenSongs.toString()),
)

/** The profile only exposes sections backed by a public profile API. */
internal enum class ProfileSection(val label: String) { HOME("主页"), EVENTS("动态"), PLAYLISTS("歌单") }

internal data class ProfileSignaturePresentation(
    val text: String,
    val canExpand: Boolean,
    val expanded: Boolean,
)

internal fun profileSignaturePresentation(
    signature: String,
    expanded: Boolean,
    collapsedCharacterLimit: Int = 56,
): ProfileSignaturePresentation {
    val cleaned = signature.trim()
    val canExpand = cleaned.length > collapsedCharacterLimit
    val text = if (canExpand && !expanded) {
        cleaned.take(collapsedCharacterLimit).trimEnd() + "…"
    } else {
        cleaned
    }
    return ProfileSignaturePresentation(text = text, canExpand = canExpand, expanded = expanded && canExpand)
}

internal fun compactProfileCount(count: Int): String = when {
    count < 10_000 -> count.toString()
    else -> "%.1f万".format(java.util.Locale.CHINA, count / 10_000.0)
}

internal fun profilePlaylistLoadError(result: AppResult<List<Playlist>>): String? = when (result) {
    is AppResult.Success -> null
    is AppResult.Failure -> result.message.ifBlank { "歌单读取失败，请稍后重试" }
}

internal fun profilePlaylistsAfterLoad(
    result: AppResult<List<Playlist>>,
    retained: List<Playlist>,
): List<Playlist> = (result as? AppResult.Success)?.data ?: retained

internal fun profilePlaylistCount(
    serverCount: Int,
    playlists: List<Playlist>,
    retainedCount: Int,
): Int = serverCount.takeIf { it > 0 } ?: playlists.size.takeIf { it > 0 } ?: retainedCount

internal fun appendProfilePlaylists(current: List<Playlist>, page: List<Playlist>): List<Playlist> =
    (current + page).filter { it.id > 0L }.distinctBy { it.id }

internal fun appendProfileEvents(current: List<EventPost>, page: List<EventPost>): List<EventPost> =
    (current + page).filter { it.id > 0L }.distinctBy { it.id }

/** Profile dynamic details use the exact comment pagination state used by the notes feed. */
internal fun profileCommentStateAfterPage(
    previous: EventCommentsState,
    page: NotesRepository.CommentPage,
): EventCommentsState = previous.appendCommentPage(page)

internal fun canLoadMoreProfileEvents(more: Boolean, currentTime: Long, nextTime: Long): Boolean =
    more && nextTime > 0L && (currentTime <= 0L || nextTime < currentTime)

private const val MAX_CONSECUTIVE_EMPTY_PROFILE_EVENT_PAGES = 3

/** A moving cursor with repeated rows is recoverable, but must not spin the scroll trigger forever. */
internal fun UserProfileViewModel.UiState.appendProfileEventPage(page: UserEventPage): UserProfileViewModel.UiState {
    val merged = appendProfileEvents(events, page.posts)
    val noNewEvents = merged.size == events.size
    val cursorDidNotAdvance = page.more && !canLoadMoreProfileEvents(page.more, eventTime, page.nextTime)
    val emptyPageCount = if (noNewEvents) eventConsecutiveEmptyPages + 1 else 0
    val terminalCursor = page.more && cursorDidNotAdvance
    val pauseAutomaticPaging = page.more && !cursorDidNotAdvance &&
        emptyPageCount >= MAX_CONSECUTIVE_EMPTY_PROFILE_EVENT_PAGES
    return copy(
        events = merged,
        eventTime = if (page.nextTime > 0L) page.nextTime else eventTime,
        eventsMore = if (terminalCursor) false else page.more,
        loadingMoreEvents = false,
        eventMoreError = null,
        eventConsecutiveEmptyPages = emptyPageCount,
        eventsAutoLoadBlocked = pauseAutomaticPaging,
    )
}

private const val PROFILE_PLAYLIST_PAGE_SIZE = 30

internal enum class ProfilePlaylistAction { PLAY_LOADED_TRACKS, OPEN_DETAIL }

/** User-profile playlist rows are summaries. Open the detail screen before playing an empty one. */
internal fun profilePlaylistAction(playlist: Playlist): ProfilePlaylistAction =
    if (playlist.tracks.isEmpty()) ProfilePlaylistAction.OPEN_DETAIL
    else ProfilePlaylistAction.PLAY_LOADED_TRACKS

private data class ProfileLoadResponses(
    val detail: AppResult<com.litemusic.shared.model.UserDetail>,
    val playlists: AppResult<List<Playlist>>,
)

class UserProfileViewModel(
    private val repo: SocialRepository,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val userId: Long = 0L,
        val nickname: String = "",
        val avatar: String = "",
        val signature: String = "",
        val follows: Int = 0,
        val followeds: Int = 0,
        val level: Int = 0,
        val listenSongs: Int = 0,
        val eventCount: Int = 0,
        val gender: Int = 0,
        val birthday: Long = 0L,
        val backgroundUrl: String = "",
        val playlistCount: Int = 0,
        val isSelf: Boolean = false,
        val followed: Boolean = false,
        val playlists: List<Playlist> = emptyList(),
        val playlistOffset: Int = 0,
        val playlistsMore: Boolean = false,
        val loadingMorePlaylists: Boolean = false,
        val playlistMoreError: String? = null,
        val events: List<EventPost> = emptyList(),
        val eventTime: Long = -1L,
        val eventsMore: Boolean = false,
        val loadingMoreEvents: Boolean = false,
        val eventMoreError: String? = null,
        val eventConsecutiveEmptyPages: Int = 0,
        val eventsAutoLoadBlocked: Boolean = false,
        val eventsError: String? = null,
        val error: String? = null,
        val followInFlight: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null
    private var loadGeneration = 0L
    private var followGeneration = 0L
    private val latestSuccessful = mutableMapOf<Long, UiState>()

    fun load(uid: Long) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        val retained = latestSuccessful[uid]
        _state.value = retained?.copy(loading = true, error = null, toast = null) ?: UiState(userId = uid)
        loadJob = viewModelScope.launch {
            // AuthRepository keeps the authenticated user id locally, so establish the
            // identity before remote profile requests. This also keeps self-only actions
            // available when the profile endpoint is temporarily offline.
            val accountUserId = repo.me()
            if (generation != loadGeneration) return@launch
            val self = isOwnProfile(uid, accountUserId)
            _state.value = _state.value
                .takeIf { it.userId == uid }
                ?.copy(isSelf = self)
                ?: UiState(userId = uid, isSelf = self)
            val responses = coroutineScope {
                val detailRequest = async { repo.userDetail(uid) }
                val playlistRequest = async { repo.userPlaylists(uid) }
                ProfileLoadResponses(
                    detail = detailRequest.await(),
                    playlists = playlistRequest.await(),
                )
            }
            if (generation != loadGeneration) return@launch
            val detail = responses.detail
            val playlists = responses.playlists
            val detailData = (detail as? AppResult.Success)?.data?.takeIf { it.code == 200 }
            val profile = detailData?.profile
            if (profile == null) {
                val message = (detail as? AppResult.Failure)?.message
                    ?: "个人资料读取失败，请重试"
                _state.value = profileStateAfterLoadFailure(
                    previous = _state.value,
                    userId = uid,
                    message = message,
                    isSelf = self,
                )
                return@launch
            }
            val next = UiState(
                loading = false,
                userId = uid,
                nickname = profile.nickname,
                avatar = profile.avatarUrl,
                signature = profile.signature,
                follows = profile.follows.toInt(),
                followeds = profile.followeds.toInt(),
                level = detailData.level,
                listenSongs = detailData.listenSongs,
                eventCount = profile.eventCount,
                gender = profile.gender,
                birthday = profile.birthday,
                backgroundUrl = profile.backgroundUrl,
                playlistCount = profilePlaylistCount(
                    profile.playlistCount,
                    (playlists as? AppResult.Success)?.data.orEmpty(),
                    retained?.playlistCount ?: 0,
                ),
                isSelf = self,
                followed = profile.followed,
                playlists = profilePlaylistsAfterLoad(playlists, retained?.playlists.orEmpty()),
                playlistOffset = (playlists as? AppResult.Success)?.data?.size ?: retained?.playlistOffset ?: 0,
                playlistsMore = (playlists as? AppResult.Success)?.data?.let {
                    it.size == PROFILE_PLAYLIST_PAGE_SIZE
                } ?: retained?.playlistsMore ?: false,
                error = profilePlaylistLoadError(playlists),
                events = retained?.events.orEmpty(),
                eventTime = retained?.eventTime ?: -1L,
                eventsMore = retained?.eventsMore ?: false,
                eventsError = null,
                // Do not let a retained cursor start a concurrent page request while the
                // first page of this refresh is still resolving.
                loadingMoreEvents = true,
            )
            latestSuccessful[uid] = next
            _state.value = next
            when (val events = repo.userEvents(uid)) {
                is AppResult.Success -> {
                    if (generation != loadGeneration) return@launch
                    val page = events.data
                    val updated = _state.value.copy(
                        events = emptyList(),
                        eventTime = -1L,
                        eventConsecutiveEmptyPages = 0,
                        eventsAutoLoadBlocked = false,
                    ).appendProfileEventPage(page).copy(eventsError = null)
                    latestSuccessful[uid] = updated
                    _state.value = updated
                }
                is AppResult.Failure -> {
                    if (generation != loadGeneration) return@launch
                    _state.value = _state.value.copy(loadingMoreEvents = false, eventsError = events.message)
                }
            }
        }
    }

    fun loadMorePlaylists() {
        val current = _state.value
        if (current.loading || current.loadingMorePlaylists || !current.playlistsMore || current.playlistMoreError != null) return
        val uid = current.userId
        val generation = loadGeneration
        _state.value = current.copy(loadingMorePlaylists = true)
        viewModelScope.launch {
            when (val result = repo.userPlaylists(uid, offset = current.playlistOffset)) {
                is AppResult.Success -> {
                    if (generation != loadGeneration || _state.value.userId != uid) return@launch
                    val page = result.data
                    val merged = appendProfilePlaylists(_state.value.playlists, page)
                    _state.value = _state.value.copy(
                        playlists = merged,
                        playlistOffset = current.playlistOffset + page.size,
                        playlistsMore = page.size == PROFILE_PLAYLIST_PAGE_SIZE && merged.size > current.playlists.size,
                        loadingMorePlaylists = false,
                        playlistMoreError = null,
                    )
                }
                is AppResult.Failure -> if (generation == loadGeneration && _state.value.userId == uid) {
                    _state.value = _state.value.copy(
                        loadingMorePlaylists = false,
                        playlistMoreError = result.message.ifBlank { "更多歌单读取失败" },
                    )
                }
            }
        }
    }

    fun retryMorePlaylists() {
        _state.value = _state.value.copy(playlistMoreError = null)
        loadMorePlaylists()
    }

    fun loadMoreEvents() = loadMoreEvents(resumeAutomaticPaging = true)

    fun loadMoreEventsAutomatically() {
        if (_state.value.eventsAutoLoadBlocked) return
        loadMoreEvents(resumeAutomaticPaging = false)
    }

    private fun loadMoreEvents(resumeAutomaticPaging: Boolean) {
        val current = _state.value
        if (current.loading || current.loadingMoreEvents || !current.eventsMore ||
            current.eventMoreError != null || current.eventsError != null
        ) return
        val uid = current.userId
        val generation = loadGeneration
        _state.value = current.copy(
            loadingMoreEvents = true,
            eventsAutoLoadBlocked = if (resumeAutomaticPaging) false else current.eventsAutoLoadBlocked,
        )
        viewModelScope.launch {
            when (val result = repo.userEvents(uid, time = current.eventTime)) {
                is AppResult.Success -> {
                    if (generation != loadGeneration || _state.value.userId != uid) return@launch
                    _state.value = _state.value.appendProfileEventPage(result.data)
                }
                is AppResult.Failure -> if (generation == loadGeneration && _state.value.userId == uid) {
                    _state.value = _state.value.copy(
                        loadingMoreEvents = false,
                        eventMoreError = result.message.ifBlank { "更多动态读取失败" },
                    )
                }
            }
        }
    }

    fun retryMoreEvents() {
        _state.value = _state.value.copy(eventMoreError = null)
        loadMoreEvents()
    }

    fun toggleFollow() {
        val current = _state.value
        if (!canStartProfileFollow(current)) return
        val target = !current.followed
        val generation = ++followGeneration
        _state.value = current.copy(followed = target, followInFlight = true)
        viewModelScope.launch {
            val result = repo.follow(current.userId, target)
            if (_state.value.userId != current.userId || generation != followGeneration) return@launch
            when (result) {
                is AppResult.Success -> {
                    if (result.data.code != 200) {
                        _state.value = _state.value.copy(
                            followed = !target,
                            followInFlight = false,
                            toast = result.data.message.ifBlank { "Follow operation failed" },
                        )
                    } else {
                        val updated = _state.value.copy(followInFlight = false, toast = "Follow state updated")
                        latestSuccessful[updated.userId] = updated.copy(toast = null)
                        _state.value = updated
                    }
                }
                is AppResult.Failure -> _state.value = _state.value.copy(
                    followed = !target,
                    followInFlight = false,
                    toast = result.message.ifBlank { "Follow operation failed" },
                )
            }
        }
    }

    fun toastShown() {
        _state.value = _state.value.copy(toast = null)
    }
}

@Composable
private fun ProfileStat(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp).clip(RoundedCornerShape(12.dp))
            .then(if (onClick == null) Modifier else Modifier.nmlPressable(onClick = onClick))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProfileSectionTabs(
    selected: ProfileSection,
    onSelect: (ProfileSection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProfileSection.entries.forEach { section ->
            val active = section == selected
            Column(
                modifier = Modifier
                    .weight(1f).heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .semantics { this.selected = active }
                    .nmlPressable(onClick = { if (!active) onSelect(section) })
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    section.label,
                    style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .width(if (active) 28.dp else 0.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun ProfilePlaylistCard(
    playlist: Playlist,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = playlist.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(62.dp).clip(RoundedCornerShape(14.dp)),
            )
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${playlist.trackCount} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "播放歌单", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ProfileEventCard(post: EventPost, onOpenDetail: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDetail),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = post.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(38.dp).clip(CircleShape),
                )
                Text(
                    post.nickname.ifBlank { "云村用户" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
            }
            if (post.content.isNotBlank()) {
                Text(
                    post.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            post.imageUrls.firstOrNull()?.let { image ->
                AsyncImage(
                    model = image,
                    contentDescription = "动态图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
            }
            Text(
                "♡ ${compactProfileCount(post.likeCount)}   ▢ ${compactProfileCount(post.commentCount)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
fun UserProfileScreen(
    navController: NavController,
    uid: Long,
    viewModel: UserProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
        UserProfileViewModel(org.koin.java.KoinJavaComponent.get(SocialRepository::class.java))
    },
) {
    val state by viewModel.state.collectAsState()
    val player = rememberPlaylistPlayer()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val commentRepository: NotesRepository = koinInject()
    val detailScope = rememberCoroutineScope()
    var signatureExpanded by rememberSaveable(uid) { mutableStateOf(false) }
    var section by rememberSaveable(uid) { mutableStateOf(ProfileSection.HOME) }
    var selectedEvent by remember(uid) { mutableStateOf<EventPost?>(null) }
    var detailComments by remember(uid) { mutableStateOf<Map<Long, EventCommentsState>>(emptyMap()) }
    var eventOverrides by remember(uid) { mutableStateOf<Map<Long, EventPost>>(emptyMap()) }
    var likingEventIds by remember(uid) { mutableStateOf<Set<Long>>(emptySet()) }
    var postingCommentIds by remember(uid) { mutableStateOf<Set<Long>>(emptySet()) }
    var mutatingCommentIds by remember(uid) { mutableStateOf<Set<Long>>(emptySet()) }
    var commentSubmissionVersions by remember(uid) { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var interactionToast by remember(uid) { mutableStateOf<String?>(null) }
    val profileListState = rememberLazyListState()

    fun visibleEvent(post: EventPost): EventPost = eventOverrides[post.id] ?: post

    fun loadEventComments(post: EventPost, offset: Int = 0) {
        val current = detailComments[post.id]
        if (current?.loading == true || current?.loadingMore == true) return
        if (post.commentThreadId.isBlank()) {
            detailComments = detailComments + (post.id to commentStateForThread(post.commentThreadId))
            return
        }
        detailComments = detailComments + (post.id to if (offset == 0) {
            commentStateForThread(post.commentThreadId)
        } else {
            (current ?: EventCommentsState()).copy(loadingMore = true, error = null, autoLoadBlocked = false)
        })
        detailScope.launch {
            when (val result = commentRepository.loadComments(post, offset = offset)) {
                is AppResult.Success -> {
                    val previous = detailComments[post.id] ?: EventCommentsState()
                    detailComments = detailComments + (post.id to profileCommentStateAfterPage(previous, result.data))
                }
                is AppResult.Failure -> {
                    val message = result.message.ifBlank { if (offset == 0) "评论加载失败" else "更多评论加载失败" }
                    val failed = detailComments[post.id] ?: EventCommentsState()
                    detailComments = detailComments + (post.id to if (offset == 0) {
                        EventCommentsState(error = message)
                    } else {
                        commentPageFailure(failed, message)
                    })
                }
            }
        }
    }

    fun shareEvent(post: EventPost) {
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, eventShareText(post))
                .let { Intent.createChooser(it, "分享动态") },
        )
    }

    fun toggleEventLike(post: EventPost) {
        if (post.id <= 0L || post.commentThreadId.isBlank() || post.id in likingEventIds) return
        val target = !post.liked
        likingEventIds = likingEventIds + post.id
        detailScope.launch {
            when (val result = commentRepository.likeEvent(post, target)) {
                is AppResult.Success -> eventOverrides = eventOverrides + (post.id to post.afterLike(target))
                is AppResult.Failure -> interactionToast = result.message.ifBlank { "点赞操作失败" }
            }
            likingEventIds = likingEventIds - post.id
        }
    }

    fun postEventComment(post: EventPost, content: String) {
        val text = content.trim()
        if (text.isBlank() || post.id <= 0L || post.commentThreadId.isBlank() || post.id in postingCommentIds) return
        postingCommentIds = postingCommentIds + post.id
        detailScope.launch {
            when (val result = commentRepository.postEventComment(post, text)) {
                is AppResult.Success -> {
                    result.data.comment?.let { comment ->
                        val current = detailComments[post.id] ?: EventCommentsState()
                        detailComments = detailComments + (post.id to current.copy(
                            comments = listOf(comment) + current.comments,
                            total = maxOf(current.total + 1, (current.comments.size + 1).toLong()),
                        ))
                    }
                    eventOverrides = eventOverrides + (post.id to post.afterComment())
                    commentSubmissionVersions = commentSubmissionVersions + (
                        post.id to ((commentSubmissionVersions[post.id] ?: 0L) + 1L)
                    )
                    interactionToast = "评论成功"
                }
                is AppResult.Failure -> interactionToast = result.message.ifBlank { "评论发布失败" }
            }
            postingCommentIds = postingCommentIds - post.id
        }
    }

    fun toggleEventCommentLike(post: EventPost, comment: Comment) {
        val commentId = comment.commentId
        if (commentId <= 0L || commentId in mutatingCommentIds) return
        val target = !comment.liked
        mutatingCommentIds = mutatingCommentIds + commentId
        detailScope.launch {
            when (val result = commentRepository.likeEventComment(post, commentId, target)) {
                is AppResult.Success -> {
                    detailComments[post.id]?.let { current ->
                        detailComments = detailComments + (post.id to current.copy(
                            comments = current.comments.map { visible ->
                                if (visible.commentId == commentId) visible.afterLike(target) else visible
                            },
                        ))
                    }
                }
                is AppResult.Failure -> interactionToast = result.message.ifBlank { "评论点赞操作失败" }
            }
            mutatingCommentIds = mutatingCommentIds - commentId
        }
    }

    fun replyToEventComment(post: EventPost, comment: Comment, content: String) {
        val text = content.trim()
        val commentId = comment.commentId
        if (text.isBlank() || commentId <= 0L || commentId in mutatingCommentIds) return
        mutatingCommentIds = mutatingCommentIds + commentId
        detailScope.launch {
            when (val result = commentRepository.replyEventComment(post, commentId, text)) {
                is AppResult.Success -> {
                    result.data.comment?.let { created ->
                        val current = detailComments[post.id] ?: EventCommentsState()
                        detailComments = detailComments + (post.id to current.copy(
                            comments = listOf(created) + current.comments,
                            total = maxOf(current.total + 1, (current.comments.size + 1).toLong()),
                        ))
                    }
                    eventOverrides = eventOverrides + (post.id to post.afterComment())
                    commentSubmissionVersions = commentSubmissionVersions + (
                        post.id to ((commentSubmissionVersions[post.id] ?: 0L) + 1L)
                    )
                    interactionToast = "回复成功"
                }
                is AppResult.Failure -> interactionToast = result.message.ifBlank { "回复发布失败" }
            }
            mutatingCommentIds = mutatingCommentIds - commentId
        }
    }

    LaunchedEffect(uid) { viewModel.load(uid) }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            viewModel.toastShown()
        }
    }
    LaunchedEffect(interactionToast) {
        interactionToast?.let {
            snackbar.showSnackbar(it)
            interactionToast = null
        }
    }
    LaunchedEffect(section, state.playlists.size, state.playlistsMore, state.loadingMorePlaylists, state.playlistMoreError) {
        if (section != ProfileSection.PLAYLISTS || !state.playlistsMore ||
            state.loadingMorePlaylists || state.playlistMoreError != null
        ) return@LaunchedEffect
        snapshotFlow {
            val layout = profileListState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) viewModel.loadMorePlaylists()
        }
    }
    LaunchedEffect(
        section,
        state.events.size,
        state.eventsMore,
        state.loadingMoreEvents,
        state.eventMoreError,
        state.eventsError,
        state.eventsAutoLoadBlocked,
    ) {
        if (section != ProfileSection.EVENTS || !state.eventsMore ||
            state.loadingMoreEvents || state.eventMoreError != null ||
            state.eventsError != null || state.eventsAutoLoadBlocked
        ) return@LaunchedEffect
        snapshotFlow {
            val layout = profileListState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) viewModel.loadMoreEventsAutomatically()
        }
    }

    if (state.loading) {
        if (state.nickname.isBlank()) {
            Box(Modifier.fillMaxSize()) {
                LoadingView()
                IconButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                if (state.isSelf) {
                    IconButton(
                        onClick = { navController.navigate(Routes.SETTINGS) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            }
            return
        }
    }

    if (state.error != null && state.nickname.isBlank()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.height(40.dp))
                Text("个人资料读取失败", style = MaterialTheme.typography.titleLarge)
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.load(state.userId) }) { Text("重试") }
            }
            if (state.isSelf) {
                IconButton(
                    onClick = { navController.navigate(Routes.SETTINGS) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = profileListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            } }
            item {
                Box(Modifier.fillMaxWidth().height(64.dp)) {
                    if (state.backgroundUrl.isNotBlank()) {
                        AsyncImage(
                            model = state.backgroundUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.62f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                                ),
                            ),
                        ),
                    )
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    if (state.isSelf) {
                        IconButton(
                            onClick = { navController.navigate(Routes.SETTINGS) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 12.dp),
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.padding(start = 20.dp, top = 58.dp, end = 20.dp, bottom = 16.dp)) {
                            Text(
                                state.nickname.ifBlank { "网易云音乐用户" },
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val profileMeta = listOfNotNull(
                                state.level.takeIf { it > 0 }?.let { "Lv.$it" },
                                state.gender.takeIf { it == 1 || it == 2 }?.let { if (it == 1) "男" else "女" },
                            )
                            if (profileMeta.isNotEmpty()) Text(
                                profileMeta.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )

                            val signature = profileSignaturePresentation(state.signature, signatureExpanded)
                            if (signature.text.isNotBlank()) {
                                Text(
                                    signature.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 14.dp),
                                )
                                if (signature.canExpand) TextButton(
                                    onClick = { signatureExpanded = !signature.expanded },
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text(if (signature.expanded) "收起简介" else "展开简介")
                                }
                            }
                            val facts = profileFactRows(
                                level = state.level,
                                listenSongs = state.listenSongs,
                                eventCount = state.eventCount,
                                gender = state.gender,
                                birthday = state.birthday,
                                playlistCount = state.playlistCount,
                            )
                            if (facts.isNotEmpty()) {
                                Text(
                                    facts.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                profileHeaderStats(
                                    follows = state.follows,
                                    followeds = state.followeds,
                                    level = state.level,
                                    listenSongs = state.listenSongs,
                                ).forEach { stat ->
                                    val onStatClick = when (stat.action) {
                                        ProfileStatAction.FOLLOWS -> { { navController.navigate(Routes.follows(uid)) } }
                                        ProfileStatAction.FOLLOWEDS -> { { navController.navigate(Routes.follows(uid, followers = true)) } }
                                        null -> null
                                    }
                                    val value = when (stat.action) {
                                        ProfileStatAction.FOLLOWS -> compactProfileCount(state.follows)
                                        ProfileStatAction.FOLLOWEDS -> compactProfileCount(state.followeds)
                                        null -> stat.value
                                    }
                                    ProfileStat(stat.label, value, onStatClick)
                                }
                            }
                            if (!state.isSelf) {
                                Spacer(Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    FilledTonalButton(
                                        onClick = viewModel::toggleFollow,
                                        enabled = !state.followInFlight,
                                        modifier = Modifier.weight(1f),
                                    ) { Text(if (state.followed) "已关注" else "关注") }
                                    if (BuildConfig.FEATURE_MSG) FilledTonalButton(
                                        onClick = { navController.navigate(Routes.msgs(uid)) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.MailOutline, contentDescription = null)
                                        Text(" 私信")
                                    }
                                }
                            }
                        }
                    }
                    AsyncImage(
                        model = state.avatar,
                        contentDescription = "${state.nickname}的头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(start = 20.dp)
                            .size(80.dp)
                            .align(Alignment.TopStart)
                            .clip(CircleShape),
                    )
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    ProfileSectionTabs(selected = section, onSelect = { section = it })
                    Spacer(Modifier.height(10.dp))
                    when (section) {
                        ProfileSection.HOME -> {
                            Text("${if (state.isSelf) "我" else "TA"} 的音乐主页", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${compactProfileCount(state.eventCount)} 条动态 · ${state.playlistCount.takeIf { it > 0 } ?: state.playlists.size} 个公开歌单",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                        ProfileSection.EVENTS -> Text(
                            if (state.isSelf) "我的动态" else "TA 的动态",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        ProfileSection.PLAYLISTS -> Text(
                            if (state.isSelf) "我的公开歌单" else "TA 的公开歌单",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            if (section == ProfileSection.EVENTS) {
                state.eventsError?.let { message -> item {
                    TextButton(onClick = { viewModel.load(uid) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${message.ifBlank { "动态读取失败" }} · 点击重试")
                    }
                } }
                items(state.events, key = { "profile-event-${it.id}" }) { event ->
                    val visible = visibleEvent(event)
                    ProfileEventCard(visible, onOpenDetail = { selectedEvent = visible })
                }
                if (state.events.isEmpty() && !state.loading && state.eventsError == null) item {
                    Text(
                        "暂无公开动态",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                    )
                }
                if (state.loadingMoreEvents) item {
                    Text("正在加载更多动态…", modifier = Modifier.fillMaxWidth().padding(20.dp))
                }
                if (state.eventMoreError != null) item {
                    TextButton(
                        onClick = viewModel::retryMoreEvents,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${state.eventMoreError} · 点击重试") }
                }
                if (state.eventsAutoLoadBlocked) item {
                    TextButton(
                        onClick = viewModel::loadMoreEvents,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("继续加载更多动态") }
                }
            } else {
                if (section == ProfileSection.HOME && state.eventsError != null) item {
                    TextButton(onClick = { viewModel.load(uid) }, modifier = Modifier.fillMaxWidth()) {
                        Text("最近动态读取失败 · 点击重试")
                    }
                }
                if (section == ProfileSection.HOME && state.events.isNotEmpty()) {
                    item {
                        Text(
                            "最近动态",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    items(state.events.take(2), key = { "profile-home-event-${it.id}" }) { event ->
                        val visible = visibleEvent(event)
                        ProfileEventCard(visible, onOpenDetail = { selectedEvent = visible })
                    }
                    if (state.events.size > 2) item {
                        TextButton(
                            onClick = { section = ProfileSection.EVENTS },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("查看全部动态") }
                    }
                }
                val visiblePlaylists = if (section == ProfileSection.HOME) state.playlists.take(2) else state.playlists
                if (section == ProfileSection.HOME && visiblePlaylists.isNotEmpty()) item {
                    Text(
                        "公开歌单",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                items(visiblePlaylists, key = { it.id }) { playlist ->
                    ProfilePlaylistCard(
                        playlist = playlist,
                        onOpen = { navController.navigate(Routes.playlist(playlist.id)) },
                        onPlay = {
                            when (profilePlaylistAction(playlist)) {
                                ProfilePlaylistAction.PLAY_LOADED_TRACKS -> player.playSongs(navController, playlist.tracks, 0)
                                ProfilePlaylistAction.OPEN_DETAIL -> navController.navigate(Routes.playlist(playlist.id))
                            }
                        },
                    )
                }
            }
            if (section == ProfileSection.HOME && state.playlists.size > 2) item {
                TextButton(
                    onClick = { section = ProfileSection.PLAYLISTS },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Text("查看全部 ${state.playlistCount.takeIf { it > 0 } ?: state.playlists.size} 个歌单") }
            }
            if (section != ProfileSection.EVENTS && state.playlists.isEmpty() && !state.loading && state.error == null) item {
                Text(
                    "暂无公开歌单",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                )
            }
            if (section == ProfileSection.PLAYLISTS && state.loadingMorePlaylists) item {
                Text("正在加载更多歌单…", modifier = Modifier.fillMaxWidth().padding(20.dp))
            }
            if (section == ProfileSection.PLAYLISTS && state.playlistMoreError != null) item {
                TextButton(
                    onClick = viewModel::retryMorePlaylists,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("${state.playlistMoreError} · 点击重试") }
            }
        }
        selectedEvent?.let { event ->
            val visible = visibleEvent(event)
            EventPostDetail(
                post = visible,
                followed = visible.followed,
                showFollow = false,
                followInFlight = false,
                onDismiss = { selectedEvent = null },
                onToggleFollow = {},
                onOpenProfile = {
                    if (visible.userId > 0L) {
                        selectedEvent = null
                        navController.navigate(Routes.user(visible.userId))
                    }
                },
                onOpenMessage = if (BuildConfig.FEATURE_MSG && visible.userId > 0L) {
                    {
                        selectedEvent = null
                        navController.navigate(Routes.msgs(visible.userId))
                    }
                } else null,
                onOpenComments = { loadEventComments(visible) },
                onToggleLike = { toggleEventLike(visible) },
                liking = visible.id in likingEventIds,
                onLoadMoreComments = {
                    detailComments[visible.id]?.let { comments ->
                        loadEventComments(visible, comments.nextOffset)
                    }
                },
                commentState = detailComments[visible.id],
                commentSubmitting = visible.id in postingCommentIds,
                commentSubmissionVersion = commentSubmissionVersions[visible.id] ?: 0L,
                onSubmitComment = { postEventComment(visible, it) },
                onToggleCommentLike = { comment -> toggleEventCommentLike(visible, comment) },
                commentMutationInFlight = { comment -> comment.commentId in mutatingCommentIds },
                onReplyToComment = { comment, content -> replyToEventComment(visible, comment, content) },
                onOpenUser = { commentUserId ->
                    if (commentUserId > 0L) {
                        selectedEvent = null
                        navController.navigate(Routes.user(commentUserId))
                    }
                },
                onShare = { shareEvent(visible) },
            )
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}
