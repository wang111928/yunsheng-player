package com.litemusic.shared.api

import com.litemusic.shared.util.AppResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EventEndpointTest {
    @Test
    fun `following uses the official v4 feed and recommended uses v2`() {
        assertEquals("/api/v4/event/get", eventPath(recommended = false))
        assertEquals("/api/v2/event/get", eventPath(recommended = true))
    }

    @Test
    fun `user events use the official public route and time cursor`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/event/get/42", request.url.encodedPath)
            assertEquals("1234", request.url.parameters["time"])
            assertEquals("20", request.url.parameters["limit"])
            assertEquals("true", request.url.parameters["getcounts"])
            respond("""{"code":200,"events":[],"lasttime":5678,"more":false}""", HttpStatusCode.OK)
        }
        val api = NMApi(ApiClient(engine, baseUrl = "https://example.test"))

        val result = api.userEvents(userId = 42L, time = 1234L, limit = 20)

        val response = assertIs<AppResult.Success<EventResponse>>(result).data
        assertEquals(5678L, response.nextLastTime)
    }
}
