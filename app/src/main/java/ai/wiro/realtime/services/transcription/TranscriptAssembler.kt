package ai.wiro.realtime.services.transcription

/**
 * Builds the running text from `TRANSCRIPT_USER` pieces.
 *
 * Each piece carries only new text, often a single word or a short
 * phrase, and never repeats or revises an earlier one. The full
 * transcript is every non-empty piece, trimmed and joined with
 * single spaces, which is also how the server saves it.
 */
internal class TranscriptAssembler {
    private var transcript: String = ""

    fun reset() {
        transcript = ""
    }

    /**
     * Appends one piece and returns the whole transcript, or null
     * when the piece holds no text.
     */
    fun applyUserText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        transcript = if (transcript.isEmpty()) {
            trimmed
        } else {
            "$transcript $trimmed"
        }
        return transcript
    }

    fun joined(): String = transcript
}
