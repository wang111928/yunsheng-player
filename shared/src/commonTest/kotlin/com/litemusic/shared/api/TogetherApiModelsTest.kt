package com.litemusic.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TogetherApiModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun decodesActiveRoomMembersAndNullableOptionalFields() {
        val raw = """
            {
              "code": 200,
              "data": {
                "inRoom": true,
                "roomInfo": {
                  "creatorId": 1710978049,
                  "roomId": "room-123",
                  "roomUsers": [
                    {"userId": 1710978049, "nickname": "房主", "avatarUrl": null},
                    {"userId": 13781146236, "nickname": "听众", "avatarUrl": "https://example/avatar.jpg"}
                  ],
                  "roomType": "FRIEND"
                },
                "anotherDeviceInfo": null,
                "anotherFollowStatus": true,
                "status": "CONNECTED"
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<TogetherStatusResponse>(raw)
        val data = assertNotNull(response.data)
        val room = assertNotNull(data.roomInfo)

        assertEquals(200, response.code)
        assertEquals(true, data.inRoom)
        assertEquals("room-123", room.roomId)
        assertEquals(2, room.roomUsers.size)
        assertEquals("房主", room.roomUsers.first().nickname)
        assertNull(room.roomUsers.first().avatarUrl)
        assertEquals(true, data.anotherFollowStatus)
    }

    @Test
    fun missingDataIsSafe() {
        val response = json.decodeFromString<TogetherStatusResponse>("{\"code\": 200, \"data\": null}")

        assertEquals(200, response.code)
        assertNull(response.data)
    }
}
