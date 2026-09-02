package ai.wiro.realtime.networking

import ai.wiro.realtime.auth.WiroAuthHeaders
import ai.wiro.realtime.auth.WiroCredentials
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.networking.support.FakeWiroHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiroApiClientTest {
    @Test
    fun returnsTypedTaskIdentifiersFromARunResponse() = runTest {
        val httpClient = FakeWiroHttpClient(
            response = WiroHttpResponse(
                statusCode = 200,
                json = mapOf(
                    "result" to true,
                    "errors" to emptyList<Any>(),
                    "taskid" to "2221",
                    "socketaccesstoken" to "task-token",
                ),
            ),
        )
        val client = makeClient(httpClient)
        val cancellation = WiroRequestCancellation()

        val result = client.runModel(
            model = WiroModelCatalog.gptRealtimeMini,
            parameters = mapOf("voice" to "alloy"),
            cancellation = cancellation,
        )

        assertEquals("2221", result.taskId)
        assertEquals("task-token", result.taskToken)
        assertEquals("/Run/openai/gpt-realtime-mini", httpClient.path)
        assertEquals("alloy", httpClient.body?.get("voice"))
        assertTrue(httpClient.cancellation === cancellation)
        assertEquals(
            mapOf(WiroAuthHeaders.API_KEY_HEADER to "api-key"),
            httpClient.headers,
        )
    }

    @Test
    fun rejectsMalformedSuccessfulResponses() = runTest {
        val client = makeClient(
            FakeWiroHttpClient(
                response = WiroHttpResponse(
                    statusCode = 200,
                    json = mapOf(
                        "result" to true,
                        "errors" to emptyList<Any>(),
                        "taskid" to "2221",
                    ),
                ),
            ),
        )
        val error = runCatching {
            client.runModel(
                model = WiroModelCatalog.gptRealtimeMini,
                parameters = emptyMap(),
                cancellation = null,
            )
        }.exceptionOrNull()
        assertEquals(WiroFailure.MalformedResponse, error)
    }

    @Test
    fun redactsCredentialsAndServerErrorsFromFailures() = runTest {
        val apiKey = "private-api-key"
        val apiSecret = "private-api-secret"
        val client = DefaultWiroApiClient(
            httpClient = FakeWiroHttpClient(
                response = WiroHttpResponse(
                    statusCode = 200,
                    json = mapOf(
                        "result" to false,
                        "errors" to listOf(
                            mapOf(
                                "message" to "Rejected $apiKey and $apiSecret",
                            ),
                        ),
                        "taskid" to null,
                        "socketaccesstoken" to null,
                    ),
                ),
            ),
            authHeaders = WiroAuthHeaders(
                credentials = WiroCredentials(
                    apiKey = apiKey,
                    apiSecret = apiSecret,
                ),
            ),
        )

        val failure = runCatching {
            client.runModel(
                model = WiroModelCatalog.gptRealtimeMini,
                parameters = emptyMap(),
                cancellation = null,
            )
        }.exceptionOrNull() as WiroFailure

        val text = failure.toString()
        assertFalse(text.contains(apiKey))
        assertFalse(text.contains(apiSecret))
        assertFalse(text.contains("Rejected"))
        assertEquals(WiroFailure.ApiRejected, failure)
    }

    @Test
    fun mapsUnauthorizedResponsesToAuthenticationFailure() = runTest {
        val client = makeClient(
            FakeWiroHttpClient(
                response = WiroHttpResponse(
                    statusCode = 401,
                    json = mapOf(
                        "result" to false,
                        "errors" to emptyList<Any>(),
                    ),
                ),
            ),
        )
        val error = runCatching {
            client.runModel(
                model = WiroModelCatalog.gptRealtimeMini,
                parameters = emptyMap(),
                cancellation = null,
            )
        }.exceptionOrNull()
        assertEquals(WiroFailure.Authentication, error)
    }

    @Test
    fun aSuccessfulTaskCancelDoesNotCallKill() = runTest {
        val httpClient = FakeWiroHttpClient(
            response = WiroHttpResponse(
                statusCode = 200,
                json = mapOf("result" to true),
            ),
        )
        val client = makeClient(httpClient)
        client.terminateTaskBestEffort(
            taskId = "task-id",
            taskToken = "task-token",
        )
        assertEquals(listOf("/Task/Cancel"), httpClient.paths)
        assertEquals("task-id", httpClient.body?.get("taskid"))
    }

    @Test
    fun fallsBackFromTaskCancelToTaskKill() = runTest {
        val httpClient = FakeWiroHttpClient(
            response = WiroHttpResponse(
                statusCode = 409,
                json = mapOf("result" to false),
            ),
        )
        val client = makeClient(httpClient)
        client.terminateTaskBestEffort(
            taskId = "task-id",
            taskToken = "task-token",
        )
        assertEquals(
            listOf("/Task/Cancel", "/Task/Kill"),
            httpClient.paths,
        )
        assertEquals("task-token", httpClient.body?.get("socketaccesstoken"))
    }

    @Test
    fun failureExposesAStableMachineReadableCode() {
        val failure = WiroFailure.SessionBusy
        assertEquals(WiroFailureCode.SessionBusy, failure.code)
        assertTrue(failure.diagnostics.isEmpty())
    }

    private fun makeClient(
        httpClient: FakeWiroHttpClient,
    ): DefaultWiroApiClient = DefaultWiroApiClient(
        httpClient = httpClient,
        authHeaders = WiroAuthHeaders(
            credentials = WiroCredentials(apiKey = "api-key"),
        ),
    )
}
