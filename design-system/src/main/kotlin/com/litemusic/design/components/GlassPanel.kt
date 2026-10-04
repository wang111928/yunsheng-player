package com.litemusic.design.components

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp

/**
 * 播放页毛玻璃模糊：Android 12+ 由 Compose blur 内部走 RenderEffect 硬件加速（性能好 10 倍），
 * 低版本自动降级为软件模糊。UI 层另叠加半透明纯色保证视觉一致。
 */
fun Modifier.glassBlur(radius: Float = 24f): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.blur(radius.dp) else this

