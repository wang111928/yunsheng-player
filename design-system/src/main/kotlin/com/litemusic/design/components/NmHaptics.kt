package com.litemusic.design.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

/** 线性马达震动反馈（iQOO Neo 8 硬件专项） */
enum class NmHaptic { TICK, CONFIRM, LONG_PRESS }

internal fun hapticFeedbackCode(kind: NmHaptic, sdkInt: Int): Int = when (kind) {
    NmHaptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
    NmHaptic.CONFIRM -> if (sdkInt >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }
    NmHaptic.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
}

@Composable
fun rememberHaptic(): (NmHaptic) -> Unit {
    val currentView = rememberUpdatedState(LocalView.current)
    return remember {
        { kind ->
            // Do not ignore the user's system haptic setting.
            currentView.value.performHapticFeedback(hapticFeedbackCode(kind, Build.VERSION.SDK_INT))
        }
    }
}
