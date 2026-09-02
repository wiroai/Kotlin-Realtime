package ai.wiro.realtime.app

import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.models.WiroModelCatalog
import kotlin.time.Duration

/**
 * The conversation, in one place.
 *
 * This is the file to edit to turn the example into a product.
 * Everything else — the transport, the audio pipeline, the screen —
 * stays as it is.
 *
 * The values here win over the catalog's own defaults, which
 * describe the model rather than this app's use of it.
 */
object ConversationConfig {
    /**
     * Who the assistant is and how it should behave.
     *
     * The two sentences about language are worth keeping whatever
     * else is written here: they let a speaker switch languages
     * mid-conversation and stop the model from inventing words
     * out of background noise.
     */
    const val PERSONA: String =
        "Respond in the language of the latest clear spoken " +
            "utterance. Allow intentional language changes. " +
            "Ignore non-speech or unclear audio instead of " +
            "inferring words."

    /** Which voice answers. The full list lives in the catalog. */
    const val VOICE: String = "marin"

    val model: ModelDefinition = WiroModelCatalog.gptRealtimeMini

    /**
     * Engineering overrides. Keys are catalog field ids. Persona
     * and voice have their own fields and win over entries here.
     */
    val parameters: Map<String, String> = emptyMap()

    /**
     * Catalog defaults, then [overrides], then persona and voice.
     *
     * The named fields spread last: a stray duplicate in the map
     * cannot silently contradict [PERSONA] or [VOICE].
     */
    fun parameterValues(
        model: ModelDefinition = ConversationConfig.model,
        persona: String = PERSONA,
        voice: String = VOICE,
        overrides: Map<String, String> = parameters,
    ): ModelParameterValues {
        val merged = overrides.toMutableMap()
        merged["system_instructions"] = persona
        merged["voice"] = voice
        return ModelParameterValues(
            fields = model.firstPassFields,
            overrides = merged,
        )
    }

    /**
     * How long a conversation is held after the speaker leaves.
     *
     * Zero — leave the screen and the conversation is over —
     * because Wiro bills a realtime task by how long it stays up.
     */
    val suspendGracePeriod: Duration = Duration.ZERO
}
