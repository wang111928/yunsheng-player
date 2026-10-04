package com.litemusic.app.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
                                Text("缓存空间", style = MaterialTheme.typography.bodyLarge)
                                Text("包括歌曲、封面和歌词等缓存数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatSize(state.cacheUsed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GroupDivider()
                        ActionLine("清理缓存", "不会删除已下载的本地音乐", "清理  ›", onClick = viewModel::clearCache)
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

                item {
                    Text(
                        "网易云音乐 Lite ${BuildConfig.VERSION_NAME}-${BuildConfig.FLAVOR_NAME}\n仅供个人学习研究，支持正版",
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
