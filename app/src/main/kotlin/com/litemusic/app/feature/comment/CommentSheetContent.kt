package com.litemusic.app.feature.comment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.litemusic.design.components.NmHaptic
import com.litemusic.design.components.NmlTactileSurface
import com.litemusic.design.components.rememberHaptic
import com.litemusic.shared.model.Comment
import com.litemusic.shared.util.displayEmotes
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentSheetContent(
    target: CommentTarget,
    onClose: () -> Unit,
    viewModel: CommentViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(target) {
        viewModel.load(target)
    }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.toastShown()
        }
    }
    LaunchedEffect(target, state.hasMore, state.loadingMore, state.moreError) {
        if (!state.hasMore || state.loadingMore || state.moreError != null) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) viewModel.loadMore() }
    }

    val haptic = rememberHaptic()

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(min = 520.dp, max = 700.dp)) {
            SpacerHandle()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("评论", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.total} 条 · 分享你听到的瞬间",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "热评优先",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            LazyColumn(Modifier.weight(1f), state = listState) {
                if (state.loading) item {
                    Text("正在读取评论…", modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.error?.let { error -> item {
                    TextButton(onClick = { viewModel.load(target) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$error · 点击重试")
                    }
                } }
                if (state.hot.isNotEmpty()) {
                    item {
                        CommentSectionLabel("精彩评论")
                    }
                }
                items(state.hot, key = { "hot-${it.commentId}" }) { c -> CommentRow(c, viewModel, isScam = viewModel.isScam(c)) }
                item {
                    CommentSectionLabel("最新评论")
                }
                items(state.newest, key = { "new-${it.commentId}" }) { c -> CommentRow(c, viewModel, isScam = viewModel.isScam(c)) }
                if (state.loadingMore) item { Text("正在加载更多评论…", modifier = Modifier.padding(16.dp)) }
                state.moreError?.let { error -> item {
                    TextButton(onClick = viewModel::retryMore, modifier = Modifier.fillMaxWidth()) {
                        Text("$error · 点击重试")
                    }
                } }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                state.replyTo?.let { reply ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "回复 @${reply.user?.nickname ?: "匿名"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        TextButton(onClick = viewModel::cancelReply) { Text("取消") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = viewModel::setInput,
                        placeholder = { Text(if (state.replyTo == null) "发表评论" else "写回复") },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.60f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                        ),
                    )
                    NmlTactileSurface(
                        modifier = Modifier.padding(start = 8.dp).size(48.dp),
                        shape = CircleShape,
                        containerColor = if (state.submitting) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        onClick = {
                            if (!state.submitting) {
                                haptic(NmHaptic.CONFIRM)
                                viewModel.post()
                            }
                        },
                    ) {
                        Text(
                            if (state.submitting) "…" else "发",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (state.submitting) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpacerHandle() {
    androidx.compose.foundation.layout.Box(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(width = 42.dp, height = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
        )
    }
}

@Composable
private fun CommentSectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun CommentRow(comment: Comment, viewModel: CommentViewModel, isScam: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = comment.user?.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(28.dp).clip(CircleShape),
            )
            Text(
                comment.user?.nickname ?: "匿名",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { viewModel.like(comment) }) {
                Icon(
                    if (comment.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    null,
                    tint = if (comment.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(comment.likedCount.toString(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            displayEmotes(comment.content),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, start = 36.dp),
        )
        comment.beReplied.firstOrNull()?.let { parent ->
            Text(
                "回复 @${parent.user?.nickname ?: "匿名"}：${displayEmotes(parent.content)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp, top = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isScam) {
            Text(
                "⚠ 疑似广告",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 36.dp, top = 2.dp),
            )
        }
        TextButton(
            onClick = { viewModel.replyTo(comment) },
            modifier = Modifier.padding(start = 28.dp),
        ) { Text("回复", style = MaterialTheme.typography.labelMedium) }
    }
}
