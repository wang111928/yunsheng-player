package com.litemusic.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class PlaylistManipulateResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun acceptsSuccessfulManipulationWhenTrackIdsIsEncodedJsonText() {
        val response = json.decodeFromString<PlaylistManipulateResponse>(
            """{"code":200,"trackIds":"[{\"id\":123}]","message":"ok"}""",
        )

        assertEquals(200, response.code)
        assertEquals("ok", response.message)
    }

    @Test
    fun acceptsObjectAndNumericTrackIdArraysWithoutCouplingMutationToTheirShape() {
        val objectArray = json.decodeFromString<PlaylistManipulateResponse>(
            """{"code":200,"trackIds":[{"id":123},{"id":456}]}""",
        )
        val numericArray = json.decodeFromString<PlaylistManipulateResponse>(
            """{"code":200,"trackIds":[123,456]}""",
        )

        assertEquals(200, objectArray.code)
        assertEquals(200, numericArray.code)
    }

    @Test
    fun acceptsResponseWithoutTrackIdsAndPreservesServerFailureCode() {
        val success = json.decodeFromString<PlaylistManipulateResponse>("""{"code":200}""")
        val denied = json.decodeFromString<PlaylistManipulateResponse>(
            """{"code":403,"message":"没有权限修改该歌单"}""",
        )

        assertEquals(200, success.code)
        assertEquals(403, denied.code)
        assertEquals("没有权限修改该歌单", denied.message)
    }
}
