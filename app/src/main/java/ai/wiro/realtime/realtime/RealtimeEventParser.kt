package ai.wiro.realtime.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class RealtimeEventParser {
    fun parse(frame: RealtimeSocketFrame): RealtimeEvent = when (frame) {
        is RealtimeSocketFrame.Binary ->
            RealtimeEvent.Binary(frame.bytes)

        is RealtimeSocketFrame.Text -> parseJson(frame.text)
    }

    private fun parseJson(frame: String): RealtimeEvent {
        val obj = runCatching {
            Json.parseToJsonElement(frame)
        }.getOrNull() as? JsonObject ?: return RealtimeEvent.Malformed
        val wireType = runCatching {
            obj["type"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
        if (wireType.isEmpty()) {
            return RealtimeEvent.Malformed
        }
        return when (wireType) {
            WiroRealtimeProtocol.TASK_OUTPUT -> parseOutput(obj)

            WiroRealtimeProtocol.TASK_ERROR,
            WiroRealtimeProtocol.TASK_ERROR_FULL,
            -> RealtimeEvent.Error(wireType)

            WiroRealtimeProtocol.TASK_CANCEL -> RealtimeEvent.Cancel

            else -> {
                val known = RealtimeEventType.fromWire(wireType)
                if (known != null) {
                    RealtimeEvent.Status(known)
                } else {
                    RealtimeEvent.Unknown(wireType)
                }
            }
        }
    }

    private fun parseOutput(obj: JsonObject): RealtimeEvent {
        val message = runCatching {
            obj["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return if (message == null) {
            RealtimeEvent.Malformed
        } else {
            RealtimeEvent.Output(message)
        }
    }
}
