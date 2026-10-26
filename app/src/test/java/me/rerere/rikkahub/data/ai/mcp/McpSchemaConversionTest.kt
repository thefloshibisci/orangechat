/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Test

class McpSchemaConversionTest {
    @Test
    fun `preserves defs referenced by MCP tool properties`() {
        val definitions = buildJsonObject {
            put("Draft", buildJsonObject { put("type", "object") })
        }
        val toolSchema = ToolSchema(
            properties = buildJsonObject {
                put("draft", buildJsonObject { put("\$ref", "#/\$defs/Draft") })
            },
            required = listOf("draft"),
            defs = definitions,
        )

        val converted = toolSchema.toSchema() as InputSchema.Obj

        assertEquals(definitions, converted.defs)
        assertEquals(listOf("draft"), converted.required)
        assertEquals(
            "#/\$defs/Draft",
            converted.properties["draft"]
                ?.let { it as JsonObject }
                ?.get("\$ref")
                ?.toString()
                ?.trim('"')
        )
    }
}
