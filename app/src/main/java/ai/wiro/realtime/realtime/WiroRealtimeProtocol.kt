package ai.wiro.realtime.realtime

/**
 * Task WebSocket frames confirmed from Wiro docs on 2026-08-31.
 *
 * Subscribe once after connect. Do not send PCM before
 * [TASK_STREAM_READY]. Audio bytes are
 * `UTF8(tasktoken + "|") + pcm`, signed Int16, 24 kHz, mono.
 */
object WiroRealtimeProtocol {
    const val TASK_INFO: String = "task_info"
    const val TASK_SESSION_END: String = "task_session_end"
    const val TASK_STREAM_READY: String = "task_stream_ready"
    const val TASK_STREAM_END: String = "task_stream_end"
    const val TASK_OUTPUT: String = "task_output"
    const val TASK_COST: String = "task_cost"
    const val TASK_END: String = "task_end"
    const val TASK_POSTPROCESS_END: String = "task_postprocess_end"
    const val TASK_ERROR: String = "task_error"
    const val TASK_ERROR_FULL: String = "task_error_full"
    const val TASK_CANCEL: String = "task_cancel"

    const val TRANSCRIPT_USER_PREFIX: String = "TRANSCRIPT_USER:"
    const val TRANSCRIPT_AI_PREFIX: String = "TRANSCRIPT_AI:"

    const val BINARY_PREFIX_SEPARATOR: String = "|"
}
