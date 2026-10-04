package com.litemusic.shared.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivateMessageModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sessionsDecodeRealPagingAndUnreadAliasesWithoutAnId() {
        val response = json.decodeFromString<MsgSessionResponse>(
            """{"code":200,"more":true,"msgs":[{"lastMsg":"{\"msg\":\"你好\"}","lastMsgTime":99,"newMsgCount":3,"fromUser":{"userId":7},"toUser":{"userId":8}}]}""",
        )

        assertTrue(response.hasMore)
        assertEquals(3, response.msgs.single().unread)
        assertEquals(0L, response.msgs.single().id)
    }

    @Test
    fun historyDecodesMoreAndKeepsIdlessRowsUsableByTime() {
        val response = json.decodeFromString<MsgHistoryResponse>(
            """{"code":200,"more":true,"msgs":[{"msg":"{\"msg\":\"早安\"}","time":1234,"fromUser":{"userId":7},"toUser":{"userId":8}}]}""",
        )

        assertTrue(response.hasMore)
        assertEquals(0L, response.msgs.single().msgId)
        assertEquals(1234L, response.msgs.single().time)
    }
}
