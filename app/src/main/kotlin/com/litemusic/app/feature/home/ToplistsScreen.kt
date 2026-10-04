package com.litemusic.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.litemusic.app.ui.Routes
import com.litemusic.design.components.CoverCard
import com.litemusic.design.components.EmptyView
import com.litemusic.design.components.ErrorView
import com.litemusic.design.components.LoadingView
import com.litemusic.design.components.NmlCard
import com.litemusic.design.components.NmlTopBar
import com.litemusic.design.components.nmlPressable
import coil3.compose.AsyncImage
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import org.koin.androidx.compose.koinViewModel

/**
 * All playlists returned by the first-party chart endpoint.  The home shortcut deliberately
 * opens this chooser instead of selecting the first row, which happened to be the rising chart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToplistsScreen(
    navController: NavController,
    viewModel: ToplistsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val charts = state.charts

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            NmlTopBar(title = "排行榜", onBack = navController::popBackStack)
        },
    ) { padding ->
        when {
            state.loading && charts.isEmpty() -> LoadingView(Modifier.padding(padding))
            state.error != null && charts.isEmpty() -> ErrorView(
                message = state.error ?: "排行榜加载失败",
                onRetry = { viewModel.load(forceRefresh = true) },
                modifier = Modifier.padding(padding),
            )
            charts.isEmpty() -> EmptyView(
                "暂无可用排行榜",
                modifier = Modifier.padding(padding),
            )
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.load(forceRefresh = true) },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                items(charts, key = { it.id }) { chart ->
                    NmlCard(Modifier.clip(RoundedCornerShape(20.dp)).nmlPressable(onClick = {
                        navController.navigate(Routes.playlist(chart.id))
                    })) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            AsyncImage(chart.coverThumb, null, Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(chart.name, style = MaterialTheme.typography.titleMedium)
                                val preview = chart.tracks.filter { it.name.isNotBlank() }.take(3)
                                if (preview.isNotEmpty()) {
                                    preview.forEachIndexed { index, song ->
                                        Text("${index + 1}  ${song.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                } else if (chart.description.isNotBlank()) {
                                    Text(chart.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Text(chart.updateFrequency.ifBlank { "${chart.trackCount} 首" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
