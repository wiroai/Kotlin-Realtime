package ai.wiro.realtime.networking

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException

class KtorWiroHttpClient(
    private val client: HttpClient,
    private val baseUrl: String = WiroEndpoints.REST_BASE,
    private val ownsClient: Boolean = false,
) : WiroHttpClient {
    override suspend fun post(
        path: String,
        headers: Map<String, String>,
        body: Map<String, Any?>,
        cancellation: WiroRequestCancellation?,
    ): WiroHttpResponse {
        if (cancellation?.isCancelled == true) {
            throw WiroFailure.Cancelled
        }
        val url = baseUrl + path
        val payload = Json.encodeToString(
            JsonObject.serializer(),
            body.toJsonObject(),
        )
        return try {
            coroutineScope {
                val request = async {
                    client.post(url) {
                        timeout {
                            requestTimeoutMillis = timeoutMillis(path)
                        }
                        contentType(ContentType.Application.Json)
                        headers.forEach { (name, value) ->
                            header(name, value)
                        }
                        setBody(payload)
                    }
                }
                val watcher = cancellation?.let { token ->
                    launch {
                        token.awaitCancelled()
                        request.cancel()
                    }
                }
                try {
                    val response = request.await()
                    WiroHttpResponse(
                        statusCode = response.status.value,
                        json = parseJsonObject(response.bodyAsText()),
                    )
                } finally {
                    watcher?.cancel()
                }
            }
        } catch (error: WiroFailure) {
            throw error
        } catch (error: CancellationException) {
            if (cancellation?.isCancelled == true) {
                throw WiroFailure.Cancelled
            }
            throw error
        } catch (_: HttpRequestTimeoutException) {
            throw WiroFailure.RequestTimeout
        } catch (_: IOException) {
            throw WiroFailure.NetworkUnavailable
        }
    }

    override fun close() {
        if (ownsClient) {
            client.close()
        }
    }

    private fun timeoutMillis(path: String): Long = if (path.startsWith("/Run/")) {
        RUN_TIMEOUT_MS
    } else {
        DEFAULT_TIMEOUT_MS
    }

    private fun parseJsonObject(text: String): Map<String, Any?>? {
        if (text.isBlank()) {
            return null
        }
        val element = runCatching {
            Json.parseToJsonElement(text)
        }.getOrNull() as? JsonObject
        @Suppress("UNCHECKED_CAST")
        return element?.toPlain() as? Map<String, Any?>
    }

    companion object {
        const val RUN_TIMEOUT_MS: Long = 120_000
        const val DEFAULT_TIMEOUT_MS: Long = 30_000

        fun create(
            baseUrl: String = WiroEndpoints.REST_BASE,
        ): KtorWiroHttpClient {
            val client = HttpClient(OkHttp) {
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = RUN_TIMEOUT_MS
                }
            }
            return KtorWiroHttpClient(
                client = client,
                baseUrl = baseUrl,
                ownsClient = true,
            )
        }
    }
}
