package com.litemusic.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MsgRepositoryTest {
    @Test fun jsonPayloadUsesItsMessageTextAndPlainTextStaysUntouched() {
        assertEquals("真实文本", displayPrivateMessage("{\"msg\":\"真实文本\",\"type\":\"text\"}"))
        assertEquals("普通文本", displayPrivateMessage("普通文本"))
    }
}
