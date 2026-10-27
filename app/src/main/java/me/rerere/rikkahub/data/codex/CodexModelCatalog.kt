package me.rerere.rikkahub.data.codex

import kotlinx.serialization.json.*
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelCapabilityMode
import me.rerere.ai.provider.ProviderSetting

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
            capabilityMode = ModelCapabilityMode.AUTO,
            capabilitySource = "codex",
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


/** Merge only opt-in native capabilities; local IDs, manual settings and overrides are retained. */
internal fun mergeCodexModelMetadata(
    provider: ProviderSetting,
    discovered: List<Model>,
    addMissing: Boolean = false,
): List<Model> {
    val byId = discovered.groupBy { it.modelId }.filterValues { it.size == 1 }.mapValues { it.value.single() }
    val saved = provider.models.map { model ->
        val remote = byId[model.modelId]
        if (remote == null || model.capabilityMode != ModelCapabilityMode.AUTO ||
            (model.providerOverwrite ?: provider) !is ProviderSetting.Codex
        ) model else model.copy(
            inputModalities = remote.inputModalities,
            outputModalities = remote.outputModalities,
            abilities = remote.abilities,
            codexReasoningEfforts = remote.codexReasoningEfforts,
            capabilitySource = "codex",
        )
    }
    return if (addMissing && provider is ProviderSetting.Codex) saved + byId.values.filter { remote ->
        saved.none { it.modelId == remote.modelId }
    }.map { it.copy(capabilityMode = ModelCapabilityMode.AUTO, capabilitySource = "codex") } else saved
}
