package com.litemusic.shared.api

import com.litemusic.shared.util.AppResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs

class QrLoginRequestTest {
    @Test
    fun qrKeyAndCheckUseTheSameCurrentClientType() = runTest {
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            assertContains(body, "type=3")
            when (request.url.encodedPath) {
                "/api/login/qrcode/unikey" -> respond("""{"code":200,"unikey":"abc"}""", HttpStatusCode.OK)
                "/api/login/qrcode/client/login" -> {
                    assertContains(body, "key=abc")
                    respond("""{"code":801,"cookie":""}""", HttpStatusCode.OK)
                }
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }
        val api = NMApi(ApiClient(engine, baseUrl = "https://example.test"))

        assertIs<AppResult.Success<*>>(api.qrKey())
        assertIs<AppResult.Success<*>>(api.qrCheck("abc"))
    }
}
