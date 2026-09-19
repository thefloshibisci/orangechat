package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory

internal fun buildProactiveMemorySearchTool(read: suspend () -> List<AssistantMemory>): Tool = Tool(
    name = "search_local_memory",
    description = "Read-only keyword search of this assistant's enabled local memories. Does not write or delete anything.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
            },
            required = listOf("query"),
        )
    },
    execute = { args ->
        val query = args.jsonObject["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(query.isNotBlank() && query.length <= 200) { "query must contain 1 to 200 characters" }
        val matches = read().filter { it.content.contains(query, ignoreCase = true) }.take(8)
        listOf(UIMessagePart.Text(matches.joinToString("\n") { "${it.id}: ${it.content.take(800)}" }
            .ifBlank { "No matching local memories were found." }))
    },
)
