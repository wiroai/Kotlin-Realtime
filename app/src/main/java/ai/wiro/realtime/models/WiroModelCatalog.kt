package ai.wiro.realtime.models

/**
 * Model parameter table from Wiro MCP `get_model_schema` on 2026-08-31.
 *
 * Field ids and options match the live schemas. Catalog defaults that
 * the schema omits are the values that keep the local PCM pipeline
 * aligned with the documented wire format, or that Swift-Realtime
 * already ships as a working first pass.
 *
 * `openbmb/voxcpm2` defaults for `cfgValue` and `inferenceSteps` still
 * come from the live model page; MCP listed the fields without
 * defaults. `inputAudio` / `inputAudio2` are `combinefileinput` on
 * the schema and stay out of the first pass until cloning is added.
 */
object WiroModelCatalog {
    val gptRealtimeMini = ModelDefinition(
        owner = "openai",
        slug = "gpt-realtime-mini",
        displayName = "GPT Realtime Mini",
        direction = RealtimeDirection.SpeechToSpeech,
        fields = listOf(
            ModelFieldDefinition(
                id = "voice",
                label = "Voice",
                kind = ModelFieldKind.Select,
                isRequired = true,
                includeInFirstPass = true,
                options = options(
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
                defaultValue = "marin",
            ),
            // Persona lives in ConversationConfig. The schema requires
            // the field; it does not fix the text.
            ModelFieldDefinition(
                id = "system_instructions",
                label = "System instructions",
                kind = ModelFieldKind.Textarea,
                isRequired = true,
                includeInFirstPass = true,
            ),
            ModelFieldDefinition(
                id = "transcription_model",
                label = "Transcription model",
                kind = ModelFieldKind.Select,
                isRequired = false,
                includeInFirstPass = true,
                options = options(
                    "gpt-4o-mini-transcribe",
                    "gpt-4o-transcribe",
                    "whisper-1",
                ),
                defaultValue = "gpt-4o-mini-transcribe",
            ),
            ModelFieldDefinition(
                id = "input_audio_format",
                label = "Input audio format",
                kind = ModelFieldKind.Select,
                isRequired = false,
                includeInFirstPass = true,
                options = options(
                    "audio/pcm",
                    "audio/pcmu",
                    "audio/pcma",
                ),
                defaultValue = "audio/pcm",
            ),
            ModelFieldDefinition(
                id = "output_audio_format",
                label = "Output audio format",
                kind = ModelFieldKind.Select,
                isRequired = false,
                includeInFirstPass = true,
                options = options(
                    "audio/pcm",
                    "audio/pcmu",
                    "audio/pcma",
                ),
                defaultValue = "audio/pcm",
            ),
            ModelFieldDefinition(
                id = "input_audio_rate",
                label = "Input audio rate",
                kind = ModelFieldKind.Select,
                isRequired = false,
                includeInFirstPass = true,
                options = options("24000", "8000"),
                defaultValue = "24000",
            ),
            ModelFieldDefinition(
                id = "output_audio_rate",
                label = "Output audio rate",
                kind = ModelFieldKind.Select,
                isRequired = false,
                includeInFirstPass = true,
                options = options("24000", "8000"),
                defaultValue = "24000",
            ),
            ModelFieldDefinition(
                id = "turn_detection_threshold",
                label = "Turn detection threshold",
                kind = ModelFieldKind.Float,
                isRequired = false,
                includeInFirstPass = true,
                defaultValue = "0.75",
            ),
            // Schema copy now says 500 ms. 700 ms is the
            // noise-resistant first pass Swift already ships.
            ModelFieldDefinition(
                id = "turn_detection_silence_ms",
                label = "Silence duration (ms)",
                kind = ModelFieldKind.Number,
                isRequired = false,
                includeInFirstPass = true,
                defaultValue = "700",
            ),
        ),
    )

    val voxtralMiniRealtime = ModelDefinition(
        owner = "mistralai",
        slug = "voxtral-mini-4b-realtime-2602",
        displayName = "Voxtral Mini Realtime",
        direction = RealtimeDirection.SpeechToText,
        fields = listOf(
            ModelFieldDefinition(
                id = "transcriptionDelay",
                label = "Transcription delay",
                kind = ModelFieldKind.Select,
                isRequired = false,
                includeInFirstPass = true,
                options = options(
                    "fastest",
                    "fast",
                    "balanced",
                    "accurate",
                ),
                defaultValue = "balanced",
            ),
        ),
    )

    val mossTtsRealtime = ModelDefinition(
        owner = "openmoss",
        slug = "moss-tts-realtime",
        displayName = "MOSS TTS Realtime",
        direction = RealtimeDirection.TextToSpeech,
        fields = listOf(
            ModelFieldDefinition(
                id = "text",
                label = "Text",
                kind = ModelFieldKind.Textarea,
                isRequired = false,
                includeInFirstPass = true,
            ),
            ModelFieldDefinition(
                id = "inputAudio",
                label = "Reference audio",
                kind = ModelFieldKind.Text,
                isRequired = false,
                includeInFirstPass = false,
            ),
            ModelFieldDefinition(
                id = "temperature",
                label = "Temperature",
                kind = ModelFieldKind.Float,
                isRequired = false,
                includeInFirstPass = false,
            ),
            ModelFieldDefinition(
                id = "topP",
                label = "Top P",
                kind = ModelFieldKind.Float,
                isRequired = false,
                includeInFirstPass = false,
            ),
            ModelFieldDefinition(
                id = "topK",
                label = "Top K",
                kind = ModelFieldKind.Number,
                isRequired = false,
                includeInFirstPass = false,
            ),
            ModelFieldDefinition(
                id = "repetitionPenalty",
                label = "Repetition penalty",
                kind = ModelFieldKind.Float,
                isRequired = false,
                includeInFirstPass = false,
            ),
            ModelFieldDefinition(
                id = "repetitionWindow",
                label = "Repetition window",
                kind = ModelFieldKind.Number,
                isRequired = false,
                includeInFirstPass = false,
            ),
            ModelFieldDefinition(
                id = "maxLength",
                label = "Max length",
                kind = ModelFieldKind.Number,
                isRequired = false,
                includeInFirstPass = false,
            ),
        ),
    )

    val voxcpm2 = ModelDefinition(
        owner = "openbmb",
        slug = "voxcpm2",
        displayName = "VoxCPM2",
        direction = RealtimeDirection.TextToSpeech,
        fields = listOf(
            ModelFieldDefinition(
                id = "prompt",
                label = "Prompt",
                kind = ModelFieldKind.Textarea,
                isRequired = false,
                includeInFirstPass = true,
            ),
            ModelFieldDefinition(
                id = "inputAudio",
                label = "Reference audio",
                kind = ModelFieldKind.Text,
                isRequired = false,
                includeInFirstPass = false,
            ),
            ModelFieldDefinition(
                id = "inputAudio2",
                label = "Prompt audio",
                kind = ModelFieldKind.Text,
                isRequired = false,
                includeInFirstPass = false,
            ),
            ModelFieldDefinition(
                id = "promptAudioText",
                label = "Prompt audio text",
                kind = ModelFieldKind.Textarea,
                isRequired = false,
                includeInFirstPass = false,
            ),
            ModelFieldDefinition(
                id = "cfgValue",
                label = "CFG value",
                kind = ModelFieldKind.Float,
                isRequired = true,
                includeInFirstPass = true,
                defaultValue = "2.0",
            ),
            ModelFieldDefinition(
                id = "inferenceSteps",
                label = "Inference steps",
                kind = ModelFieldKind.Number,
                isRequired = false,
                includeInFirstPass = true,
                defaultValue = "10",
            ),
        ),
    )

    val all: List<ModelDefinition> = listOf(
        gptRealtimeMini,
        voxtralMiniRealtime,
        mossTtsRealtime,
        voxcpm2,
    )
}

private fun options(vararg values: String): List<ModelFieldOption> = values.map { ModelFieldOption(it) }
