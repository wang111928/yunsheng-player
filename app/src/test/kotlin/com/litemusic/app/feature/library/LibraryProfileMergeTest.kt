package com.litemusic.app.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryProfileMergeTest {
    @Test
    fun partialProfileResponse_doesNotBlankVerifiedValues() {
        assertEquals("已验证签名", nonBlankOrCurrent("", "已验证签名"))
        assertEquals("已验证会员", nonBlankOrCurrent(null, "已验证会员"))
    }

    @Test
    fun nonBlankProfileResponse_replacesTheCurrentValue() {
        assertEquals("远端签名", nonBlankOrCurrent("远端签名", "旧签名"))
    }
}
