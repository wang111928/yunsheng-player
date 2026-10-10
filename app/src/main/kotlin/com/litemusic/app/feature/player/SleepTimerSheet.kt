package com.litemusic.app.feature.player

import android.os.SystemClock
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.litemusic.design.components.NmlButton
import com.litemusic.design.components.nmlPressable
import com.litemusic.player.SleepTimerMode
import com.litemusic.player.SleepTimerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow

private val TimerCardShape = RoundedCornerShape(24.dp)
private val TimerWheelShape = RoundedCornerShape(22.dp)
private val TimerChipShape = RoundedCornerShape(16.dp)
private val TimerWheelRowHeight = 48.dp
private val TimerWheelHeight = 200.dp
private val TimerHourValues = (0..24).toList()
private val TimerMinuteValues = (0..59).toList()
private val TimerLockedMinuteValues = listOf(0)

@Composable
internal fun SleepTimerSheet(
    state: SleepTimerState,
    onMinutes: (Int) -> Unit,
    onCurrentTrack: () -> Unit,
    onCancel: () -> Unit,
) {
    var nowMs by remember(state.deadlineMs) { mutableStateOf(SystemClock.elapsedRealtime()) }
    var selectedHours by rememberSaveable { mutableStateOf(0) }
    var selectedMinutes by rememberSaveable { mutableStateOf(15) }
    var hourWheelMoving by remember { mutableStateOf(false) }
    var minuteWheelMoving by remember { mutableStateOf(false) }
    val timerMinutes = SleepTimerUiPolicy.minutesForWheel(selectedHours, selectedMinutes)

    LaunchedEffect(state.deadlineMs) {
        while (state.deadlineMs != null) {
            nowMs = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("睡眠定时", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("到时自动暂停，让音乐陪你入睡", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SleepTimerStatusCard(state = state, nowMs = nowMs)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(TimerCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text("设定暂停时间", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("滑动滚轮选择时长", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TimeWheel(
                    label = "小时",
                    values = TimerHourValues,
                    selectedValue = selectedHours,
                    onValueChange = { hour ->
                        selectedHours = hour
                        if (hour == 24) selectedMinutes = 0
                    },
                    onMovingChange = { hourWheelMoving = it },
                    modifier = Modifier.weight(1f),
                )
                Text(":", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                TimeWheel(
                    label = "分钟",
                    values = if (selectedHours == 24) TimerLockedMinuteValues else TimerMinuteValues,
                    selectedValue = selectedMinutes,
                    enabled = selectedHours != 24,
                    onValueChange = { minute ->
                        if (selectedHours != 24) selectedMinutes = minute
                    },
                    onMovingChange = { minuteWheelMoving = it },
                    modifier = Modifier.weight(1f),
                )
            }
            if (timerMinutes == null) {
                Text(
                    "请选择 1 分钟至 24 小时，24 小时时分钟固定为 00",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    "将在 ${selectedHours} 小时 ${selectedMinutes.toString().padStart(2, '0')} 分钟后暂停",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(15, 30, 60).forEach { minutes ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(TimerChipShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .nmlPressable(onClick = { onMinutes(minutes) })
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${minutes} 分钟", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        NmlButton(
            onClick = {
                if (!hourWheelMoving && !minuteWheelMoving) {
                    SleepTimerUiPolicy.minutesForWheel(selectedHours, selectedMinutes)?.let(onMinutes)
                }
            },
            enabled = timerMinutes != null && !hourWheelMoving && !minuteWheelMoving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.mode == SleepTimerMode.OFF) "开始定时" else "重新定时")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(TimerCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                .nmlPressable(onClick = onCurrentTrack)
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("当前歌曲结束后暂停", style = MaterialTheme.typography.titleSmall)
                Text("切换歌曲会自动取消", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("启用", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }

        if (state.mode != SleepTimerMode.OFF) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = 48.dp)
                    .clip(TimerChipShape)
                    .nmlPressable(onClick = onCancel)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("取消当前定时", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.size(1.dp))
    }
}

@Composable
private fun SleepTimerStatusCard(state: SleepTimerState, nowMs: Long) {
    val message = when (state.mode) {
        SleepTimerMode.MINUTES -> state.remainingMs(nowMs)?.let(SleepTimerUiPolicy::formatRemainingMs) ?: "00:00:00"
        SleepTimerMode.CURRENT_TRACK -> "当前歌曲结束后暂停"
        SleepTimerMode.OFF -> "尚未设置暂停时间"
    }
    val caption = when (state.mode) {
        SleepTimerMode.MINUTES -> "剩余时间"
        SleepTimerMode.CURRENT_TRACK -> "睡眠定时已启用"
        SleepTimerMode.OFF -> "轻触下方滚轮设定时长"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TimerCardShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(caption, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f))
        Text(
            message,
            style = if (state.mode == SleepTimerMode.MINUTES) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TimeWheel(
    label: String,
    values: List<Int>,
    selectedValue: Int,
    onValueChange: (Int) -> Unit,
    onMovingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val selectedIndex = values.indexOf(selectedValue).coerceAtLeast(0)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val rowHeight = TimerWheelRowHeight * fontScale
    val wheelHeight = TimerWheelHeight * fontScale
    val wheelPadding = (wheelHeight - rowHeight) / 2
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val scope = rememberCoroutineScope()
    val currentValues by rememberUpdatedState(values)
    val currentSelectedValue by rememberUpdatedState(selectedValue)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnMovingChange by rememberUpdatedState(onMovingChange)
    val flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Center)
    val centerIndex by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { item ->
                kotlin.math.abs(item.offset + item.size / 2 - center)
            }?.index ?: currentValues.indexOf(currentSelectedValue).coerceAtLeast(0)
        }
    }

    LaunchedEffect(selectedValue, enabled, values) {
        val targetIndex = values.indexOf(selectedValue).coerceAtLeast(0)
        if (!enabled) {
            listState.stopScroll(MutatePriority.PreventUserInput)
            listState.scrollToItem(targetIndex)
        } else if (!listState.isScrollInProgress && centerIndex != targetIndex) {
            listState.animateScrollToItem(targetIndex)
        }
    }
    LaunchedEffect(listState, enabled, values) {
        snapshotFlow {
            listState.isScrollInProgress to centerIndex
        }.distinctUntilChanged().collect { (moving, index) ->
            // Commit the visible selection before enabling the primary action.
            if (!moving && enabled) currentValues.getOrNull(index)?.let(currentOnValueChange)
            currentOnMovingChange(moving)
        }
    }

    fun step(delta: Int): Boolean {
        if (!enabled) return false
        val nextIndex = (selectedIndex + delta).coerceIn(values.indices)
        if (nextIndex == selectedIndex) return false
        scope.launch { listState.animateScrollToItem(nextIndex) }
        return true
    }

    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.46f)
            .semantics {
                stateDescription = "$label ${values.getOrElse(centerIndex) { selectedValue }}"
                customActions = if (enabled) listOf(
                    CustomAccessibilityAction("增加$label") { step(1) },
                    CustomAccessibilityAction("减少$label") { step(-1) },
                ) else emptyList()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(wheelHeight)
                .clip(TimerWheelShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(TimerChipShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.68f)),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = wheelPadding),
                flingBehavior = flingBehavior,
                userScrollEnabled = enabled,
            ) {
                items(values.size) { index ->
                    val isCentered = index == centerIndex
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .nmlPressable(
                                onClick = { scope.launch { listState.animateScrollToItem(index) } },
                                enabled = enabled,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            values[index].toString().padStart(2, '0'),
                            style = if (isCentered) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                            fontWeight = if (isCentered) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCentered) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.46f),
                        )
                    }
                }
            }
        }
    }
}
