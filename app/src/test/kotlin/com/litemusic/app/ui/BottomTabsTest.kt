package com.litemusic.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BottomTabsTest {
    @Test
    fun mvRouteCarriesTheMvIdWithoutBecomingABottomTab() {
        assertEquals("mv/42", Routes.mv(42L))
        assertFalse(bottomTabSpecs().any { it.route == Routes.MV })
    }

    @Test
    fun bottomTabsMatchTheMobileInformationArchitecture() {
        assertEquals(
            listOf("首页", "歌单", "笔记", "我的"),
            bottomTabSpecs().map { it.label },
        )
        assertEquals(
            listOf(Routes.HOME, Routes.PLAYLISTS, Routes.NOTES, Routes.LIBRARY),
            bottomTabSpecs().map { it.route },
        )
        assertFalse(bottomTabSpecs().any { it.label == "搜索" })
        assertFalse(bottomTabSpecs().any { it.label == "播放" })
    }
}
