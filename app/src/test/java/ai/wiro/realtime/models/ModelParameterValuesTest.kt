package ai.wiro.realtime.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelParameterValuesTest {
    @Test
    fun convertsNumericModelFieldsForJsonRequests() {
        val parameters = ModelParameterValues(
            fields = WiroModelCatalog.voxcpm2.firstPassFields,
        )
        val snapshot = parameters.requestSnapshot()

        assertEquals(
            ModelRequestValue.Decimal(2.0),
            snapshot["cfgValue"],
        )
        assertEquals(
            ModelRequestValue.Integer(10),
            snapshot["inferenceSteps"],
        )
        assertEquals(2.0, parameters.requestBody()["cfgValue"])
        assertEquals(10, parameters.requestBody()["inferenceSteps"])
    }

    @Test
    fun usesNoiseResistantConversationDefaults() {
        val parameters = ModelParameterValues(
            fields = WiroModelCatalog.gptRealtimeMini.firstPassFields,
        )
        val snapshot = parameters.requestSnapshot()

        assertEquals(
            ModelRequestValue.Decimal(0.75),
            snapshot["turn_detection_threshold"],
        )
        assertEquals(
            ModelRequestValue.Integer(700),
            snapshot["turn_detection_silence_ms"],
        )
        assertEquals(
            700,
            parameters.requestBody()["turn_detection_silence_ms"],
        )
    }

    @Test
    fun overridesReplaceCatalogDefaultsAndIgnoreUnknownIds() {
        val parameters = ModelParameterValues(
            fields = WiroModelCatalog.gptRealtimeMini.firstPassFields,
            overrides = mapOf(
                "voice" to "cedar",
                "system_instructions" to "You answer support questions.",
                "not_a_field" to "ignored",
            ),
        )
        val snapshot = parameters.requestSnapshot()

        assertEquals(ModelRequestValue.Text("cedar"), snapshot["voice"])
        assertEquals(
            ModelRequestValue.Text("You answer support questions."),
            snapshot["system_instructions"],
        )
        assertNull(snapshot["not_a_field"])
        assertEquals(
            ModelRequestValue.Decimal(0.75),
            snapshot["turn_detection_threshold"],
        )
    }

    @Test
    fun withValueIgnoresUnknownIdsAndKeepsTheRest() {
        val original = ModelParameterValues(
            fields = WiroModelCatalog.voxtralMiniRealtime.firstPassFields,
        )

        val ignored = original.withValue("not_a_field", "nope")
        assertEquals(original, ignored)

        val updated = original.withValue("transcriptionDelay", "fast")
        assertEquals("fast", updated.value("transcriptionDelay"))
        assertEquals("balanced", original.value("transcriptionDelay"))
    }
}
