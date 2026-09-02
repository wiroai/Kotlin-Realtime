package ai.wiro.realtime.networking.support

import ai.wiro.realtime.networking.WiroHttpClient
import ai.wiro.realtime.networking.WiroHttpResponse
import ai.wiro.realtime.networking.WiroRequestCancellation

class FakeWiroHttpClient(
    var response: WiroHttpResponse,
) : WiroHttpClient {
    var path: String? = null
    var headers: Map<String, String>? = null
    var body: Map<String, Any?>? = null
    var cancellation: WiroRequestCancellation? = null
    val paths: MutableList<String> = mutableListOf()

    override suspend fun post(
        path: String,
        headers: Map<String, String>,
        body: Map<String, Any?>,
        cancellation: WiroRequestCancellation?,
    ): WiroHttpResponse {
        paths += path
        this.path = path
        this.headers = headers
        this.body = body
        this.cancellation = cancellation
        return response
    }

    override fun close() = Unit
}
