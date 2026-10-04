package com.litemusic.app.feature.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.litemusic.app.feature.home.HomeViewModel
import com.litemusic.app.feature.home.hasMoreCatalogRows
import com.litemusic.app.ui.Routes
import com.litemusic.design.components.CoverCard
import com.litemusic.design.components.EmptyView
import com.litemusic.design.components.ErrorView
import com.litemusic.design.components.LoadingView
import com.litemusic.design.components.NmlSearchEntryBar
import com.litemusic.design.components.SectionHeader
import com.litemusic.design.components.NmlCard
import com.litemusic.design.components.nmlPressable
import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private const val CATALOG_PAGE_SIZE = 30

internal data class PlaylistCatalogState(
    val rows: List<Playlist> = emptyList(),
    val offset: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

/**
 * The grid's end observer may emit several times before a recomposition marks
 * [PlaylistCatalogState.loadingMore]. Keep a synchronous latch so one reach
 * of the end maps to one network page.
 */
internal fun canRequestPlaylistCatalogMore(
    catalog: PlaylistCatalogState,
    requestPending: Boolean,
): Boolean = catalog.hasMore && catalog.error == null &&
    !catalog.loading && !catalog.refreshing && !catalog.loadingMore && !requestPending

/** Stops a repeated page from keeping the grid's near-end observer busy forever. */
internal fun mergePlaylistCatalogPage(
    current: PlaylistCatalogState,
    page: List<Playlist>,
    total: Int,
    serverMore: Boolean,
    append: Boolean,
): PlaylistCatalogState {
    val rows = if (append) (current.rows + page).distinctBy { it.id } else page.distinctBy { it.id }
    val added = rows.size - if (append) current.rows.size else 0
    val offset = (if (append) current.offset else 0) + page.size
    return PlaylistCatalogState(
        rows = rows,
        offset = offset,
        hasMore = added > 0 && hasMoreCatalogRows(
            total = total,
            offset = if (append) current.offset else 0,
            received = page.size,
            serverMore = serverMore,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistHubScreen(
    navController: NavController,
    viewModel: HomeViewModel = koinViewModel(),
    api: NMApi = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val data = state.data
    val playlists = data?.playlists.orEmpty()
    val toplists = data?.toplists.orEmpty()
    val gridState = rememberLazyGridState()
    var catalog by remember { mutableStateOf(PlaylistCatalogState()) }
    var requestId by remember { mutableIntStateOf(0) }
    var refreshId by remember { mutableIntStateOf(0) }
    var loadMorePending by remember { mutableStateOf(false) }

    fun reload() {
        loadMorePending = false
        refreshId = requestId + 1
        requestId += 1
    }
    fun loadMore() {
        if (canRequestPlaylistCatalogMore(catalog, loadMorePending)) {
            loadMorePending = true
            requestId += 1
        }
    }
    fun retryCatalog() {
        if (catalog.rows.isEmpty() || requestId == refreshId) {
            reload()
        } else {
            catalog = catalog.copy(error = null)
            requestId += 1
        }
    }

    LaunchedEffect(requestId) {
        val append = requestId > 0 && requestId != refreshId
        val previous = catalog
        val refreshing = requestId == refreshId
        if (append && (!previous.hasMore || previous.loadingMore)) return@LaunchedEffect
        catalog = previous.copy(
            loading = !append && previous.rows.isEmpty(),
            refreshing = refreshing,
            loadingMore = append,
            error = null,
        )
        when (val result = api.playlistCatalog(
            offset = if (append) previous.offset else 0,
            limit = CATALOG_PAGE_SIZE,
        )) {
            is AppResult.Success -> if (result.data.code == 200) {
                catalog = mergePlaylistCatalogPage(previous, result.data.playlists, result.data.total, result.data.more, append)
            } else catalog = previous.copy(loading = false, refreshing = false, loadingMore = false, error = "歌单广场读取失败(${result.data.code})")
            is AppResult.Failure -> catalog = previous.copy(loading = false, refreshing = false, loadingMore = false, error = result.message)
        }
        loadMorePending = false
    }

    when {
        state.loading && data == null -> LoadingView()
        state.error != null && data == null -> ErrorView(state.error ?: "歌单加载失败", onRetry = { viewModel.load(force = true) })
        data == null -> EmptyView("暂无歌单")
        else -> Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            topBar = { Text("歌单", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = state.refreshing || catalog.refreshing,
                onRefresh = { viewModel.load(force = true); reload() },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2), state = gridState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "search") { NmlSearchEntryBar(onClick = { navController.navigate(Routes.SEARCH) }) }
                    state.error?.let { message ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "home-refresh-error") {
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                        }
                    }
                    if (playlists.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "featured-title") { SectionHeader("精选歌单", actionText = "换一批", onAction = { viewModel.load(force = true) }, horizontalPadding = 0.dp) }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "featured-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(playlists.take(6), key = { it.id }) { playlist ->
                                    CoverCard(playlist, modifier = Modifier.width(132.dp), onClick = { navController.navigate(Routes.playlist(playlist.id)) })
                                }
                            }
                        }
                    }
                    if (toplists.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "ranking-title") { SectionHeader("排行榜", actionText = "全部榜单", onAction = { navController.navigate(Routes.TOPLISTS) }, horizontalPadding = 0.dp) }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "ranking-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(toplists.take(6), key = { it.id }) { playlist ->
                                    CoverCard(playlist, modifier = Modifier.width(124.dp), showSubtitle = true, onClick = { navController.navigate(Routes.playlist(playlist.id)) })
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "browse-title") { SectionHeader("浏览全部", horizontalPadding = 0.dp) }
                    items(catalog.rows, key = { "catalog-${it.id}" }) { playlist -> PlaylistBrowseCard(playlist) { navController.navigate(Routes.playlist(playlist.id)) } }
                    if (catalog.loading || catalog.loadingMore) item(span = { GridItemSpan(maxLineSpan) }, key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    catalog.error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-error") {
                        Text("$message · 点此重试", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().clickable { retryCatalog() }.padding(vertical = 12.dp))
                    } }
                    if (!catalog.loading && !catalog.hasMore && catalog.rows.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "end") {
                        Text("已浏览全部歌单", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(catalog.hasMore, catalog.loading, catalog.refreshing, catalog.loadingMore, catalog.error, catalog.rows.size) {
        if (!catalog.hasMore || catalog.loading || catalog.refreshing || catalog.loadingMore || catalog.error != null) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (last >= gridState.layoutInfo.totalItemsCount - 5) loadMore()
        }
    }
}

@Composable
private fun PlaylistBrowseCard(playlist: Playlist, onClick: () -> Unit) {
    NmlCard(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).nmlPressable(onClick)) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Box {
            AsyncImage(model = playlist.coverThumb, contentDescription = playlist.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(148.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant))
            Icon(Icons.Filled.PlayArrow, contentDescription = "打开歌单", tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)).size(32.dp))
        }
        Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        playlist.creator?.nickname?.takeIf { it.isNotBlank() }?.let { creator ->
            Text(creator, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
}
