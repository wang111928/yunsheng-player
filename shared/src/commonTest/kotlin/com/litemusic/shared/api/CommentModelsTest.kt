package com.litemusic.shared.api

import com.litemusic.shared.model.CommentResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class CommentModelsTest {
    @Test
    fun `v1 comments more field drives pagination`() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<CommentResponse>(
            """{"code":200,"comments":[],"total":44,"more":true}""",
        )

        assertTrue(response.hasMore)
    }

    @Test
    fun `v1 comments request uses offset pagination without a cursor`() {
        val params = commentPagePayload(threadId = "A_EV_2_7_8", offset = 20, limit = 20)

        assertEquals("A_EV_2_7_8", params["rid"])
        assertEquals(20, params["offset"])
        assertEquals(20, params["limit"])
        assertFalse(params.containsKey("cursor"))
    }
}
