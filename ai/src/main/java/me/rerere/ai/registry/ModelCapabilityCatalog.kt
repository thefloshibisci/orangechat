package me.rerere.ai.registry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelCapabilityMode
import me.rerere.ai.provider.ProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only fields supported by our transports are applied; an omitted field is unknown. */
data class CatalogCapabilities(
    val input: List<Modality>?,
    val output: List<Modality>?,
    val tools: Boolean?,
    val reasoning: Boolean?,
) {
    fun applyTo(model: Model, source: String): Model = model.copy(
        inputModalities = input ?: model.inputModalities,
        outputModalities = output ?: model.outputModalities,
        abilities = buildList {
            if (tools ?: (ModelAbility.TOOL in model.abilities)) add(ModelAbility.TOOL)
            if (reasoning ?: (ModelAbility.REASONING in model.abilities)) add(ModelAbility.REASONING)
        },
        capabilitySource = source,
    )
}

class ModelCapabilityCatalog private constructor(private val providers: List<CatalogProvider>) {
    private data class CatalogProvider(
        val id: String,
        val host: String?,
        val models: Map<String, CatalogCapabilities>,
    )

    /** Exact identifiers only. Unknown endpoints may use a consensus, never a guessed alias. */
    fun resolve(provider: ProviderSetting, model: Model): Pair<CatalogCapabilities, String>? {
        val effective = model.providerOverwrite ?: provider
        if (effective is ProviderSetting.Codex) return null // Codex uses authenticated provider metadata.
        val host = when (effective) {
            is ProviderSetting.OpenAI -> effective.baseUrl
            is ProviderSetting.Google -> effective.baseUrl
            is ProviderSetting.Claude -> effective.baseUrl
            is ProviderSetting.Codex -> return null
        }.toHttpUrlOrNull()?.host
        val nativeProviderId = when (host) {
            "api.openai.com" -> "openai"
            "api.anthropic.com" -> "anthropic"
            "generativelanguage.googleapis.com" -> "google"
            else -> null
        }
        val hosts = providers.filter { (it.host != null && it.host == host) || it.id == nativeProviderId }
        val matchingProviders = if (nativeProviderId != null) hosts else hosts.ifEmpty { providers }
        val candidates = matchingProviders.mapNotNull { entry ->
            entry.models[model.modelId]?.let { Triple(entry.id, model.modelId, it) }
        }
        if (candidates.isEmpty() || candidates.map { it.third }.distinct().size != 1) return null
        val selected = candidates.first()
        return selected.third to "models.dev:${selected.first}/${selected.second}"
    }

    fun refresh(provider: ProviderSetting): ProviderSetting {
        val models = provider.models.map { model ->
            if (model.capabilityMode != ModelCapabilityMode.AUTO) model
            else resolve(provider, model)?.let { (capabilities, source) -> capabilities.applyTo(model, source) } ?: model
        }
        return if (models == provider.models) provider else provider.copyProvider(models = models)
    }

    companion object {
        /** https://github.com/anomalyco/models.dev#api-reference (v1 api.json). */
        fun parse(text: String): ModelCapabilityCatalog {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Expected model catalog object")
            val providers = root.mapNotNull { (providerId, raw) ->
                if (providerId.length > 256) return@mapNotNull null
                val entry = raw as? JsonObject ?: return@mapNotNull null
                val models = entry["models"] as? JsonObject ?: return@mapNotNull null
                CatalogProvider(
                    id = providerId,
                    host = (entry["api"] as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull()?.host,
                    models = models.mapNotNull modelEntry@{ (modelId, rawModel) ->
                        if (modelId.length > 512) return@modelEntry null
                        val fields = rawModel as? JsonObject ?: return@modelEntry null
                        val modalities = fields["modalities"] as? JsonObject
                        val capabilities = CatalogCapabilities(
                            input = modalities?.get("input").modalities(),
                            output = modalities?.get("output").modalities(),
                            tools = (fields["tool_call"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
                            reasoning = (fields["reasoning"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
                        )
                        if (capabilities == CatalogCapabilities(null, null, null, null)) null
                        else modelId to capabilities
                    }.toMap(),
                )
            }
            require(providers.any { it.models.isNotEmpty() }) { "Empty model capability catalog" }
            return ModelCapabilityCatalog(providers)
        }

        private fun kotlinx.serialization.json.JsonElement?.modalities(): List<Modality>? {
            val array = this as? JsonArray ?: return null
            if (array.any { it !is JsonPrimitive || it.contentOrNull == null }) return null
            return array.mapNotNull {
                when ((it as JsonPrimitive).contentOrNull) {
                    "text" -> Modality.TEXT
                    "image" -> Modality.IMAGE
                    else -> null
                }
            }.distinct().takeIf { it.isNotEmpty() }
        }
    }
}
