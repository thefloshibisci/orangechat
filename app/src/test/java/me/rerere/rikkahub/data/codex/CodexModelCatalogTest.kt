package me.rerere.rikkahub.data.codex

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ModelAbility
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
}
