package com.litemusic.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals

class SeekMathTest {
    @Test
    fun `clamps mini player progress and converts it to milliseconds`() {
        assertEquals(30_000L, seekPositionMs(0.5f, 60_000L))
        assertEquals(0L, seekPositionMs(-1f, 60_000L))
        assertEquals(60_000L, seekPositionMs(2f, 60_000L))
        assertEquals(0L, seekPositionMs(0.5f, 0L))
    }
}
