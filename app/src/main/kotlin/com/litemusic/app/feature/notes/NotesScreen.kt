package com.litemusic.app.feature.notes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import com.litemusic.design.components.NmlButton as Button
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.litemusic.app.BuildConfig
import com.litemusic.app.ui.Routes
import coil3.compose.AsyncImage
import com.litemusic.design.components.nmlPressable
import androidx.compose.foundation.border
import com.litemusic.shared.util.displayEmotes
import com.litemusic.shared.util.commonEmoteTokens
import com.litemusic.design.theme.NmlTheme
import com.litemusic.shared.api.EventPost
import com.litemusic.shared.model.Comment
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel

@Composable
private fun SongCover(url: String?, modifier: Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal enum class NotesLayout {
    SINGLE_COLUMN,
    TWO_COLUMN,
}

internal fun notesLayoutFor(tab: NotesTab): NotesLayout = when (tab) {
    NotesTab.FOLLOWING -> NotesLayout.SINGLE_COLUMN
    NotesTab.RECOMMENDED -> NotesLayout.TWO_COLUMN
}

/** A pending reply must first be observed by the ViewModel before a false flag means failure. */
internal fun shouldReleasePendingReply(
    hasObservedInFlight: Boolean,
    replyMutationInFlight: Boolean,
): Boolean = hasObservedInFlight && !replyMutationInFlight

/** Merge a refreshed page without replacing the visible local interaction state. */
internal fun mergeEventPosts(existing: List<EventPost>, incoming: List<EventPost>): List<EventPost> {
    val merged = LinkedHashMap<Long, EventPost>(existing.size + incoming.size)
    existing.forEach { merged[it.id] = it }
    incoming.forEach { post ->
        val visible = merged[post.id]
        merged[post.id] = visible?.let {
            post.copy(
                followed = it.followed,
                liked = it.liked,
                likeCount = it.likeCount,
            )
        } ?: post
    }
    return merged.values.toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    navController: NavController,
    viewModel: NotesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var selectedPost by remember { mutableStateOf<EventPost?>(null) }
    val listState = rememberLazyStaggeredGridState()

    LaunchedEffect(state.tab) {
        listState.scrollToItem(0)
    }

    // 到达列表尾部自动翻页，保留底部按钮作为网络失败后的手动重试入口。
    LaunchedEffect(
        listState,
        state.more,
        state.loadMoreError,
        state.posts.size,
        state.loadingMore,
        state.autoLoadMoreBlocked,
    ) {
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.maxOfOrNull { it.index } ?: -1) to layout.totalItemsCount
        }.distinctUntilChanged().collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 3 && state.loadMoreError == null) {
                viewModel.loadMoreAutomatically()
            }
        }
    }

    fun openComments(post: EventPost) {
        selectedPost = post
        viewModel.loadComments(post)
    }

    fun sharePost(post: EventPost) {
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, eventShareText(post))
        context.startActivity(Intent.createChooser(sendIntent, "分享动态"))
    }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            viewModel.toastShown()
        }
    }

    Box(
        Modifier.fillMaxSize(),
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(if (state.tab == NotesTab.RECOMMENDED) 2 else 1),
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 12.dp,
            ) {
                item(key = "notes-header", span = StaggeredGridItemSpan.FullLine) {
                    NotesHeader(state.tab, viewModel::selectTab)
                }
                when {
                    state.loading -> item(key = "notes-loading", span = StaggeredGridItemSpan.FullLine) {
                        Text(
                            "正在加载云村动态…",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.error != null && state.posts.isEmpty() -> item(key = "notes-error", span = StaggeredGridItemSpan.FullLine) {
                        NotesError(state.error.orEmpty(), viewModel::refresh)
                    }
                    state.posts.isEmpty() -> item(key = "notes-empty", span = StaggeredGridItemSpan.FullLine) {
                        NotesEmpty(state.tab, viewModel::refresh)
                    }
                    else -> {
                        state.error?.let { message ->
                            item(key = "notes-inline-error", span = StaggeredGridItemSpan.FullLine) {
                                NotesInlineError(message, viewModel::refresh)
                            }
                        }
                        when (notesLayoutFor(state.tab)) {
                        NotesLayout.SINGLE_COLUMN -> items(state.posts, key = { "event-${it.id}" }) { post ->
                            val followed = state.followedAuthors[post.userId] ?: post.followed
                            EventPostCard(
                                post = post,
                                showFollow = false,
                                followed = followed,
                                followInFlight = post.userId in state.followRequestTokens,
                                onToggleFollow = { viewModel.toggleFollow(post) },
                                onOpenDetail = { selectedPost = post },
                                onOpenProfile = {
                                    if (post.userId > 0L) navController.navigate(Routes.user(post.userId))
                                },
                                onOpenMessage = if (BuildConfig.FEATURE_MSG && post.userId > 0L) {
                                    { navController.navigate(Routes.msgs(post.userId)) }
                                } else null,
                                onOpenComments = { openComments(post) },
                                onToggleLike = { viewModel.toggleLike(post) },
                                liking = post.id in state.likingPostIds,
                                onShare = { sharePost(post) },
                            )
                        }
                        NotesLayout.TWO_COLUMN -> items(state.posts, key = { "recommended-${it.id}" }) { post ->
                                val followed = state.followedAuthors[post.userId] ?: post.followed
                                RecommendedEventPostCard(
                                    post = post,
                                    modifier = Modifier.fillMaxWidth(),
                                    showFollow = post.userId > 0L && !followed,
                                    followed = followed,
                                    followInFlight = post.userId in state.followRequestTokens,
                                    onToggleFollow = { viewModel.toggleFollow(post) },
                                    onOpenDetail = { selectedPost = post },
                                    onOpenProfile = {
                                        if (post.userId > 0L) navController.navigate(Routes.user(post.userId))
                                    },
                                    onOpenComments = { openComments(post) },
                                    onToggleLike = { viewModel.toggleLike(post) },
                                    liking = post.id in state.likingPostIds,
                                    onShare = { sharePost(post) },
                                )
                        }
                        }
                    }
                }
                if (!state.loading && state.posts.isNotEmpty()) {
                    item(key = "notes-more", span = StaggeredGridItemSpan.FullLine) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (state.loadingMore) {
                                Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            } else if (!state.more) {
                                Text(
                                    if (state.tab == NotesTab.RECOMMENDED) "本轮推荐已看完" else "已加载全部关注动态",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                if (state.tab == NotesTab.RECOMMENDED) {
                                    state.loadMoreError?.let { message ->
                                        Text(
                                            message,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    OutlinedButton(onClick = viewModel::fetchMoreRecommendedAtEnd) {
                                        Text(if (state.loadMoreError == null) "获取新推荐" else "重试获取新推荐")
                                    }
                                }
                            } else if (state.loadMoreError != null) {
                                state.loadMoreError.orEmpty().let { message ->
                                    Text(
                                        message,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                OutlinedButton(
                                    onClick = viewModel::loadMore,
                                    modifier = Modifier.height(48.dp),
                                ) {
                                    Text("重试加载更多")
                                }
                            } else if (state.autoLoadMoreBlocked) {
                                OutlinedButton(
                                    onClick = viewModel::loadMore,
                                    modifier = Modifier.height(48.dp),
                                ) { Text("继续加载") }
                            } else {
                                Text(
                                    "继续上滑加载更多",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp),
        )
        selectedPost?.let { post ->
            // The comment endpoint can correct a stale feed count; render the updated visible post
            // instead of keeping the originally selected snapshot in the full-screen detail.
            val visiblePost = state.posts.firstOrNull { it.id == post.id } ?: post
            EventPostDetail(
                post = visiblePost,
                followed = state.followedAuthors[visiblePost.userId] ?: visiblePost.followed,
                showFollow = state.tab == NotesTab.RECOMMENDED && visiblePost.userId > 0L,
                followInFlight = visiblePost.userId in state.followRequestTokens,
                onDismiss = { selectedPost = null },
                onToggleFollow = { viewModel.toggleFollow(visiblePost) },
                onOpenProfile = {
                    selectedPost = null
                    if (visiblePost.userId > 0L) navController.navigate(Routes.user(visiblePost.userId))
                },
                onOpenMessage = if (BuildConfig.FEATURE_MSG && visiblePost.userId > 0L) {
                    {
                        selectedPost = null
                        navController.navigate(Routes.msgs(visiblePost.userId))
                    }
                } else null,
                onOpenComments = { openComments(visiblePost) },
                onToggleLike = { viewModel.toggleLike(visiblePost) },
                liking = visiblePost.id in state.likingPostIds,
                onLoadMoreComments = { viewModel.loadMoreComments(visiblePost) },
                commentState = state.commentsByPost[visiblePost.id],
                commentSubmitting = visiblePost.id in state.postingCommentIds,
                commentSubmissionVersion = state.commentSubmissionVersions[visiblePost.id] ?: 0L,
                onSubmitComment = { viewModel.postComment(visiblePost, it) },
                onToggleCommentLike = { comment -> viewModel.toggleCommentLike(visiblePost, comment) },
                commentMutationInFlight = { comment -> comment.commentId in state.mutatingCommentIds },
                onReplyToComment = { comment, content -> viewModel.replyToComment(visiblePost, comment, content) },
                onOpenUser = { userId ->
                    if (userId > 0L) navController.navigate(Routes.user(userId))
                },
                onShare = {
                    sharePost(post)
                },
            )
        }
    }
}

@Composable
private fun NotesHeader(
    tab: NotesTab,
    onTabSelected: (NotesTab) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("笔记", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "关注的人和云村里的新声音",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TabRow(
            selectedTabIndex = tab.ordinal,
            modifier = Modifier.padding(top = 8.dp),
            containerColor = Color.Transparent,
            divider = {},
        ) {
            NotesTab.entries.forEach { item ->
                Tab(
                    selected = item == tab,
                    onClick = { onTabSelected(item) },
                    text = { Text(if (item == NotesTab.FOLLOWING) "关注" else "推荐") },
                )
            }
        }
    }
}

@Composable
private fun EventPostCard(
    post: EventPost,
    showFollow: Boolean,
    followed: Boolean,
    followInFlight: Boolean,
    onToggleFollow: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenMessage: (() -> Unit)?,
    onOpenComments: (() -> Unit)?,
    onToggleLike: () -> Unit,
    liking: Boolean,
    onShare: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .nmlPressable(onClick = onOpenDetail, pressScale = 0.99f)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = post.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(CircleShape).nmlPressable(onClick = onOpenProfile),
            )
            Column(
                Modifier.weight(1f).padding(start = 10.dp).nmlPressable(onClick = onOpenProfile),
            ) {
                Text(post.nickname, style = MaterialTheme.typography.titleSmall)
                Text(
                    formatEventTime(post.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showFollow) {
                OutlinedButton(
                    onClick = onToggleFollow,
                    enabled = !followInFlight,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                Text(if (followed) "已关注" else "关注", style = MaterialTheme.typography.labelMedium)
            }
                }
            if (onOpenMessage != null) {
                IconButton(onClick = onOpenMessage) {
                    Icon(Icons.Default.MailOutline, contentDescription = "私信")
                }
            }
        }

        if (post.content.isNotBlank()) {
            Text(
                displayEmotes(post.content),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (post.tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                post.tags.take(3).forEach { tag ->
                    FilterChip(
                        selected = false,
                        onClick = {},
                        label = { Text("#$tag") },
                    )
                }
            }
        }

        if (post.imageUrls.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                post.imageUrls.take(3).forEach { imageUrl ->
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(94.dp).clip(RoundedCornerShape(16.dp)),
                    )
                }
            }
        }

        if (!post.songTitle.isNullOrBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongCover(post.songCoverUrl, Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(post.songTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        post.songArtist.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleLike, enabled = !liking, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (post.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "点赞",
                    tint = if (post.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Text(post.likeCount.toString(), modifier = Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(22.dp))
            IconButton(
                onClick = { onOpenComments?.invoke() },
                enabled = onOpenComments != null,
            ) {
                Icon(Icons.Default.ChatBubbleOutline, contentDescription = "评论", modifier = Modifier.size(19.dp))
            }
            Text(post.commentCount.toString(), modifier = Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "分享", modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun RecommendedEventPostCard(
    post: EventPost,
    modifier: Modifier,
    showFollow: Boolean,
    followed: Boolean,
    followInFlight: Boolean,
    onToggleFollow: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenComments: (() -> Unit)?,
    onToggleLike: () -> Unit,
    liking: Boolean,
    onShare: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .nmlPressable(onClick = onOpenDetail, pressScale = 0.99f)
            .padding(10.dp),
    ) {
        if (post.imageUrls.isNotEmpty()) {
            Box {
                AsyncImage(
                    model = post.imageUrls.first(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)),
                )
                if (post.imageUrls.size > 1) {
                    Text(
                        "${post.imageUrls.size} 张",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = post.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(30.dp).clip(CircleShape).nmlPressable(onClick = onOpenProfile),
            )
            Text(
                post.nickname,
                modifier = Modifier.weight(1f).padding(start = 7.dp).nmlPressable(onClick = onOpenProfile),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showFollow) {
                TextButton(
                    onClick = onToggleFollow,
                    enabled = !followInFlight,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(if (followed) "已关注" else "关注", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (post.content.isNotBlank()) {
            Text(
                displayEmotes(post.content),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (post.tags.isNotEmpty()) {
            Text(
                post.tags.take(2).joinToString("  ") { "#$it" },
                modifier = Modifier.padding(top = 7.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!post.songTitle.isNullOrBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongCover(post.songCoverUrl, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.padding(start = 7.dp)) {
                    Text(
                        post.songTitle.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        post.songArtist.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        EventStatsRow(post, onOpenComments, onToggleLike, liking, onShare)
    }
}

@Composable
private fun EventStatsRow(
    post: EventPost,
    onOpenComments: (() -> Unit)?,
    onToggleLike: () -> Unit,
    liking: Boolean,
    onShare: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleLike, enabled = !liking, modifier = Modifier.size(32.dp)) {
            Icon(
                if (post.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "点赞",
                tint = if (post.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(post.likeCount.toString(), modifier = Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
        IconButton(
            onClick = { onOpenComments?.invoke() },
            enabled = onOpenComments != null,
            modifier = Modifier.size(32.dp).padding(start = 6.dp),
        ) {
            Icon(Icons.Default.ChatBubbleOutline, contentDescription = "评论", modifier = Modifier.size(18.dp))
        }
        Text(post.commentCount.toString(), modifier = Modifier.padding(start = 2.dp), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Share, contentDescription = "分享", modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventPostDetail(
    post: EventPost,
    followed: Boolean,
    showFollow: Boolean,
    followInFlight: Boolean,
    onDismiss: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenMessage: (() -> Unit)?,
    onOpenComments: (() -> Unit)?,
    onToggleLike: () -> Unit,
    liking: Boolean,
    onLoadMoreComments: (() -> Unit)?,
    commentState: EventCommentsState?,
    commentSubmitting: Boolean,
    commentSubmissionVersion: Long,
    onSubmitComment: (String) -> Unit,
    onToggleCommentLike: ((Comment) -> Unit)? = null,
    commentMutationInFlight: (Comment) -> Boolean = { false },
    onReplyToComment: ((Comment, String) -> Unit)? = null,
    onOpenUser: (Long) -> Unit,
    onShare: () -> Unit,
) {
    LaunchedEffect(post.id) {
        onOpenComments?.invoke()
    }
    val detailListState = rememberLazyListState()
    LaunchedEffect(post.id, commentState?.comments?.size, commentState?.more, commentState?.loadingMore) {
        snapshotFlow {
            val layout = detailListState.layoutInfo
            // Comments are independent lazy items. Check the actual scroll extent
            // so a partial final row cannot trigger the next request prematurely.
            layout.totalItemsCount > 0 && !detailListState.canScrollForward
        }.distinctUntilChanged().collect { atBottom ->
            if (atBottom && canAutoLoadMoreComments(commentState)) {
                onLoadMoreComments?.invoke()
            }
        }
    }
    var replyTarget by remember(post.id) { mutableStateOf<Comment?>(null) }
    var pendingReply by remember(post.id) { mutableStateOf<Comment?>(null) }
    var pendingReplyWasInFlight by remember(post.id) { mutableStateOf(false) }
    val replyMutationInFlight = pendingReply?.let(commentMutationInFlight) == true
    LaunchedEffect(commentSubmissionVersion) {
        // Only the reply that was submitted may clear this composer.  Clearing
        // every selected target here made an earlier reply erase a newly chosen
        // reply draft when its request completed.
        if (pendingReply != null) {
            replyTarget = null
            pendingReply = null
        }
    }
    LaunchedEffect(pendingReply?.commentId, replyMutationInFlight) {
        // A failed mutation does not advance the submission version. Unlock the
        // same draft so it can be corrected and sent again. The first
        // composition after tapping send may still see the old false flag, so
        // only unlock after this exact request has been seen in flight.
        if (pendingReply != null && replyMutationInFlight) {
            pendingReplyWasInFlight = true
        } else if (pendingReply != null && shouldReleasePendingReply(
                pendingReplyWasInFlight,
                replyMutationInFlight,
            )
        ) {
            pendingReply = null
            pendingReplyWasInFlight = false
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
            LazyColumn(
                state = detailListState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 12.dp),
            ) {
                item(key = "detail-toolbar") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回动态")
                        }
                        Text("动态详情", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = "分享")
                        }
                    }
                }
                item(key = "detail-author") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = post.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onOpenProfile),
                )
                Column(Modifier.weight(1f).padding(start = 10.dp).clickable(onClick = onOpenProfile)) {
                    Text(post.nickname, style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatEventTime(post.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showFollow) {
                    OutlinedButton(
                        onClick = onToggleFollow,
                        enabled = !followInFlight,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text(if (followed) "已关注" else "关注", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (onOpenMessage != null) {
                    IconButton(onClick = onOpenMessage) {
                        Icon(Icons.Default.MailOutline, contentDescription = "私信")
                    }
                }
            }
                }
                if (post.content.isNotBlank()) item(key = "detail-content") {
                    Text(displayEmotes(post.content), modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyLarge)
                }
                if (post.tags.isNotEmpty()) item(key = "detail-tags") {
                Text(
                    post.tags.joinToString("  ") { "#$it" },
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                }
                post.imageUrls.forEachIndexed { index, imageUrl -> item(key = "detail-image-$index") {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                } }
                if (!post.songTitle.isNullOrBlank()) item(key = "detail-song") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SongCover(post.songCoverUrl, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(post.songTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            post.songArtist.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                }
                item(key = "detail-stats") { EventStatsRow(post, onOpenComments, onToggleLike, liking, onShare) }
                eventCommentItems(
                    post = post,
                    state = commentState,
                    onOpenUser = onOpenUser,
                    onRetry = onOpenComments,
                    onRetryMore = onLoadMoreComments,
                    onToggleCommentLike = onToggleCommentLike,
                    commentMutationInFlight = commentMutationInFlight,
                    onReply = if (onReplyToComment == null || pendingReply != null) null
                    else { comment: Comment -> replyTarget = comment },
                )
            }
            EventCommentComposer(
                onSubmit = { content ->
                    replyTarget?.let { target ->
                        onReplyToComment?.invoke(target, content)
                        pendingReply = target
                        pendingReplyWasInFlight = false
                    } ?: onSubmitComment(content)
                },
                submitting = commentSubmitting || pendingReply != null ||
                    (replyTarget?.let(commentMutationInFlight) == true),
                submissionVersion = commentSubmissionVersion,
                replyTarget = replyTarget,
                onCancelReply = { if (pendingReply == null) replyTarget = null },
            )
            }
        }
    }
}

@Composable
private fun EventCommentComposer(
    onSubmit: (String) -> Unit,
    submitting: Boolean,
    submissionVersion: Long,
    replyTarget: Comment?,
    onCancelReply: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var appliedVersion by remember { mutableStateOf(submissionVersion) }
    var emojiPanelVisible by remember(replyTarget?.commentId) { mutableStateOf(false) }
    LaunchedEffect(submissionVersion) {
        if (submissionVersion > appliedVersion) {
            text = ""
            appliedVersion = submissionVersion
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (replyTarget != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "回复 ${replyTarget.user?.nickname.orEmpty().ifBlank { "云村用户" }}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(
                    onClick = onCancelReply,
                    enabled = !submitting,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text("取消") }
            }
        }
        if (emojiPanelVisible) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(38.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(commonEmoteTokens, key = { it }) { token ->
                    Text(
                        displayEmotes(token),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = !submitting) { text += token }
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { emojiPanelVisible = !emojiPanelVisible },
                enabled = !submitting,
                modifier = Modifier.height(42.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) { Text("表情", style = MaterialTheme.typography.labelMedium) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 96.dp),
                placeholder = { Text(if (replyTarget == null) "写评论" else "写回复") },
                minLines = 1,
                maxLines = 2,
                enabled = !submitting,
                singleLine = false,
            )
            Button(
                onClick = { onSubmit(text) },
                enabled = text.isNotBlank() && !submitting,
                modifier = Modifier.padding(start = 6.dp).height(42.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) { Text(if (submitting) "发布中" else "发布") }
        }
    }
}

private fun LazyListScope.eventCommentItems(
    post: EventPost,
    state: EventCommentsState?,
    onOpenUser: (Long) -> Unit,
    onRetry: (() -> Unit)?,
    onRetryMore: (() -> Unit)?,
    onToggleCommentLike: ((Comment) -> Unit)?,
    commentMutationInFlight: (Comment) -> Boolean,
    onReply: ((Comment) -> Unit)?,
) {
    val total = displayedCommentTotal(post, state)
    item(key = "detail-comments-title") {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
        Text("评论 $total", style = MaterialTheme.typography.titleSmall)
        }
    }
        when {
            state == null || state.loading -> item(key = "detail-comments-loading") { Row(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } }
            state.error != null && state.comments.isEmpty() -> item(key = "detail-comments-error") { Column(Modifier.padding(top = 10.dp)) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (post.commentThreadId.isNotBlank() && onRetry != null) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.padding(top = 8.dp).height(48.dp),
                    ) { Text("重试加载评论") }
                }
            } }
            state.comments.isEmpty() -> item(key = "detail-comments-empty") { Text(
                "暂时还没有评论",
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            ) }
            else -> {
                items(
                    state.comments,
                    key = { comment -> "detail-comment-${comment.commentId}-${comment.time}" },
                ) { comment ->
                val userId = comment.user?.userId ?: 0L
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AsyncImage(
                        model = comment.user?.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(enabled = userId > 0L) { onOpenUser(userId) },
                    )
                    Column(Modifier.weight(1f).padding(start = 9.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                comment.user?.nickname.orEmpty().ifBlank { "云村用户" },
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = userId > 0L) { onOpenUser(userId) },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            IconButton(
                                onClick = { onToggleCommentLike?.invoke(comment) },
                                enabled = onToggleCommentLike != null && !commentMutationInFlight(comment),
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    if (comment.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "点赞 ${comment.likedCount}",
                                    modifier = Modifier.size(17.dp),
                                    tint = if (comment.liked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (comment.likedCount > 0) Text(
                                comment.likedCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            displayEmotes(comment.content),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp)
                                .clickable(enabled = onReply != null) { onReply?.invoke(comment) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        comment.beReplied.firstOrNull()?.let { replied ->
                            Text(
                                "回复 ${replied.user?.nickname.orEmpty()}：${displayEmotes(replied.content)}",
                                modifier = Modifier.padding(top = 3.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "回复",
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = onReply != null) { onReply?.invoke(comment) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                }
                state.error?.let { message ->
                    item(key = "detail-comments-more-error") { Column(Modifier.padding(top = 10.dp)) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (state.needsRefresh && onRetry != null) {
                            OutlinedButton(
                                onClick = onRetry,
                                modifier = Modifier.padding(top = 8.dp).height(48.dp),
                            ) { Text("重新加载评论") }
                        } else if (state.autoLoadBlocked && onRetryMore != null) {
                            OutlinedButton(
                                onClick = onRetryMore,
                                modifier = Modifier.padding(top = 8.dp).height(48.dp),
                            ) { Text("重试加载更多评论") }
                        }
                    }
                    }
                }
                if ((state.more && !state.autoLoadBlocked) || state.loadingMore) {
                    item(key = "detail-comments-more") { Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                "继续下滑加载更多评论",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    }
                }
            }
        }
}

@Composable
private fun NotesEmpty(tab: NotesTab, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (tab == NotesTab.FOLLOWING) "还没有关注动态" else "暂时没有推荐动态", style = MaterialTheme.typography.titleMedium)
        Text(
            "登录后可以看到真实的云村帖子",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRefresh, modifier = Modifier.padding(top = 16.dp)) { Text("重新加载") }
    }
}

@Composable
private fun NotesError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("动态加载失败", style = MaterialTheme.typography.titleMedium)
        Text(message, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("重试") }
    }
}

@Composable
private fun NotesInlineError(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.height(48.dp)) { Text("重试") }
    }
}

private fun formatEventTime(timeMs: Long): String {
    if (timeMs <= 0L) return "刚刚"
    val minutes = ((System.currentTimeMillis() - timeMs).coerceAtLeast(0L) / 60_000L)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 24 * 60 -> "${minutes / 60}小时前"
        else -> "${minutes / (24 * 60)}天前"
    }
}
