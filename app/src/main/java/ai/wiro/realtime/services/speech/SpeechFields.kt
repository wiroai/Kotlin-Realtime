package ai.wiro.realtime.services.speech

import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelFieldDefinition
import ai.wiro.realtime.models.WiroModelCatalog

object SpeechFields {
    val models: List<ModelDefinition> = listOf(
        WiroModelCatalog.mossTtsRealtime,
        WiroModelCatalog.voxcpm2,
    )

    fun promptFieldId(model: ModelDefinition): String = if (
        model.modelSlug == WiroModelCatalog.mossTtsRealtime.modelSlug
    ) {
        "text"
    } else {
        "prompt"
    }

    fun settingsFields(
        model: ModelDefinition,
    ): List<ModelFieldDefinition> {
        val promptId = promptFieldId(model)
        return model.firstPassFields.filter { it.id != promptId }
    }
}
