package com.litemusic.app.ui

import androidx.annotation.DrawableRes
import com.litemusic.app.R
import com.litemusic.design.theme.NmThemeKind
import com.litemusic.design.theme.canonicalNmlThemeKind

/** App-owned drawable mapping keeps the design-system module independent of Android resources. */
@DrawableRes
internal fun artSkinDrawable(themeKind: String): Int? = when (canonicalNmlThemeKind(themeKind)) {
    NmThemeKind.STARRY_NIGHT -> R.drawable.skin_starry_night
    NmThemeKind.SUNRISE -> R.drawable.skin_sunrise
    NmThemeKind.LANDSCAPE -> R.drawable.skin_landscape
    NmThemeKind.DREAM -> R.drawable.skin_dream
    else -> null
}

