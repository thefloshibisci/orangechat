package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.*
import org.junit.Test

class ProactiveMemoryToolsTest {
    @Test fun `search is read only scoped and bounded`() = runBlocking {
        var reads = 0
        val tool = buildProactiveMemorySearchTool {
            reads++
            (1..20).map { AssistantMemory(it, "Study ${"x".repeat(1000)}") }
        }
        assertEquals(0, reads)
        val output = tool.execute(buildJsonObject { put("query", "study") })
            .filterIsInstance<UIMessagePart.Text>().single().text
        assertEquals(1, reads)
        assertEquals(8, output.lines().size)
        assertTrue(output.length < 6500)
    }

    @Test fun `blank query never reads the store`() = runBlocking {
        val tool = buildProactiveMemorySearchTool { error("must not read") }
        val result = runCatching { tool.execute(buildJsonObject { put("query", " ") }) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `no match is explicit`() = runBlocking {
        val tool = buildProactiveMemorySearchTool { listOf(AssistantMemory(1, "study")) }
        val output = tool.execute(buildJsonObject { put("query", "sleep") })
            .filterIsInstance<UIMessagePart.Text>().single().text
        assertEquals("No matching local memories were found.", output)
    }
}
