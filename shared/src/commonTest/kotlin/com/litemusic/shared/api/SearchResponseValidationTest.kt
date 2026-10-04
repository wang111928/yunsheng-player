package com.litemusic.shared.api

import com.litemusic.shared.model.SearchResult
import com.litemusic.shared.util.AppResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SearchResponseValidationTest {
    private fun api(body: String) = NMApi(ApiClient(MockEngine { respond(body) }, baseUrl = "https://example.test"))

    @Test fun usesTheCurrentOfficialWebSearchRoute() = runTest {
        var requestedPath = ""
        val api = NMApi(ApiClient(MockEngine { request ->
            requestedPath = request.url.encodedPath
            respond("""{"code":200,"result":{"songs":[],"songCount":0}}""")
        }, baseUrl = "https://example.test"))
        api.search("追")
        assertEquals("/weapi/cloudsearch/get/web", requestedPath)
    }

    @Test fun businessErrorsDoNotBecomeSuccessfulEmptySearches() = runTest {
        val result = api("""{"code":405,"message":"temporarily unavailable"}""").search("追")
        assertEquals(405, assertIs<AppResult.Failure>(result).code)
    }

    @Test fun missingSearchPayloadIsAnErrorRatherThanNoMatchingSongs() = runTest {
        assertIs<AppResult.Failure>(api("""{"code":200}""").search("长夜"))
    }

    @Test fun genuineZeroResultSearchRemainsSuccessful() = runTest {
        val result = api("""{"code":200,"result":{"songs":[],"songCount":0}}""").search("无此歌曲")
        assertEquals(0, assertIs<AppResult.Success<SearchResult>>(result).data.result?.songCount)
    }

    @Test fun emptyOrMalformedPayloadDoesNotMasqueradeAsZeroSongs() = runTest {
        listOf("{}", "{\"songs\":null}", "{\"songs\":{}}", "{\"songCount\":8}").forEach { payload ->
            assertIs<AppResult.Failure>(api("{\"code\":200,\"result\":$payload}").search("晴天"))
        }
        assertIs<AppResult.Success<SearchResult>>(api("""{"code":200,"result":{"songCount":0}}""").search("无此歌曲"))
    }

    @Test fun albumSearchParsesTheOfficialArtistEvidence() = runTest {
        val response = api("""{"code":200,"result":{"albums":[{"id":12,"name":"专辑","artist":{"id":3,"name":"歌手"}}]}}""").search("专辑 歌手", type = 10)
        assertEquals("歌手", assertIs<AppResult.Success<SearchResult>>(response).data.result?.albums?.single()?.artist?.name)
    }

    @Test fun albumTracksRequireARealSuccessfulTrackList() = runTest {
        assertIs<AppResult.Failure>(api("""{"code":200}""").albumSongsForImport(12))
        assertIs<AppResult.Failure>(api("""{"code":405}""").albumSongsForImport(12))
        val songs = api("""{"code":200,"songs":[{"id":1,"name":"歌曲"}]}""").albumSongsForImport(12)
        assertEquals(1L, assertIs<AppResult.Success<List<com.litemusic.shared.model.Song>>>(songs).data.single().id)
    }
}
