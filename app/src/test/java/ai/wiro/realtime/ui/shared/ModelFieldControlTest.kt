package ai.wiro.realtime.ui.shared

import ai.wiro.realtime.models.ModelFieldDefinition
import ai.wiro.realtime.models.ModelFieldKind
import ai.wiro.realtime.models.ModelFieldOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFieldControlTest {
    @Test
    fun twoOptionBooleanSelectsBecomeAToggle() {
        val field = ModelFieldDefinition(
            id = "enabled",
            label = "Enabled",
            kind = ModelFieldKind.Select,
            isRequired = false,
            includeInFirstPass = true,
            options = listOf(
                ModelFieldOption("true"),
                ModelFieldOption("false"),
            ),
        )
        assertEquals(
            ModelFieldControl.Toggle(on = "true", off = "false"),
            field.control,
        )
    }

    @Test
    fun voiceSelectsStayAPicker() {
        val field = ModelFieldDefinition(
            id = "voice",
            label = "Voice",
            kind = ModelFieldKind.Select,
            isRequired = true,
            includeInFirstPass = true,
            options = listOf(
                ModelFieldOption("marin"),
                ModelFieldOption("cedar"),
            ),
        )
        assertEquals(ModelFieldControl.Picker, field.control)
    }

    @Test
    fun numbersUseANumericKeyboard() {
        val field = ModelFieldDefinition(
            id = "steps",
            label = "Steps",
            kind = ModelFieldKind.Number,
            isRequired = false,
            includeInFirstPass = true,
        )
        val control = field.control
        assertTrue(control is ModelFieldControl.TextField)
        assertEquals(
            ModelFieldKeyboard.Number,
            (control as ModelFieldControl.TextField).keyboard,
        )
    }
}
