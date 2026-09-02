package ai.wiro.realtime.networking

enum class WiroFailureCode {
    Authentication,
    ApiRejected,
    RateLimited,
    ServerUnavailable,
    NetworkUnavailable,
    RequestTimeout,
    Cancelled,
    MalformedResponse,
    SocketConnection,
    SocketConnectTimeout,
    RealtimeProtocol,
    StreamReadyTimeout,
    SessionBusy,
}

data class WiroFailure(
    val code: WiroFailureCode,
    override val message: String,
    val diagnostics: Map<String, String> = emptyMap(),
) : Exception(message) {
    override fun toString(): String = message

    companion object {
        val Authentication = WiroFailure(
            code = WiroFailureCode.Authentication,
            message = "Wiro authentication failed. " +
                "Check the project credentials.",
        )
        val ApiRejected = WiroFailure(
            code = WiroFailureCode.ApiRejected,
            message = "Wiro rejected the model Run request.",
        )
        val RateLimited = WiroFailure(
            code = WiroFailureCode.RateLimited,
            message = "Wiro request limit reached. Try again later.",
        )
        val ServerUnavailable = WiroFailure(
            code = WiroFailureCode.ServerUnavailable,
            message = "Wiro is temporarily unavailable. Try again later.",
        )
        val NetworkUnavailable = WiroFailure(
            code = WiroFailureCode.NetworkUnavailable,
            message = "Could not reach Wiro. Check the network connection.",
        )
        val RequestTimeout = WiroFailure(
            code = WiroFailureCode.RequestTimeout,
            message = "The Wiro request timed out. Try again.",
        )
        val Cancelled = WiroFailure(
            code = WiroFailureCode.Cancelled,
            message = "The Wiro request was cancelled.",
        )
        val MalformedResponse = WiroFailure(
            code = WiroFailureCode.MalformedResponse,
            message = "Wiro returned an unexpected response.",
        )
        val SocketConnection = WiroFailure(
            code = WiroFailureCode.SocketConnection,
            message = "The Wiro realtime connection failed.",
        )
        val SocketConnectTimeout = WiroFailure(
            code = WiroFailureCode.SocketConnectTimeout,
            message = "The Wiro realtime connection timed out. Try again.",
        )
        val RealtimeProtocol = WiroFailure(
            code = WiroFailureCode.RealtimeProtocol,
            message = "Wiro returned an unexpected realtime event.",
        )
        val StreamReadyTimeout = WiroFailure(
            code = WiroFailureCode.StreamReadyTimeout,
            message = "The Wiro realtime stream did not become ready in time.",
        )
        val SessionBusy = WiroFailure(
            code = WiroFailureCode.SessionBusy,
            message = "Another realtime session is already active.",
        )
    }
}
