package ai.wiro.realtime.models

sealed class ModelRequestValue {
    data class Text(val value: String) : ModelRequestValue()

    data class Integer(val value: Int) : ModelRequestValue()

    data class Decimal(val value: Double) : ModelRequestValue()

    fun asWire(): Any = when (this) {
        is Text -> value
        is Integer -> value
        is Decimal -> value
    }
}

/**
 * Catalog defaults plus the ids an application chooses to replace.
 *
 * Overrides replace the catalog defaults for the ids they name. Ids
 * the model does not expose are ignored.
 */
data class ModelParameterValues(
    private val definitions: Map<String, ModelFieldDefinition>,
    private val values: Map<String, String>,
) {
    constructor(
        fields: List<ModelFieldDefinition>,
        overrides: Map<String, String> = emptyMap(),
    ) : this(
        definitions = fields
            .filter { it.includeInFirstPass }
            .associateBy { it.id },
        values = fields
            .filter { it.includeInFirstPass }
            .associate { field ->
                field.id to (
                    overrides[field.id]
                        ?: field.defaultValue
                        ?: ""
                    )
            },
    )

    fun value(id: String): String? = values[id]

    fun withValue(id: String, value: String): ModelParameterValues {
        if (id !in values) {
            return this
        }
        return copy(values = values + (id to value))
    }

    fun snapshot(): Map<String, String> = values.filter { (_, value) ->
        value.trim().isNotEmpty()
    }

    fun requestSnapshot(): Map<String, ModelRequestValue> {
        return snapshot().mapNotNull { (id, value) ->
            val definition = definitions[id] ?: return@mapNotNull null
            id to requestValue(definition, value)
        }.toMap()
    }

    fun requestBody(): Map<String, Any?> = requestSnapshot().mapValues { (_, value) -> value.asWire() }

    private fun requestValue(
        definition: ModelFieldDefinition,
        value: String,
    ): ModelRequestValue = when (definition.kind) {
        ModelFieldKind.Number ->
            value.toIntOrNull()
                ?.let { ModelRequestValue.Integer(it) }
                ?: ModelRequestValue.Text(value)

        ModelFieldKind.Float ->
            value.toDoubleOrNull()
                ?.let { ModelRequestValue.Decimal(it) }
                ?: ModelRequestValue.Text(value)

        ModelFieldKind.Text,
        ModelFieldKind.Textarea,
        ModelFieldKind.Select,
        -> ModelRequestValue.Text(value)
    }
}
