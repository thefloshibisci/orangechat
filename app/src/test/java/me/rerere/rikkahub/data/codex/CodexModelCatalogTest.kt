package me.rerere.rikkahub.data.codex

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelCapabilityMode
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class CodexModelCatalogTest {
    @Test fun `catalog preserves discovered models and effort metadata without hardcoded fallback`() {
        val models = parseCodexModels(Json.parseToJsonElement("""{"models":[
            {"slug":"gpt-6-astra","visibility":"list","supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}]},
            {"slug":"future-model","supported_reasoning_levels":["medium"]},
            {"slug":"hidden-model","visibility":"hide"}
        ]}"""))
        assertEquals(listOf("gpt-6-astra", "future-model"), models.map { it.modelId })
        assertEquals(listOf("low", "high"), models.first().codexReasoningEfforts)
        assertTrue(models.all { ModelAbility.TOOL in it.abilities && ModelAbility.REASONING in it.abilities })
        assertTrue(parseCodexModels(Json.parseToJsonElement("{} ")).isEmpty())
    }

    @Test fun `discovered Codex models opt into their authenticated metadata`() {
        val model = parseCodexModels(Json.parseToJsonElement("""{"models":[{"slug":"native"}]}""")).single()
        assertEquals(ModelCapabilityMode.AUTO, model.capabilityMode)
        assertEquals("codex", model.capabilitySource)
    }

    @Test fun `native refresh preserves local configuration and manually maintained models`() {
        val automatic = Model(modelId = "native", displayName = "my label", capabilityMode = ModelCapabilityMode.AUTO)
        val manual = automatic.copy(modelId = "manual", capabilityMode = ModelCapabilityMode.MANUAL)
        val legacy = automatic.copy(modelId = "legacy", capabilityMode = ModelCapabilityMode.LEGACY)
        val missing = automatic.copy(modelId = "missing")
        val remote = listOf(automatic, manual, legacy).map { model -> model.copy(
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE), abilities = listOf(ModelAbility.REASONING),
            displayName = "remote name", codexReasoningEfforts = listOf("low", "high"),
        ) }
        val saved = mergeCodexModelMetadata(ProviderSetting.Codex(models = listOf(automatic, manual, legacy, missing)), remote)
        assertEquals(automatic.id, saved.first().id)
        assertEquals("my label", saved.first().displayName)
        assertEquals(remote.first().inputModalities, saved.first().inputModalities)
        assertEquals(listOf("low", "high"), saved.first().codexReasoningEfforts)
        assertEquals(listOf(manual, legacy, missing), saved.drop(1))
    }

    @Test fun `native overrides update and nonnative overrides remain untouched`() {
        val nativeOverride = Model(modelId = "native", capabilityMode = ModelCapabilityMode.AUTO,
            providerOverwrite = ProviderSetting.Codex())
        val otherOverride = nativeOverride.copy(providerOverwrite = ProviderSetting.OpenAI())
        val remote = listOf(Model(modelId = "native", abilities = listOf(ModelAbility.TOOL)))
        assertEquals(listOf(ModelAbility.TOOL), mergeCodexModelMetadata(
            ProviderSetting.OpenAI(models = listOf(nativeOverride)), remote,
        ).single().abilities)
        assertEquals(otherOverride, mergeCodexModelMetadata(
            ProviderSetting.Codex(models = listOf(otherOverride)), remote,
        ).single())
    }

    @Test fun `empty or ambiguous native lists preserve existing models and missing additions require explicit opt in`() {
        val saved = Model(modelId = "native", capabilityMode = ModelCapabilityMode.AUTO)
        val provider = ProviderSetting.Codex(models = listOf(saved))
        assertEquals(provider.models, mergeCodexModelMetadata(provider, emptyList()))
        assertEquals(provider.models, mergeCodexModelMetadata(provider, listOf(saved, saved.copy())))
        val newModel = Model(modelId = "new")
        assertEquals(provider.models, mergeCodexModelMetadata(provider, listOf(newModel)))
        val added = mergeCodexModelMetadata(provider, listOf(newModel), addMissing = true).last()
        assertEquals(ModelCapabilityMode.AUTO, added.capabilityMode)
        assertEquals("codex", added.capabilitySource)
    }
}
