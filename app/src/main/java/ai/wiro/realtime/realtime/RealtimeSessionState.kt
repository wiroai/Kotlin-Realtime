package ai.wiro.realtime.realtime

enum class RealtimeSessionState {
    Idle,
    RequestingPermission,
    PreparingAudio,
    CreatingTask,
    ConnectingSocket,
    WaitingForStream,
    Streaming,
    Stopping,
    Completed,
    Failure,
    ;

    val isIdleLike: Boolean
        get() = this == Idle || this == Completed || this == Failure

    val isActive: Boolean
        get() = !isIdleLike

    val canStart: Boolean
        get() = isIdleLike

    val canStop: Boolean
        get() = isActive
}
