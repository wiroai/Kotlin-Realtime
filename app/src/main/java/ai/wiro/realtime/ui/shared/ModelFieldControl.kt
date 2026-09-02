package ai.wiro.realtime.ui.shared

import ai.wiro.realtime.models.ModelFieldDefinition
import ai.wiro.realtime.models.ModelFieldKind
import ai.wiro.realtime.models.ModelFieldOption

sealed class ModelFieldControl {
    data object Picker : ModelFieldControl()

    data class TextField(
        val isMultiline: Boolean,
        val keyboard: ModelFieldKeyboard,
    ) : ModelFieldControl()

    data class Toggle(
        val on: String,
        val off: String,
    ) : ModelFieldControl()
}

enum class ModelFieldKeyboard {
    Default,
    Number,
    Decimal,
}

val ModelFieldDefinition.control: ModelFieldControl
    get() = when (kind) {
        ModelFieldKind.Select -> {
            val pair = booleanPair(options)
            when {
                pair != null -> ModelFieldControl.Toggle(
                    on = pair.first,
                    off = pair.second,
                )

                options.isNotEmpty() -> ModelFieldControl.Picker

                else -> ModelFieldControl.TextField(
                    isMultiline = false,
                    keyboard = ModelFieldKeyboard.Default,
                )
            }
        }

        ModelFieldKind.Textarea -> ModelFieldControl.TextField(
            isMultiline = true,
            keyboard = ModelFieldKeyboard.Default,
        )

        ModelFieldKind.Number -> ModelFieldControl.TextField(
            isMultiline = false,
            keyboard = ModelFieldKeyboard.Number,
        )

        ModelFieldKind.Float -> ModelFieldControl.TextField(
            isMultiline = false,
            keyboard = ModelFieldKeyboard.Decimal,
        )

        ModelFieldKind.Text -> ModelFieldControl.TextField(
            isMultiline = false,
            keyboard = ModelFieldKeyboard.Default,
        )
    }

fun booleanPair(
    options: List<ModelFieldOption>,
): Pair<String, String>? {
    if (options.size != 2) {
        return null
    }
    val values = options.map { it.value }
    val lowered = values.map { it.lowercase() }
    val pairs = listOf(
        "true" to "false",
        "yes" to "no",
        "on" to "off",
        "1" to "0",
    )
    for ((on, off) in pairs) {
        val onIndex = lowered.indexOf(on)
        val offIndex = lowered.indexOf(off)
        if (onIndex >= 0 && offIndex >= 0) {
            return values[onIndex] to values[offIndex]
        }
    }
    return null
}
