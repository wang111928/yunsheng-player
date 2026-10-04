package com.litemusic.shared.api

import com.litemusic.shared.util.AppResult
import com.litemusic.shared.model.RadioProgramDetailResponse
import com.litemusic.shared.model.RadioProgramResponse
import com.litemusic.shared.model.RadioStationResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RadioProgramApiIntegrationTest {
    @Test
    fun radioStationPagesUseThePagedPodcastAndAudiobookRoutes() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            respond(
                content = """{"code":200,"djRadios":[{"id":7,"name":"电台"}]}""",
                status = HttpStatusCode.OK,
            )
        }
        val api = NMApi(ApiClient(engine, baseUrl = "https://example.test"))

        val podcast = api.radioStations(audiobook = false, offset = 30, limit = 20)
        val audiobook = api.radioStations(audiobook = true, offset = 40, limit = 10)

        assertEquals(listOf("/weapi/djradio/hot/v1", "/weapi/djradio/hot"), paths)
        assertEquals(7L, assertIs<AppResult.Success<RadioStationResponse>>(podcast).data.stations.single().id)
        assertEquals(7L, assertIs<AppResult.Success<RadioStationResponse>>(audiobook).data.stations.single().id)
    }

    @Test
    fun radioProgramsUseTheOfficialReadOnlyRouteWithTheRequestedPageSize() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/dj/program/byradio", request.url.encodedPath)
            assertEquals("42", request.url.parameters["radioId"])
            assertEquals("false", request.url.parameters["asc"])
            assertEquals("20", request.url.parameters["limit"])
            assertEquals("40", request.url.parameters["offset"])
            respond(
                content = """{"code":200,"programs":[{"id":7,"name":"节目","programDesc":[{"type":1,"content":"第一段"},{"type":1,"content":"第二段"}]}]}""",
                status = HttpStatusCode.OK,
            )
        }
        val api = NMApi(ApiClient(engine, baseUrl = "https://example.test"))

        val result = api.radioPrograms(radioId = 42L, limit = 20, offset = 40)

        val success = assertIs<AppResult.Success<RadioProgramResponse>>(result)
        val response = success.data
        assertEquals("第一段\n第二段", response.programs.single().text)
    }

    @Test
    fun radioProgramDetailUsesTheOfficialProgramDetailRoute() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/dj/program/detail", request.url.encodedPath)
            assertEquals("42", request.url.parameters["id"])
            respond(
                content = """{"code":200,"program":{"id":42,"name":"详情节目","mainSong":{"id":99,"name":"可播放歌曲"}}}""",
                status = HttpStatusCode.OK,
            )
        }
        val api = NMApi(ApiClient(engine, baseUrl = "https://example.test"))

        val result = api.radioProgramDetail(programId = 42L)

        val success = assertIs<AppResult.Success<RadioProgramDetailResponse>>(result)
        assertEquals(99L, success.data.program.mainSong?.id)
    }
}
