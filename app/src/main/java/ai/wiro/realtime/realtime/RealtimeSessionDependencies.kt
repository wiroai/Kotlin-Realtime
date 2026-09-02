package ai.wiro.realtime.realtime

import ai.wiro.realtime.auth.WiroAuthHeaders
import ai.wiro.realtime.auth.WiroCredentials
import ai.wiro.realtime.networking.DefaultWiroApiClient
import ai.wiro.realtime.networking.KtorWiroHttpClient
import ai.wiro.realtime.networking.WiroApiClient
import ai.wiro.realtime.networking.WiroHttpClient
import kotlin.time.Duration

/**
 * The Wiro stack every realtime feature stands up the same way.
 *
 * One HTTP client, the signed API client on top of it, and a session
 * factory whose ready-wait outlives the model's cold start. Features
 * differ only in that wait — the socket replays nothing to a
 * subscriber that arrives after an event fired, so the ready timeout
 * is sized per model rather than shared.
 *
 * Owns the HTTP client it creates; [close] releases it.
 */
class RealtimeSessionDependencies(
    private val httpClient: WiroHttpClient,
    val apiClient: WiroApiClient,
    private val readyTimeout: Duration,
) {
    fun createSession(
        taskId: String,
        taskToken: String,
    ): RealtimeSession = RealtimeSession(
        socketClient = OkHttpRealtimeSocketClient(),
        taskId = taskId,
        taskToken = taskToken,
        readyTimeout = readyTimeout,
    )

    fun close() {
        httpClient.close()
    }

    companion object {
        fun create(
            credentials: WiroCredentials,
            readyTimeout: Duration,
        ): RealtimeSessionDependencies {
            val httpClient = KtorWiroHttpClient.create()
            return RealtimeSessionDependencies(
                httpClient = httpClient,
                apiClient = DefaultWiroApiClient(
                    httpClient = httpClient,
                    authHeaders = WiroAuthHeaders(
                        credentials = credentials,
                    ),
                ),
                readyTimeout = readyTimeout,
            )
        }
    }
}
