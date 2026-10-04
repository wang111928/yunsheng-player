package com.litemusic.shared.api

import com.litemusic.shared.model.Song
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MvApiModelsTest {
    @Test
    fun songMvIdAcceptsTheAlternateMvidField() {
        val song = Json { ignoreUnknownKeys = true }.decodeFromString<Song>(
            """{"id":4,"name":"带视频的歌","mvid":19}""",
        )
        assertEquals(19L, song.mv)
    }

    @Test
    fun mvUrlPayloadUsesThePlaybackEndpointAndExposesTheStreamUrl() {
        assertEquals("/weapi/song/enhance/play/mv/url", mvUrlPath)
        assertEquals(19L, mvUrlPayload(19L, 1080)["id"])
        assertEquals(1080, mvUrlPayload(19L, 1080)["r"])

        val response = Json { ignoreUnknownKeys = true }.decodeFromString<MvUrlResponse>(
            """{"code":200,"data":{"id":19,"url":"https://example.test/video.m3u8","r":1080}}""",
        )
        assertEquals("https://example.test/video.m3u8", response.data?.url)
    }
}
