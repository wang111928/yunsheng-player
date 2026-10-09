package com.litemusic.app.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.litemusic.design.components.NmlIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.litemusic.design.components.nmlPressable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.litemusic.app.BuildConfig
import com.litemusic.app.ui.artSkinDrawable
import com.litemusic.design.components.NmHaptic
import com.litemusic.design.components.rememberHaptic
import com.litemusic.design.theme.NmlThemeOptions
import com.litemusic.design.theme.canonicalNmlThemeKind
import com.litemusic.shared.util.Quality
import java.io.File
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var passphrase by remember { mutableStateOf("") }
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var exportMode by remember { mutableStateOf(true) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showUpdateErrorDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null && passphrase.length >= 6) {
            val tmp = File(context.cacheDir, "credential.nml")
            viewModel.exportCredential(tmp, passphrase)
            context.contentResolver.openOutputStream(uri)?.use { output -> tmp.inputStream().copyTo(output) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && passphrase.length >= 6) {
            val tmp = File(context.cacheDir, "credential_import.nml")
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            viewModel.importCredential(tmp, passphrase)
        }
    }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it); viewModel.toastShown() }
    }
    LaunchedEffect(state.update) { if (state.update != null) showUpdateDialog = true }
    LaunchedEffect(state.updateError) { if (state.updateError != null) showUpdateErrorDialog = true }

    if (showUpdateErrorDialog && state.updateError != null) {
        AlertDialog(
            onDismissRequest = { showUpdateErrorDialog = false },
            title = { Text("检查更新失败") },
            text = { Text(state.updateError!!) },
            confirmButton = { TextButton(onClick = { showUpdateErrorDialog = false; viewModel.checkForUpdate(BuildConfig.VERSION_CODE.toLong()) }) { Text("重试") } },
            dismissButton = {
                TextButton(onClick = {
                    showUpdateErrorDialog = false
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wang111928/yunsheng-player/releases/latest")))
                    }.onFailure { viewModel.showToast("无法打开浏览器，请检查是否安装浏览器后重试") }
                }) { Text("打开 GitHub 发布页") }
            },
        )
    }

    if (showUpdateDialog && state.update != null) {
        val update = state.update!!
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("发现新版本 ${update.versionName}") },
            text = {
                Column {
                    Text("安装包 ${update.asset.size?.let(::formatSize) ?: "大小以下载结果为准"}")
                    if (update.releaseNotes.isNotBlank()) Text(update.releaseNotes, modifier = Modifier.padding(top = 8.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                    if (state.downloadingUpdate) {
                        val progress = state.totalUpdateBytes?.takeIf { it > 0 }?.let { total -> "${formatSize(state.downloadedBytes)} / ${formatSize(total)}" }
                            ?: formatSize(state.downloadedBytes)
                        Text("正在下载：$progress", modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val downloaded = state.downloadedUpdate
                    if (downloaded != null) {
                        installUpdate(context, downloaded, viewModel)
                    } else {
                        viewModel.downloadUpdate()
                    }
                }, enabled = !state.downloadingUpdate) {
                    Text(if (state.downloadedUpdate != null) "安装更新" else if (state.downloadingUpdate) "下载中" else "下载更新")
                }
            },
            dismissButton = { TextButton(onClick = { showUpdateDialog = false }) { Text("稍后") } },
        )
    }

    if (showPassphraseDialog) {
        AlertDialog(
            onDismissRequest = { showPassphraseDialog = false },
            title = { Text(if (exportMode) "设置导出口令" else "输入恢复口令") },
            text = {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("口令（至少 6 位）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (passphrase.length >= 6) {
                        if (exportMode) exportLauncher.launch("netease-music-lite-credential.json")
                        else importLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                        showPassphraseDialog = false
                        passphrase = ""
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showPassphraseDialog = false }) { Text("取消") } },
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (state.loaded) {
                item { SectionLabel("音质") }
                item {
                    SettingsCard {
                        MenuLine(
                            title = "默认音质",
                            subtitle = "播放时优先使用此音质",
                            currentLabel = state.quality.label,
                            items = listOf(Quality.STANDARD, Quality.HIGH, Quality.EXHIGH, Quality.LOSSLESS).map { it.label to it },
                            current = state.quality,
                            onPick = viewModel::setQuality,
                        )
                    }
                }

                item { SectionLabel("外观") }
                item {
                    SettingsCard {
                        Text("主题颜色", style = MaterialTheme.typography.bodyLarge)
                        Text("立即预览，重启后仍会保留", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThemePicker(selected = state.theme, onSelect = viewModel::setTheme)
                        GroupDivider()
                        SwitchLine("播放页毛玻璃", "在播放页使用柔和背景效果", state.glassBlur, viewModel::setGlassBlur)
                    }
                }

                item { SectionLabel("本地音乐") }
                item {
                    SettingsCard {
                        MenuLine(
                            title = "最小时长过滤",
                            subtitle = "过滤过短的音频文件",
                            currentLabel = "${state.minLocalSec} 秒",
                            items = listOf(10, 20, 30, 60, 120).map { "$it 秒" to it },
                            current = state.minLocalSec,
                            onPick = viewModel::setMinLocalSec,
                        )
                    }
                }

                item { SectionLabel("缓存") }
                item {
                    SettingsCard {
                        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("内容缓存空间", style = MaterialTheme.typography.bodyLarge)
                                Text("包括封面和歌词，不含歌曲音频缓存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatSize(state.cacheUsed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GroupDivider()
                        ActionLine("清理内容缓存", "只清理封面和歌词；音频缓存不设上限且不会自动淘汰", "清理  ›", onClick = viewModel::clearCache)
                    }
                }

                item { SectionLabel("账号") }
                item {
                    SettingsCard {
                        ActionLine("导出登录凭证", "使用口令加密后保存到文件", "导出  ›") { exportMode = true; showPassphraseDialog = true }
                        GroupDivider()
                        ActionLine("导入登录凭证", "从先前导出的文件恢复登录", "导入  ›") { exportMode = false; showPassphraseDialog = true }
                        GroupDivider()
                        ActionLine("退出登录", "清除当前设备上的登录状态", "退出  ›", destructive = true) {
                            viewModel.logout { navController.popBackStack() }
                        }
                    }
                }

                item { SectionLabel("软件更新") }
                item {
                    SettingsCard {
                        val subtitle = when {
                            state.downloadedUpdate != null -> "新版本已下载，点击安装后仍需系统确认"
                            state.downloadingUpdate -> "正在下载更新"
                            state.checkingUpdate -> "正在检查 GitHub 最新正式发布"
                            else -> "从 GitHub 检查最新版，不会影响登录、歌单或已缓存音频"
                        }
                        val action = when {
                            state.downloadedUpdate != null -> "安装  ›"
                            state.downloadingUpdate -> "下载中"
                            state.checkingUpdate -> "检查中"
                            else -> "检查更新  ›"
                        }
                        ActionLine("自动更新", subtitle, action) {
                            when {
                                state.downloadedUpdate != null -> installUpdate(context, state.downloadedUpdate!!, viewModel)
                                state.update != null -> showUpdateDialog = true
                                else -> viewModel.checkForUpdate(BuildConfig.VERSION_CODE.toLong())
                            }
                        }
                    }
                }

                item {
                    Text(
                        "云声 ${BuildConfig.VERSION_NAME}-${BuildConfig.FLAVOR_NAME}\n仅供个人学习研究，支持正版",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = content)
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f))
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val haptic = rememberHaptic()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = { haptic(NmHaptic.TICK); onCheckedChange(it) })
    }
}

@Composable
private fun ActionLine(title: String, subtitle: String, actionLabel: String, destructive: Boolean = false, onClick: () -> Unit) {
    val actionColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .nmlPressable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (destructive) actionColor else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = actionColor)
    }
}

