package com.litemusic.shared.api

import com.litemusic.shared.util.AppResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessageRequestTest {
    @Test
    fun historyUsesTheActualWeapiPathAndTimeCursor() {
        assertEquals("/weapi/msg/private/history", privateHistoryPath)
        assertEquals(
            mapOf("userId" to 42L, "limit" to 30, "time" to 1234L, "total" to "true"),
            privateHistoryPayload(userId = 42L, limit = 30, time = 1234L),
        )
    }

    @Test
    fun textSendUsesTheWebClientsWeapiRoute() {
        assertEquals("/weapi/msg/private/send", privateMessageSendPath)
        assertEquals(
            mapOf("type" to "text", "msg" to "你好", "userIds" to "[7,42]"),
            privateMessageSendPayload(listOf(7L, 42L), "你好"),
        )
    }

    @Test
    fun textSendActuallyPostsToTheWebRoute() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/weapi/msg/private/send", request.url.encodedPath)
            respond("""{"code":200}""", HttpStatusCode.OK)
        }
        val api = NMApi(ApiClient(engine, baseUrl = "https://example.test"))

        assertTrue(api.sendPrivateMsg(listOf(42L), "你好") is AppResult.Success)
    }
}
