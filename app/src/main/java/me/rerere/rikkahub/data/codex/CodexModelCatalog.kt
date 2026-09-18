package me.rerere.rikkahub.data.codex

import kotlinx.serialization.json.*
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility

internal fun parseCodexModels(payload: JsonElement): List<Model> {
    val models = (payload as? JsonObject)
        ?.get("models") as? JsonArray
        ?: return emptyList()
    return models.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (item["visibility"]?.jsonPrimitive?.contentOrNull == "hide") {
            return@mapNotNull null
        }
        val slug = item["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val modalities = item["input_modalities"]?.jsonArray
            ?.mapNotNull { modality ->
                when ((modality as? JsonPrimitive)?.contentOrNull) {
                    "text" -> Modality.TEXT
                    "image" -> Modality.IMAGE
                    else -> null
                }
            }
            ?.ifEmpty { listOf(Modality.TEXT) }
            ?: listOf(Modality.TEXT, Modality.IMAGE)
        Model(
            modelId = slug,
            displayName = item["display_name"]?.jsonPrimitive?.contentOrNull ?: slug,
            inputModalities = modalities,
            codexReasoningEfforts = item["supported_reasoning_levels"]?.jsonArray?.mapNotNull { level ->
                (level as? JsonObject)?.get("effort")?.jsonPrimitive?.contentOrNull
                    ?: (level as? JsonPrimitive)?.contentOrNull
            }.orEmpty(),
            abilities = buildList {
                add(ModelAbility.TOOL)
                if (
                    item["supported_reasoning_levels"]?.jsonArray?.isNotEmpty() == true ||
                    item["supports_reasoning_summaries"]?.jsonPrimitive?.booleanOrNull == true
                ) {
                    add(ModelAbility.REASONING)
                }
            },
        )
    }
}