private fun installUpdate(context: android.content.Context, file: File, viewModel: SettingsViewModel) {
    if (!file.isFile) {
        viewModel.discardMissingUpdate()
        return
    }
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            viewModel.showToast("请允许安装未知来源应用后返回此处继续安装")
        } else {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }.onFailure { viewModel.showToast("无法打开安装器，请检查系统安装权限后重试") }
}

@Composable
private fun <T> MenuLine(title: String, subtitle: String, currentLabel: String, items: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptic = rememberHaptic()
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .nmlPressable(onClick = { open = true }).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            Text(currentLabel + "  ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label + if (value == current) "  ✓" else "") },
                        onClick = { haptic(NmHaptic.TICK); onPick(value); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePicker(selected: String, onSelect: (String) -> Unit) {
    val canonicalSelection = canonicalNmlThemeKind(selected)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)) {
        NmlThemeOptions.chunked(3).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { choice ->
                    val active = choice.id == canonicalSelection
                    Column(
                        modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .semantics { this.selected = active }
                            .nmlPressable(onClick = { onSelect(choice.id) }).padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(24.dp).clip(MaterialTheme.shapes.small)
                            .background(choice.previewColor),
                        ) {
                            artSkinDrawable(choice.id)?.let { art ->
                                Image(
                                    painter = painterResource(art),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Text(
                            choice.label + if (active) " ✓" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
            if (rowIndex != NmlThemeOptions.chunked(3).lastIndex) Box(Modifier.height(8.dp))
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> (bytes / 1024).toString() + " KB"
    else -> "$bytes B"
}
