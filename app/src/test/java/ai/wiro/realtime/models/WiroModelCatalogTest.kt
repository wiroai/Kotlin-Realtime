package ai.wiro.realtime.models

import org.junit.Assert.assertEquals
import org.junit.Test

class WiroModelCatalogTest {
    @Test
    fun catalogFieldIdsMatchThe20260831McpSchemas() {
        assertEquals(
            "openai/gpt-realtime-mini",
            WiroModelCatalog.gptRealtimeMini.modelSlug,
        )
        assertEquals(
            listOf(
                "voice",
                "system_instructions",
                "transcription_model",
                "input_audio_format",
                "output_audio_format",
                "input_audio_rate",
                "output_audio_rate",
                "turn_detection_threshold",
                "turn_detection_silence_ms",
            ),
            WiroModelCatalog.gptRealtimeMini.firstPassFields.map { it.id },
        )

        val voxtralFields =
            WiroModelCatalog.voxtralMiniRealtime.firstPassFields
        assertEquals(1, voxtralFields.size)
        assertEquals("transcriptionDelay", voxtralFields[0].id)
        assertEquals("balanced", voxtralFields[0].defaultValue)

        assertEquals(
            listOf("text"),
            WiroModelCatalog.mossTtsRealtime.firstPassFields.map { it.id },
        )
        assertEquals(
            listOf("prompt", "cfgValue", "inferenceSteps"),
            WiroModelCatalog.voxcpm2.firstPassFields.map { it.id },
        )
        assertEquals(
            "2.0",
            WiroModelCatalog.voxcpm2.fields
                .first { it.id == "cfgValue" }
                .defaultValue,
        )
    }

    @Test
    fun gptRealtimeMiniVoicesMatchTheLiveSelectOptions() {
        assertEquals(
            listOf(
                "marin",
                "cedar",
                "alloy",
                "ash",
                "ballad",
                "coral",
                "echo",
                "fable",
                "nova",
                "onyx",
                "sage",
                "shimmer",
                "verse",
            ),
            WiroModelCatalog.gptRealtimeMini.fields
                .first { it.id == "voice" }
                .options
                .map { it.value },
        )
    }
}
