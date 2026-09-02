package ai.wiro.realtime.realtime

enum class RealtimeEventType(
    val wireName: String,
) {
    Queue("task_queue"),
    Accepted("task_accept"),
    PreprocessStarted("task_preprocess_start"),
    PreprocessEnded("task_preprocess_end"),
    Assigned("task_assign"),
    Started("task_start"),
    OutputFull("task_output_full"),
    Ended("task_end"),
    PostprocessStarted("task_postprocess_start"),
    PostprocessEnded("task_postprocess_end"),
    StreamReady("task_stream_ready"),
    StreamEnded("task_stream_end"),
    Cost("task_cost"),
    ;

    companion object {
        fun fromWire(wireName: String): RealtimeEventType? = entries.firstOrNull { it.wireName == wireName }
    }
}

sealed class RealtimeEvent {
    abstract val wireType: String

    data class Status(
        val type: RealtimeEventType,
    ) : RealtimeEvent() {
        override val wireType: String
            get() = type.wireName

        override fun toString(): String = "status(${type.wireName})"
    }

    data class Output(
        val message: String,
    ) : RealtimeEvent() {
        override val wireType: String
            get() = WiroRealtimeProtocol.TASK_OUTPUT

        override fun toString(): String = "output($message)"
    }

    data class Error(
        val wireTypeName: String,
    ) : RealtimeEvent() {
        override val wireType: String
            get() = wireTypeName

        override fun toString(): String = "error($wireTypeName)"
    }

    data object Cancel : RealtimeEvent() {
        override val wireType: String
            get() = WiroRealtimeProtocol.TASK_CANCEL

        override fun toString(): String = "cancel"
    }

    data class Binary(
        val bytes: ByteArray,
    ) : RealtimeEvent() {
        override val wireType: String
            get() = "binary"

        override fun toString(): String = "binary(${bytes.size})"

        override fun equals(other: Any?): Boolean = other is Binary && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Unknown(
        val wireTypeName: String,
    ) : RealtimeEvent() {
        override val wireType: String
            get() = wireTypeName

        override fun toString(): String = "unknown($wireTypeName)"
    }

    data object Malformed : RealtimeEvent() {
        override val wireType: String
            get() = "malformed"

        override fun toString(): String = "malformed"
    }
}
