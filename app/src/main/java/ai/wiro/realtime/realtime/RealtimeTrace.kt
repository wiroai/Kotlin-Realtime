package ai.wiro.realtime.realtime

/**
 * Session breadcrumbs for a later debug panel.
 *
 * Tokens and credentials stay out of disk and the protocol log.
 */
object RealtimeTrace {
    fun line(message: String) {
        if (message.isEmpty()) {
            return
        }
    }
}
