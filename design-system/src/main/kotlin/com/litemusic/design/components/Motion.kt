package com.litemusic.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal data class PressTransform(val scaleX: Float, val scaleY: Float)

internal fun nmlPressTransform(pressed: Boolean, enabled: Boolean, pressScale: Float): PressTransform =
    if (pressed && enabled) {
        PressTransform(scaleX = pressScale, scaleY = (pressScale - 0.012f).coerceAtLeast(0.95f))
    } else {
        PressTransform(1f, 1f)
    }

/**
 * Shared press feedback for tappable music surfaces.
 *
 * A quick, subtle squash confirms touch down; the release has one restrained spring rebound.
 * Animated values are read by the graphics layer, so they invalidate drawing rather than
 * recomposing the component that owns this modifier.
 */
@Composable
fun Modifier.nmlPressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    pressScale: Float = 0.975f,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val currentOnClick = rememberUpdatedState(onClick)
    val haptic = rememberHaptic()
    val transform = nmlPressTransform(pressed, enabled, pressScale)
    val animationSpec = if (pressed && enabled) {
        tween<Float>(durationMillis = 75, easing = FastOutLinearInEasing)
    } else {
        spring(dampingRatio = 0.78f, stiffness = 800f)
    }
    val scaleXState = animateFloatAsState(
        targetValue = transform.scaleX,
        animationSpec = animationSpec,
        label = "nmlPressScaleX",
    )
    val scaleYState = animateFloatAsState(
        targetValue = transform.scaleY,
        animationSpec = animationSpec,
        label = "nmlPressScaleY",
    )

    return graphicsLayer {
        scaleX = scaleXState.value
        scaleY = scaleYState.value
    }.clickable(
        enabled = enabled,
        role = Role.Button,
        interactionSource = interactionSource,
        indication = LocalIndication.current,
    ) {
        haptic(NmHaptic.TICK)
        currentOnClick.value()
    }
}

/** A 48dp icon target with the same motion, ripple, and haptic policy as other NML controls. */
@Composable
fun NmlIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(
                when {
                    !enabled -> Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    containerColor != Color.Transparent -> Modifier.background(containerColor)
                    else -> Modifier
                },
            )
            .nmlPressable(onClick = onClick, enabled = enabled),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        ) { content() }
    }
}

/** A compact, filled 48dp action button for page-level calls to action. */
@Composable
fun NmlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    shape: Shape = RoundedCornerShape(16.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .then(if (enabled) Modifier.background(containerColor) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant))
            .nmlPressable(onClick = onClick, enabled = enabled)
            .padding(contentPadding),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, content = content)
        }
    }
}

/** A consistent 56dp page header with a centered title and optional action slot. */
@Composable
fun NmlTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).nmlPressable(onBack),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (trailing == null) {
            Spacer(Modifier.width(48.dp))
        } else {
            Row(content = trailing)
        }
    }
}
