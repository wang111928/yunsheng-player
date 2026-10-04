package com.litemusic.shared.api

import com.litemusic.shared.model.IntelligencePlayResponse
import com.litemusic.shared.model.IntelligencePlayEntry
import com.litemusic.shared.model.DailyStyleConfigResponse
import com.litemusic.shared.model.DailyStyleSongsResponse
import com.litemusic.shared.model.DailyStyleSaveResponse
import com.litemusic.shared.model.PersonalizedPlaylist
import com.litemusic.shared.model.Playlist
import com.litemusic.shared.model.RecommendResource
import com.litemusic.shared.model.Song
import com.litemusic.shared.util.AppResult
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecommendationAndIntelligenceModelsTest {
    @Test
    fun recommendationDoesNotTurnTwoBusinessFailuresIntoEmptySuccess() {
        val result = resolveRecommendedPlaylists(
            RecommendResource(code = 500),
            PersonalizedPlaylist(code = 502),
            limit = 6,
        )

        assertIs<AppResult.Failure>(result)
        assertEquals(502, result.code)
    }

    @Test
    fun recommendationFallsBackOnlyToSuccessfulNonEmptyPayload() {
        val result = resolveRecommendedPlaylists(
            RecommendResource(code = 200),
            PersonalizedPlaylist(code = 200, result = listOf(Playlist(id = 8, name = "备用"))),
            limit = 6,
        )

        assertEquals(listOf(8L), (result as AppResult.Success<List<Playlist>>).data.map { it.id })
    }

    @Test
    fun intelligenceResponseExposesSongListOnlyForSuccessfulPayload() {
        val row = IntelligencePlayEntry(songInfo = Song(id = 7, name = "推荐曲"))
        val success = intelligenceSongs(IntelligencePlayResponse(code = 200, data = listOf(row)))
        val failure = intelligenceSongs(IntelligencePlayResponse(code = 500, data = listOf(row)))

        assertEquals(listOf(7L), (success as AppResult.Success<List<Song>>).data.map { it.id })
        assertIs<AppResult.Failure>(failure)
    }

    @Test
    fun intelligenceRequestUsesThePlaymodeFieldNames() {
        assertEquals(
            mapOf("songId" to 7L, "type" to "fromPlayOne", "playlistId" to 9L, "startMusicId" to 8L, "count" to 30),
            intelligencePlayPayload(7L, 9L, 8L, 30),
        )
    }

    @Test
    fun intelligenceResponseUnwrapsSongInfoAndSkipsRowsWithoutPlayableSongs() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<IntelligencePlayResponse>(
            """{"code":200,"data":[{"id":1,"alg":"like","songInfo":{"id":88,"name":"续播曲"}},{"id":2}]}""",
        )
        val result = intelligenceSongs(response)
        assertEquals(listOf("续播曲"), (result as AppResult.Success<List<Song>>).data.map { it.name })
    }

    @Test
    fun dailyStyleConfigKeepsServerCategoriesAndTags() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<DailyStyleConfigResponse>(
            """{"code":200,"data":{"categorys":[{"categoryId":3,"categoryName":"曲风","tagVOList":[{"tagId":31,"tagName":"民谣","categoryId":3}]}]}}""",
        )

        assertEquals(200, response.code)
        assertEquals("曲风", response.data?.categories?.single()?.categoryName)
        assertEquals(31L, response.data?.categories?.single()?.tags?.single()?.tagId)
    }

    @Test
    fun dailyStyleSongsUseTheOfficialHomepageFields() {
        assertEquals(
            mapOf("categoryId" to 3L, "tagId" to "31,32"),
            dailyStyleSongsPayload(categoryId = 3L, tagIds = listOf(31L, 32L)),
        )
        assertEquals(emptyMap(), dailyStyleSongsPayload(tagIds = emptyList()))
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<DailyStyleSongsResponse>(
            """{"code":200,"data":{"dailySongs":[{"id":9,"name":"风格曲"}],"tags":{"categoryId":3,"categoryName":"曲风","tagVOList":[{"tagId":31,"tagName":"民谣","categoryId":3,"isChoose":true}]}}}""",
        )
        assertEquals(listOf(9L), response.data?.dailySongs?.map { it.id })
        assertEquals(3L, response.data?.tags?.categoryId)
        assertEquals(listOf(31L), response.data?.tags?.tags?.filter { it.isChoose }?.map { it.tagId })
    }

    @Test
    fun dailyStyleConfirmationUsesOfficialTagSaveShape() {
        assertEquals("/api/homepage/daily/song/tag/save", dailyStyleSavePath)
        val payload = dailyStyleSavePayload(3L, listOf(31L, 32L, 31L))
        assertEquals("{\"categoryId\":3,\"tagIds\":[31,32]}", payload["tags"])
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<DailyStyleSaveResponse>(
            """{"code":200,"data":{"saveSuccess":true}}""",
        )
        assertEquals(true, response.data?.saveSuccess)
    }
}
