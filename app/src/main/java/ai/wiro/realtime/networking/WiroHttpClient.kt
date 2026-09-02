package ai.wiro.realtime.networking

data class WiroHttpResponse(
    val statusCode: Int,
    val json: Map<String, Any?>?,
)

interface WiroHttpClient {
    suspend fun post(
        path: String,
        headers: Map<String, String>,
        body: Map<String, Any?>,
        cancellation: WiroRequestCancellation?,
    ): WiroHttpResponse

    fun close()
}
