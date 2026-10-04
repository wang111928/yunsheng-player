package com.litemusic.app.feature.playlist

/** The public, app-independent link used by system share and clipboard. */
internal fun playlistPublicLink(id: Long): String = "https://music.163.com/playlist?id=$id"
