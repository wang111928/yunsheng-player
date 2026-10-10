package com.litemusic.app.feature.playlist.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.litemusic.app.ui.Routes
import com.litemusic.design.components.NmlButton
import com.litemusic.design.components.NmlIconButton
import com.litemusic.design.components.nmlPressable
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

private val importTabs = listOf("链接导入", "文字导入", "图片导入", "本地导入")

@Composable
fun PlaylistImportScreen(
    navController: NavController,
    viewModel: PlaylistImportViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val pendingText by PlaylistImportRequestStore.pendingText.collectAsState()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var destinationMenu by remember { mutableStateOf(false) }
    var readingFile by remember { mutableStateOf(false) }
    var imageTextReady by remember { mutableStateOf(false) }
    var showImageTextEditor by remember { mutableStateOf(false) }
    var showAllMatches by remember { mutableStateOf(false) }
    var readJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = state.importing) { viewModel.cancelImport() }
    LaunchedEffect(state.result) {
        if (state.result?.startsWith("已恢复") == true) {
            tab = when {
                state.queries.any { it.fromScreenshot } -> 2
                extractedImportUrl(state.input) != null || officialPlaylistId(state.input) != null -> 0
                else -> 1
            }
            imageTextReady = tab == 2 && state.queries.isNotEmpty()
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        if (readingFile || state.importing) return@rememberLauncherForActivityResult
        viewModel.cancelPreview()
        imageTextReady = false
        showImageTextEditor = false
        showAllMatches = false
        readingFile = true
        readJob = scope.launch {
            try {
            val content = withContext(Dispatchers.IO) { readBoundedImportFile(context, uri) }
            if (content == null) {
                viewModel.reportError("请选择 TXT、CSV、JSON、M3U 或 M3U8（UTF-8 编码，且不超过 1 MiB）")
            } else {
                viewModel.setInput(content)
                viewModel.loadText(content)
            }
            } finally { readingFile = false }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty() || readingFile || state.importing) return@rememberLauncherForActivityResult
        viewModel.cancelPreview()
        viewModel.setInput("")
        imageTextReady = false
        showImageTextEditor = false
        showAllMatches = false
        readingFile = true
        readJob = scope.launch {
            try {
            when (val result = PlaylistScreenshotReader(context).read(uris)) {
                is AppResult.Success -> {
                    // Keep image selection, correction and matching in the image tab.
                    // This only starts the read-only preview; no playlist is written here.
                    viewModel.setInput(result.data)
                    imageTextReady = true
                    viewModel.loadText(sourceLabel = "图片导入")
                }
                is AppResult.Failure -> viewModel.reportError(result.message)
            }
            } finally { readingFile = false }
        }
    }

    LaunchedEffect(pendingText) {
        PlaylistImportRequestStore.consume()?.let { shared ->
            readJob?.cancel()
            imageTextReady = false
            showImageTextEditor = false
            showAllMatches = false
            viewModel.setInput(shared)
            tab = if (officialPlaylistId(shared) != null || extractedImportUrl(shared) != null) 0 else 1
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            NmlIconButton(onClick = {
                readJob?.cancel()
                viewModel.cancelPreview()
                if (state.importing) viewModel.cancelImport() else navController.popBackStack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Text("歌单导入", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            importTabs.forEachIndexed { index, label ->
                val active = tab == index
                Text(
                    label,
                    modifier = Modifier.weight(1f).nmlPressable(onClick = {
                        if (readingFile || state.importing) return@nmlPressable
                        viewModel.cancelPreview()
                        if (tab != index) {
                            showImageTextEditor = false
                            showAllMatches = false
                        }
                        tab = index
                    }).padding(vertical = 14.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = if (active) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                when (tab) {
                    0 -> LinkImportPane(state.input, viewModel::setInput, viewModel::loadLink, !readingFile && !state.importing)
                    1 -> TextImportPane(state.input, viewModel::setInput, { viewModel.loadText() }, !readingFile && !state.importing)
                    2 -> ImageImportPane(
                        state.input, imageTextReady, showImageTextEditor, viewModel::setInput,
                        { showImageTextEditor = !showImageTextEditor },
                        { viewModel.loadText(sourceLabel = "图片导入") },
                        !readingFile && !state.importing,
                    ) { imageLauncher.launch(arrayOf("image/*")) }
                    else -> LocalImportPane(!readingFile && !state.importing) { fileLauncher.launch(arrayOf("*/*")) }
                }
            }
            if (readingFile) item {
                Text("正在读取并识别…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                NmlButton(onClick = { readJob?.cancel() }) { Text("取消识别") }
            }
            if (state.loading) item {
                Text(if (state.totalQueries > 0) "正在匹配 ${state.processedQueries} / ${state.totalQueries} 首…" else "正在读取网易云歌曲…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (state.matches.isNotEmpty()) {
                item {
                    ImportPreviewHeader(state.matches, state.selectedSongIds, state.sourceTotalCount, state.sourceReadCompleteness)
                    val missing = (minOf(state.sourceTotalCount, MAX_IMPORT_SONGS) - state.matches.size).coerceAtLeast(0)
                    if (missing > 0) Text(
                        "有 $missing 首歌曲详情暂未读取到。可重试，或仅导入当前已读取的歌曲。",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.overflowCount > 0) item {
                    Text("为保证稳定，本次仅预览前 $MAX_IMPORT_SONGS 首；还有 ${state.overflowCount} 首未导入，请分批处理。", color = MaterialTheme.colorScheme.error)
                }
                val automatic = state.matches.filter { it.selectedByDefault }
                val exceptions = state.matches.filter { !it.selectedByDefault && !it.searchIncomplete }
                val incomplete = state.matches.count { it.searchIncomplete }
                val selectedAutomatic = automatic.count { it.song?.id in state.selectedSongIds }
                val visibleMatches = when {
                    showAllMatches -> state.matches
                    else -> state.matches.filter { !it.selectedByDefault }
                }
                if (incomplete > 0) item {
                    Text("$incomplete 首暂未完成搜索，可稍后继续匹配。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    NmlButton(onClick = viewModel::retrySearch,
                        enabled = !state.loading && !state.importing && !readingFile) { Text("继续匹配") }
                }
                if (automatic.isNotEmpty()) item {
                    Text("已自动匹配 ${automatic.size} 首，当前选中 $selectedAutomatic 首", color = MaterialTheme.colorScheme.primary)
                    if (exceptions.isNotEmpty()) {
                        Text("待处理 ${exceptions.size} 首：未匹配或匹配度不足，已在下方展开。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (showAllMatches) "收起完整匹配列表" else "查看全部 ${state.matches.size} 条匹配结果",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp).nmlPressable(onClick = { showAllMatches = !showAllMatches }).padding(vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                itemsIndexed(visibleMatches, key = { index, match -> "$index:${match.song?.id ?: 0L}:${match.query.displayName}" }) { _, match ->
                    ImportMatchRow(match, match.song?.id in state.selectedSongIds, !state.importing && !readingFile, viewModel::toggleSong)
                }
                item {
                    Text("导入位置", style = MaterialTheme.typography.titleSmall)
                    Box {
                        Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable(enabled = !state.importing && !readingFile) { destinationMenu = true }) {
                            Column(Modifier.padding(14.dp)) {
                                val selected = state.destinationPlaylists.firstOrNull { it.id == state.selectedDestinationId }
                                Text(selected?.name ?: state.selectedDestinationId?.let { "已选歌单（$it）" } ?: "新建歌单：${state.newPlaylistName}")
                                Text("点击选择已有歌单，或保留新建", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        androidx.compose.material3.DropdownMenu(expanded = destinationMenu, onDismissRequest = { destinationMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("新建歌单") }, onClick = {
                                destinationMenu = false; viewModel.setDestination(null)
                            })
                            state.destinationPlaylists.forEach { playlist ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(playlist.name) }, onClick = {
                                    destinationMenu = false; viewModel.setDestination(playlist.id)
                                })
                            }
                        }
                    }
                    TextButton(onClick = viewModel::loadDestinations, enabled = !state.loadingDestinations && !state.importing) {
                        Text(if (state.loadingDestinations) "正在读取歌单…" else "刷新已有歌单")
                    }
                    state.destinationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (state.selectedDestinationId == null) {
                        OutlinedTextField(
                            value = state.newPlaylistName,
                            onValueChange = viewModel::setNewPlaylistName,
                            label = { Text("新歌单名称") },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            singleLine = true,
                            enabled = !state.importing && !readingFile,
                            colors = importFieldColors(),
                        )
                    }
                }
                item {
                    if (state.importing) {
                        NmlButton(onClick = viewModel::cancelImport, enabled = !state.creatingDestination, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.creatingDestination) "正在创建目标歌单…" else "取消导入")
                        }
                    } else {
                        NmlButton(
                            onClick = { viewModel.importSelected { id -> navController.navigate(Routes.playlist(id)) { popUpTo(Routes.LIBRARY) } } },
                            enabled = state.selectedSongIds.isNotEmpty() && !state.loading && !readingFile,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("确认导入") }
                    }
                }
            }
            state.result?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
        }
    }
}

@Composable private fun LinkImportPane(value: String, onChange: (String) -> Unit, onStart: () -> Unit, enabled: Boolean) {
    Text("链接导入", style = MaterialTheme.typography.titleMedium)
    Text("支持网易云音乐公开歌单链接或歌单 ID。其它平台仅在能实际读取公开歌曲列表时才会导入。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    OutlinedTextField(value, onChange, enabled = enabled, colors = importFieldColors(), label = { Text("粘贴歌单链接或 ID") }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), minLines = 2, maxLines = 4)
    NmlButton(onClick = onStart, enabled = enabled && value.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("读取并预览") }
}

@Composable private fun TextImportPane(value: String, onChange: (String) -> Unit, onStart: () -> Unit, enabled: Boolean) {
    Text("文字导入", style = MaterialTheme.typography.titleMedium)
    Text("每行一首，推荐“歌名 - 歌手”。导入前会展示网易云匹配结果。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    OutlinedTextField(value, onChange, enabled = enabled, colors = importFieldColors(), label = { Text("粘贴歌曲文字") }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), minLines = 6, maxLines = 10)
    NmlButton(onClick = onStart, enabled = enabled && value.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("开始匹配") }
}

@Composable private fun ImageImportPane(
    value: String,
    ready: Boolean,
    editorOpen: Boolean,
    onChange: (String) -> Unit,
    onToggleEditor: () -> Unit,
    onStart: () -> Unit,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    Text("图片导入", style = MaterialTheme.typography.titleMedium)
    Text("最多选择 8 张歌单截图。识别后会自动生成网易云匹配预览；只有确认导入时才会写入歌单。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    NmlButton(onClick = onChoose, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Icon(Icons.Default.FolderOpen, null); Text("选择歌单截图", modifier = Modifier.padding(start = 8.dp))
    }
    if (ready) {
        val candidates = remember(value) { parseImportedText(value).size }
        Text("识别结果：$candidates 条候选", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        Text("候选条数不代表已匹配或已导入。识别文字可按需编辑后重新匹配。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(
            if (editorOpen) "收起识别文字" else "编辑识别文字",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp).nmlPressable(onClick = onToggleEditor).padding(vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
        if (editorOpen) {
            OutlinedTextField(
                value, onChange, enabled = enabled, colors = importFieldColors(),
                label = { Text("识别文字") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                minLines = 6, maxLines = 10,
            )
            NmlButton(onClick = onStart, enabled = enabled && value.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("按修改后的文字重新匹配")
            }
        }
    }
}

@Composable private fun LocalImportPane(enabled: Boolean, onChoose: () -> Unit) {
    Text("本地导入", style = MaterialTheme.typography.titleMedium)
    Text("支持 JSON、CSV（title,artist）、M3U（标准“歌手 - 歌名”）和每行“歌名 - 歌手”的 TXT。文件只在本机读取，用于匹配预览。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    NmlButton(onClick = onChoose, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Icon(Icons.Default.FolderOpen, null); Text("选择歌单文件", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable private fun ImportPreviewHeader(matches: List<ImportedSongMatch>, selected: Set<Long>, sourceTotal: Int, completeness: ExternalPlaylistReadCompleteness) {
    Text("导入预览", style = MaterialTheme.typography.titleMedium)
    val readStatus = when (completeness) {
        ExternalPlaylistReadCompleteness.COMPLETE -> "已完整读取"
        ExternalPlaylistReadCompleteness.INCOMPLETE -> "读取不完整"
        ExternalPlaylistReadCompleteness.UNKNOWN -> "读取完整性未知"
    }
    Text(if (sourceTotal > 0) "来源 $sourceTotal 首 · 已读取 ${matches.size} 条 · $readStatus" else "候选 ${matches.size} 条 · $readStatus", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    val matched = matches.mapNotNull { it.song?.id?.takeIf { id -> id > 0L } }.distinct().size
    Text("成功匹配 $matched 首 · 已选 ${selected.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    val pending = matches.count { !it.selectedByDefault && !it.searchIncomplete }
    Text(
        if (pending == 0) "已确认的歌曲已自动选中。" else "已确认的歌曲已自动选中；$pending 首未能确认，暂不导入。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable private fun importFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    disabledContainerColor = MaterialTheme.colorScheme.surface,
)

@Composable private fun ImportMatchRow(match: ImportedSongMatch, selected: Boolean, enabled: Boolean, onToggle: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(match.song?.name ?: match.query.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = match.song?.artistNames?.takeIf { it.isNotBlank() } ?: match.query.artist
                Text(
                    when {
                        match.searchIncomplete -> "搜索暂未完成：${match.query.displayName}"
                        match.song == null -> "未匹配：${match.query.displayName}"
                        else -> "$detail · 匹配度 ${match.confidence}%"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (match.selectedByDefault) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            match.song?.id?.let { id ->
                Checkbox(checked = selected, enabled = enabled, onCheckedChange = { onToggle(id) })
            }
        }
    }
}
