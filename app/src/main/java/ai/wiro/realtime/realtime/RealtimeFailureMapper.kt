package ai.wiro.realtime.realtime

import ai.wiro.realtime.audio.AudioFailure
import ai.wiro.realtime.networking.WiroFailure

/**
 * Translates session errors into a user-facing message and a stable
 * machine-readable code.
 *
 * Errors outside the project's failure types collapse to
 * [fallbackMessage] and the `unknown` code, so raw exception text
 * never reaches the UI or the protocol log.
 */
class RealtimeFailureMapper(
    val fallbackMessage: String,
) {
    fun message(error: Throwable): String = when (error) {
        is WiroFailure -> error.message
        is AudioFailure -> error.message
        else -> fallbackMessage
    }

    fun code(error: Throwable): String = when (error) {
        is WiroFailure -> error.code.wireName
        is AudioFailure -> error.code.wireName
        else -> UNKNOWN_CODE
    }

    companion object {
        const val UNKNOWN_CODE: String = "unknown"
    }
}

private val Enum<*>.wireName: String
    get() = name.replaceFirstChar { it.lowercase() }
