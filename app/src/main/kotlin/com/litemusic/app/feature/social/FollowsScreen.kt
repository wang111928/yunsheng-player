package com.litemusic.app.feature.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.litemusic.design.components.NmlButton as FilledTonalButton
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.litemusic.app.ui.Routes
import com.litemusic.app.ui.NmlSegmentedTabs
import com.litemusic.design.components.NmlTopBar
import com.litemusic.design.components.nmlPressable
import com.litemusic.design.components.EmptyView
import com.litemusic.design.components.ErrorView
import com.litemusic.design.components.LoadingView
import com.litemusic.shared.model.Profile
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

/** Route values remain numeric so navigation does not depend on ViewModel implementation names. */
internal fun followsInitialTab(tab: Int): SocialViewModel.Tab =
    if (tab == 1) SocialViewModel.Tab.FOLLOWEDS else SocialViewModel.Tab.FOLLOWS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowsScreen(
    navController: NavController,
    uid: Long,
    initialTab: SocialViewModel.Tab = SocialViewModel.Tab.FOLLOWS,
    viewModel: SocialViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val listState = androidx.compose.runtime.key(uid, state.tab) { rememberLazyListState() }

    LaunchedEffect(uid, initialTab) { viewModel.load(uid, tab = initialTab) }
    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it); viewModel.toastShown() }
    }
    LaunchedEffect(listState, state.hasMore, state.loading, state.refreshing, state.loadingMore, state.users.size, state.loadMoreError) {
        if (!state.hasMore || state.loading || state.refreshing || state.loadingMore || state.loadMoreError != null) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= listState.layoutInfo.totalItemsCount - 4) viewModel.loadMore()
            }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        NmlTopBar("关注 / 粉丝", onBack = { navController.popBackStack() })
        NmlSegmentedTabs(
            labels = listOf("关注", "粉丝"),
            selectedIndex = state.tab.ordinal,
            onSelect = { viewModel.setTab(SocialViewModel.Tab.entries[it]) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.load(uid, forceRefresh = true) },
            modifier = Modifier.weight(1f),
        ) {
            when {
                state.loading -> LoadingView()
                state.error != null && state.users.isEmpty() -> ErrorView(state.error!!)
                state.users.isEmpty() -> EmptyView("暂无用户")
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.users, key = { it.userId }) { user ->
                        UserRow(
                            user = user,
                            followed = state.followedMap[user.userId] ?: false,
                            viewModel = viewModel,
                            onClick = { navController.navigate(Routes.user(user.userId)) },
                        )
                    }
                    if (state.loadingMore) item(key = "loading-more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                    state.loadMoreError?.let { message -> item(key = "load-more-error") {
                        TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) {
                            Text(message + "，轻触重试")
                        }
                    } }
                }
            }
        }
    }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun UserRow(
    user: Profile,
    followed: Boolean,
    viewModel: SocialViewModel,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).nmlPressable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = user.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(user.nickname, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(user.signature.ifBlank { "这个人很懒" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(onClick = { viewModel.toggleFollow(user) },
            containerColor = if (followed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
            contentColor = if (followed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text(if (followed) "已关注" else "关注")
        }
    }
}
