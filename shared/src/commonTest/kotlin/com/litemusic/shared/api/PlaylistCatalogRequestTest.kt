package com.litemusic.shared.api

import com.litemusic.shared.model.PlaylistCatalogResponse
import com.litemusic.shared.model.PlaylistDetailResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCatalogRequestTest {
    @Test
    fun songDetailPayloadUsesAValidJsonArrayForTheCParameter() {
        val c = songDetailPayload(listOf(71L, 9L))["c"] as String
        val ids = Json.parseToJsonElement(c).jsonArray.map { it.jsonObject["id"]?.toString() }

        assertEquals(listOf("71", "9"), ids)
    }

    @Test
    fun radioStationsUseEndpointsThatActuallySupportOffsetPaging() {
        assertEquals("/weapi/djradio/hot/v1", podcastStationPath)
        assertEquals(mapOf("offset" to 30, "limit" to 20), radioStationPayload(audiobook = false, offset = 30, limit = 20))
        assertEquals("/weapi/djradio/hot", audiobookStationPath)
        assertEquals(
            mapOf("offset" to 30, "limit" to 20, "cateId" to 10001),
            radioStationPayload(audiobook = true, offset = 30, limit = 20),
        )
    }

    @Test
    fun catalogUsesThePagedHotPlaylistContract() {
        assertEquals("/weapi/playlist/list", playlistCatalogPath)
        assertEquals(
            mapOf(
                "cat" to "全部",
                "order" to "hot",
                "limit" to 30,
                "offset" to 60,
                "total" to true,
            ),
            playlistCatalogPayload(offset = 60, limit = 30),
        )
    }

    @Test
    fun catalogCanPageNewPlaylistsAfterTheHotCatalogEnds() {
        assertEquals("new", playlistCatalogPayload(offset = 30, limit = 30, order = "new")["order"])
        assertEquals(30, playlistCatalogPayload(offset = 30, limit = 30, order = "new")["offset"])
    }

    @Test
    fun playlistDetailCanRequestABoundedTrackCount() {
        assertEquals(
            mapOf<String, Any>("id" to 42L, "n" to 30, "s" to 8),
            playlistDetailPayload(id = 42L, n = 30),
        )
    }

    @Test
    fun catalogResponseDecodesTotalMoreAndRows() {
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<PlaylistCatalogResponse>(
            """{"code":200,"total":61,"more":true,"playlists":[{"id":7,"name":"歌单"}]}""",
        )

        assertEquals(200, decoded.code)
        assertEquals(61, decoded.total)
        assertTrue(decoded.more)
        assertEquals(listOf(7L), decoded.playlists.map { it.id })
    }

    @Test
    fun playlistDetailPreservesTrackIdsWhenEmbeddedTracksAreMissing() {
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<PlaylistDetailResponse>(
            """{"code":200,"playlist":{"id":7,"trackCount":2,"tracks":[],"trackIds":[{"id":81},{"id":82}]}}""",
        )

        assertEquals(emptyList<Long>(), decoded.playlist?.tracks?.map { it.id })
        assertEquals(listOf(81L, 82L), decoded.playlist?.trackIds?.map { it.id })
    }
}
