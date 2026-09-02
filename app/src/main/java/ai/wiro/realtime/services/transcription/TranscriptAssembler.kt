package ai.wiro.realtime.services.transcription

/**
 * Folds progressive `TRANSCRIPT_USER` events into running text.
 *
 * The model streams each utterance and starts over at the next
 * one, so a raw replace would leave only the last fragment. An
 * event that extends the live utterance refines it; one that does
 * not is a new utterance, and the finished one is confirmed.
 */
internal class TranscriptAssembler {
    var confirmed: String = ""
        private set
    var live: String = ""
        private set

    fun reset() {
        confirmed = ""
        live = ""
    }

    fun applyUserText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        if (trimmed.startsWith(live) || live.isEmpty()) {
            live = trimmed
        } else if (!live.startsWith(trimmed)) {
            confirmed = joined()
            live = trimmed
        }
        return joined()
    }

    fun joined(): String {
        if (confirmed.isEmpty()) {
            return live
        }
        return "$confirmed $live"
    }
}
