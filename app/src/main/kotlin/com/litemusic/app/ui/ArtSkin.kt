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

/** The startup artwork is intentionally separate from page backgrounds and player art. */
internal enum class StartupArtSkin {
    STARRY_NIGHT,
    SUNRISE,
    LANDSCAPE,
    DREAM,
    NONE,
}

internal fun startupArtSkin(themeKind: String): StartupArtSkin = when (canonicalNmlThemeKind(themeKind)) {
    NmThemeKind.STARRY_NIGHT -> StartupArtSkin.STARRY_NIGHT
    NmThemeKind.SUNRISE -> StartupArtSkin.SUNRISE
    NmThemeKind.LANDSCAPE -> StartupArtSkin.LANDSCAPE
    NmThemeKind.DREAM -> StartupArtSkin.DREAM
    else -> StartupArtSkin.NONE
}

@DrawableRes
internal fun startupSkinDrawable(themeKind: String): Int? = when (startupArtSkin(themeKind)) {
    StartupArtSkin.STARRY_NIGHT -> R.drawable.startup_starry_night
    StartupArtSkin.SUNRISE -> R.drawable.startup_sunrise
    StartupArtSkin.LANDSCAPE -> R.drawable.startup_landscape
    StartupArtSkin.DREAM -> R.drawable.startup_dream
    StartupArtSkin.NONE -> null
}

