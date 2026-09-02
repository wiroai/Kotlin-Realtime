package ai.wiro.realtime.models

enum class RealtimeDirection {
    SpeechToSpeech,
    SpeechToText,
    TextToSpeech,
}

enum class ModelFieldKind {
    Text,
    Textarea,
    Select,
    Number,
    Float,
}

data class ModelFieldOption(
    val value: String,
)

data class ModelFieldDefinition(
    val id: String,
    val label: String,
    val kind: ModelFieldKind,
    val isRequired: Boolean,
    val includeInFirstPass: Boolean,
    val options: List<ModelFieldOption> = emptyList(),
    val defaultValue: String? = null,
)

data class ModelDefinition(
    val owner: String,
    val slug: String,
    val displayName: String,
    val direction: RealtimeDirection,
    val fields: List<ModelFieldDefinition>,
) {
    val modelSlug: String
        get() = "$owner/$slug"

    val firstPassFields: List<ModelFieldDefinition>
        get() = fields.filter { it.includeInFirstPass }
}
