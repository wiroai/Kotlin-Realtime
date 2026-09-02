package ai.wiro.realtime.networking

import ai.wiro.realtime.auth.WiroAuthHeaders
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorWiroHttpClientTest {
    @Test
    fun postsJsonAndParsesARunEnvelope() = runTest {
        val engine = MockEngine { request ->
            assertTrue(request.url.toString().endsWith("/Run/openai/gpt"))
            assertEquals(
                "test-key",
                request.headers[WiroAuthHeaders.API_KEY_HEADER],
            )
            respond(
                content = ByteReadChannel(
                    """{"result":true,"errors":[],"taskid":"9",""" +
                        """"socketaccesstoken":"tok"}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    "application/json",
                ),
            )
        }
        val client = KtorWiroHttpClient(
            client = HttpClient(engine) {
                install(HttpTimeout)
            },
            baseUrl = "https://example.test/v1",
        )
        val response = client.post(
            path = "/Run/openai/gpt",
            headers = mapOf("x-api-key" to "test-key"),
            body = mapOf("voice" to "marin"),
            cancellation = null,
        )
        assertEquals(200, response.statusCode)
        assertEquals(true, response.json?.get("result"))
        assertEquals("tok", response.json?.get("socketaccesstoken"))
        client.close()
    }

    @Test
    fun cancelledBeforeSendDoesNotHitTheNetwork() = runTest {
        var hits = 0
        val engine = MockEngine {
            hits += 1
            respond("{}", HttpStatusCode.OK)
        }
        val client = KtorWiroHttpClient(
            client = HttpClient(engine) { install(HttpTimeout) },
        )
        val cancellation = WiroRequestCancellation()
        cancellation.cancel()
        val error = runCatching {
            client.post(
                path = "/Run/openai/gpt",
                headers = emptyMap(),
                body = emptyMap(),
                cancellation = cancellation,
            )
        }.exceptionOrNull()
        assertEquals(WiroFailure.Cancelled, error)
        assertEquals(0, hits)
        client.close()
    }
}
