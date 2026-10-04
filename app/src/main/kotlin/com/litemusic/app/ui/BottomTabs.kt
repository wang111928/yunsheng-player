package com.litemusic.app.ui


data class BottomTabSpec(
    val route: String,
    val label: String,
)

fun bottomTabSpecs(): List<BottomTabSpec> = listOf(
    BottomTabSpec(Routes.HOME, "首页"),
    BottomTabSpec(Routes.PLAYLISTS, "歌单"),
    BottomTabSpec(Routes.NOTES, "笔记"),
    BottomTabSpec(Routes.LIBRARY, "我的"),
)
