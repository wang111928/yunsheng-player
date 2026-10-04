package com.litemusic.app.feature.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.litemusic.design.components.NmlButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.litemusic.design.components.LoadingView
import com.litemusic.design.components.NmlTopBar
import androidx.navigation.NavController
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import org.koin.androidx.compose.koinViewModel

@Composable
fun SigninScreen(navController: NavController, viewModel: SigninViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    if (state.loading) {
        LoadingView()
        return
    }

    Column(Modifier.fillMaxSize()) {
        NmlTopBar("签到", onBack = { navController.popBackStack() })
        SnackbarHost(snackbar, Modifier.align(Alignment.CenterHorizontally))
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            when {
                                state.state.todaySigned -> "今日已签到 ✓"
                                !state.state.remoteStatusAvailable -> "签到状态暂未从网易云读取"
                                else -> "每日签到"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "云贝 " + state.state.yunbei,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            "移动端 + PC 端双签，双倍经验",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "本机历史：连续 " + state.state.streak + " 天 · 累计 " + state.state.total + " 天",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "“今日已签到”只以网易云任务状态为准；本机历史不参与判断",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Button(
                            onClick = { viewModel.signin() },
                            enabled = !state.state.todaySigned && !state.signing,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        ) {
                            Text(if (state.signing) "签到中…" else if (state.state.todaySigned) "已签到" else "立即签到")
                        }
                    }
                }
            }
            item {
                Text("云贝任务（手动完成）", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp))
            }
            items(state.tasks, key = { it.userTaskId }) { task ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(task.name, style = MaterialTheme.typography.bodyLarge)
                        Text("奖励 " + task.yunbei + " 云贝", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { viewModel.finishTask(task) }, enabled = !task.done) {
                        Text(if (task.done) "已完成" else "去完成")
                    }
                }
            }
        }
    }
}
