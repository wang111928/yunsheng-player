package com.litemusic.design.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive

/**
 * 高仿网易云黑胶唱片（PonyMusic 优点）：
 * 旋转唱片 + 中央封面 + 唱针。
 * @param spinning 播放中旋转，暂停时停止（唱针抬起由 UI 层控制）
 */
@Composable
fun VinylDisc(
    coverUrl: String?,
    spinning: Boolean,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        while (isActive) {
            val remaining = 360f - rotation.value
            val duration = (12_000f * remaining / 360f).roundToInt().coerceAtLeast(1)
            rotation.animateTo(360f, animationSpec = tween(durationMillis = duration, easing = LinearEasing))
            rotation.snapTo(0f)
        }
    }

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        // 唱片
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value }
                .clip(CircleShape)
                .background(Color(0xFF111111)),
            contentAlignment = Alignment.Center,
        ) {
            // 唱片纹路
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = if (accent == null) {
                                listOf(Color(0xFF0A0A0A), Color(0xFF1E1E1E), Color(0xFF000000))
                            } else {
                                listOf(
                                    accent.copy(alpha = 0.92f),
                                    Color(0xFF15171C),
                                    accent.copy(alpha = 0.36f),
                                    Color(0xFF07080A),
                                )
                            },
                        )
                    )
            )
            // 封面
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize(0.5f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            // 中心孔
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .background(Color(0xFF2A2A2A), CircleShape)
                    .fillMaxSize(0.04f),
            )
        }
    }
}

/** 唱针 */
@Composable
fun Tonearm(
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val rotation = animateFloatAsState(
        targetValue = if (playing) 2f else -16f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tonearmPosition",
    )
    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = rotation.value }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(Color(0xFF8B8B8B)),
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(Color(0xFF4A4A4A)),
        )
    }
}
