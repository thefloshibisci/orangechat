package me.rerere.ai

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelCapabilityMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelCapabilityCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityCatalogTest {
    private val metadata = """{"modalities":{"input":["text","image"],"output":["text"]},"tool_call":true,"reasoning":true}"""
    private fun catalog(models: String = "\"new-model\":$metadata") = ModelCapabilityCatalog.parse(
        """{"openai":{"api":"https://api.openai.com/v1","models":{$models}}}""",
    )
    private fun auto(id: String = "new-model") = Model(modelId = id, capabilityMode = ModelCapabilityMode.AUTO)

    @Test fun `exact identifier applies all supported capability fields`() {
        val original = auto()
        val provider = ProviderSetting.OpenAI(models = listOf(original))
        val updated = catalog().refresh(provider).models.single()
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), updated.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), updated.abilities)
        assertEquals(original.id, updated.id)
        assertEquals("models.dev:openai/new-model", updated.capabilitySource)
    }

    @Test fun `manual and legacy saved model configurations are preserved`() {
        for (mode in listOf(ModelCapabilityMode.MANUAL, ModelCapabilityMode.LEGACY)) {
            val provider = ProviderSetting.OpenAI(models = listOf(auto().copy(capabilityMode = mode)))
            assertSame(provider, catalog().refresh(provider))
        }
    }

    @Test fun `older serialized models remain opt in`() {
        val old = Json.decodeFromString<Model>("""{"modelId":"new-model","abilities":["TOOL"]}""")
        assertEquals(ModelCapabilityMode.LEGACY, old.capabilityMode)
        assertEquals(listOf(ModelAbility.TOOL), old.abilities)
    }

    @Test fun `missing and alias models keep saved offline capabilities`() {
        val original = auto("prefix/new-model").copy(inputModalities = listOf(Modality.IMAGE))
        val provider = ProviderSetting.OpenAI(models = listOf(original))
        assertSame(provider, catalog().refresh(provider))
    }

    @Test fun `unknown fields remain unknown and do not disable saved features`() {
        val original = auto().copy(abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))
        val updated = catalog(""""new-model":{"tool_call":false,"extra":"ignored"}""")
            .refresh(ProviderSetting.OpenAI(models = listOf(original))).models.single()
        assertEquals(listOf(ModelAbility.REASONING), updated.abilities)
        assertEquals(original.inputModalities, updated.inputModalities)
    }

    @Test fun `known false metadata removes formerly supported features`() {
        val original = auto().copy(
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        )
        val updated = catalog(""""new-model":{"tool_call":false,"reasoning":false,"modalities":{"input":["text"]}}""")
            .refresh(ProviderSetting.OpenAI(models = listOf(original))).models.single()
        assertTrue(updated.abilities.isEmpty())
        assertEquals(listOf(Modality.TEXT), updated.inputModalities)
    }

    @Test fun `malformed boolean strings do not disable capabilities`() {
        val original = auto().copy(abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))
        val updated = catalog(""""new-model":{"tool_call":"false","reasoning":"false","modalities":{"input":["text"]}}""")
            .refresh(ProviderSetting.OpenAI(models = listOf(original))).models.single()
        assertEquals(original.abilities, updated.abilities)
    }

    @Test fun `ambiguous model at unknown endpoint does not guess a provider`() {
        val data = """{"openai":{"models":{"new-model":$metadata}},"other":{"api":"https://other.test/v1","models":{"new-model":{"tool_call":false}}}}"""
        assertNull(ModelCapabilityCatalog.parse(data).resolve(ProviderSetting.OpenAI(baseUrl = "https://proxy.test/v1"), auto()))
    }

    @Test fun `native provider identity wins over a conflicting reseller`() {
        val data = """{"openai":{"models":{"new-model":$metadata}},"other":{"models":{"new-model":{"tool_call":false}}}}"""
        assertEquals(true, ModelCapabilityCatalog.parse(data).resolve(ProviderSetting.OpenAI(), auto())?.first?.tools)
    }

    @Test fun `unknown endpoint can use unanimous exact metadata`() {
        val data = """{"a":{"models":{"new-model":$metadata}},"b":{"models":{"new-model":$metadata}}}"""
        assertEquals(true, ModelCapabilityCatalog.parse(data).resolve(ProviderSetting.OpenAI(baseUrl = "https://proxy.test/v1"), auto())?.first?.tools)
    }

    @Test fun `matched provider missing model does not borrow conflicting provider data`() {
        val data = """{"openai":{"models":{"other":$metadata}},"reseller":{"models":{"new-model":$metadata}}}"""
        assertNull(ModelCapabilityCatalog.parse(data).resolve(ProviderSetting.OpenAI(), auto()))
    }

    @Test fun `native endpoint never borrows metadata when native provider is absent`() {
        val data = """{"reseller":{"models":{"new-model":$metadata}}}"""
        assertNull(ModelCapabilityCatalog.parse(data).resolve(ProviderSetting.OpenAI(), auto()))
    }

    @Test fun `per model provider override determines metadata source`() {
        val data = """{"openai":{"models":{"new-model":$metadata}},"other":{"api":"https://other.test/v1","models":{"new-model":{"tool_call":false}}}}"""
        val model = auto().copy(providerOverwrite = ProviderSetting.OpenAI(baseUrl = "https://other.test/v1"))
        assertEquals(false, ModelCapabilityCatalog.parse(data).resolve(ProviderSetting.OpenAI(), model)?.first?.tools)
    }

    @Test fun `codex authenticated metadata is never overwritten by public catalog`() {
        val provider = ProviderSetting.Codex(models = listOf(auto().copy(codexReasoningEfforts = listOf("high"))))
        assertSame(provider, catalog().refresh(provider))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty or malformed metadata cannot replace the last valid catalog`() {
        ModelCapabilityCatalog.parse("""{"openai":{"models":{"new-model":null}}}""")
    }
}
