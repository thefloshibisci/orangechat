/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSchemaTest {
    @Test
    fun `nested references keep the target property instead of its parent object`() {
        val schema = json.parseToJsonElement("""{
            "properties":{"mode":{"${'$'}ref":"#/${'$'}defs/Options/properties/mode"}},
            "${'$'}defs":{"Options":{"type":"object","properties":{"mode":{"const":"list"}}}}
        }""").toGeminiFunctionParameters()
        val mode = schema["properties"]!!.jsonObject["mode"]!!.jsonObject
        assertEquals("string", mode["type"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("list"))), mode["enum"])
    }

    @Test
    fun `recursive refs terminate and invalid numeric limits are discarded`() {
        val schema = json.parseToJsonElement("""{
            "properties":{
                "node":{"${'$'}ref":"#/${'$'}defs/Node"},
                "list":{"type":"array","minItems":-1,"maxItems":true},
                "number":{"type":"number","minimum":"bad","maximum":10}
            },
            "${'$'}defs":{"Node":{"type":"object","properties":{"next":{"${'$'}ref":"#/${'$'}defs/Node"}}}}
        }""").toGeminiFunctionParameters()
        val props = schema["properties"]!!.jsonObject
        assertFalse(containsKeyRecursively(schema, "${'$'}ref"))
        assertFalse("minItems" in props["list"]!!.jsonObject)
        assertFalse("maxItems" in props["list"]!!.jsonObject)
        assertFalse("minimum" in props["number"]!!.jsonObject)
        assertEquals(JsonPrimitive(10), props["number"]!!.jsonObject["maximum"])
    }
    @Test
    fun `preserves reaction choices and operations while stripping metadata`() {
        val reactions = JsonArray(listOf(JsonPrimitive("(ᵕ—ᴗ—)"), JsonPrimitive("(≧▽≦)")))
        val operations = JsonArray(listOf(JsonPrimitive("add"), JsonPrimitive("remove")))
        val schema = buildJsonObject {
            put("properties", buildJsonObject {
                put("emoji", buildJsonObject {
                    put("type", "string")
                    put("title", "Reaction")
                    put("enum", reactions)
                })
                put("operation", buildJsonObject {
                    put("type", "string")
                    put("enum", operations)
                })
            })
        }.toGeminiFunctionParameters()

        val properties = schema["properties"]!!.jsonObject
        assertEquals(reactions, properties["emoji"]!!.jsonObject["enum"])
        assertEquals(operations, properties["operation"]!!.jsonObject["enum"])
        assertFalse("title" in properties["emoji"]!!.jsonObject)
    }

    @Test
    fun `retains referenced nullable string enums in array items`() {
        val choices = JsonArray(listOf(JsonPrimitive("list"), JsonPrimitive("add"), JsonPrimitive("remove")))
        val schema = buildJsonObject {
            put("properties", buildJsonObject {
                put("operations", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("\$ref", "#/\$defs/Operation") })
                })
            })
            put("\$defs", buildJsonObject {
                put("Operation", buildJsonObject {
                    put("anyOf", buildJsonArray {
                        add(buildJsonObject { put("type", "null") })
                        add(buildJsonObject {
                            put("type", "string")
                            put("enum", choices)
                        })
                    })
                })
            })
        }.toGeminiFunctionParameters()

        val items = schema["properties"]!!.jsonObject["operations"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(choices, items["enum"])
        assertEquals("string", items["type"]!!.jsonPrimitive.content)
        assertFalse(containsKeyRecursively(schema, "\$ref"))
    }

    @Test
    fun `removes unsupported metadata and adds missing nullable type`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("title", "Root title")
            put("properties", buildJsonObject {
                put("script", buildJsonObject {
                    put("title", "Script title")
                    put("anyOf", buildJsonArray {
                        add(buildJsonObject { put("type", "string") })
                        add(buildJsonObject { put("type", "null") })
                    })
                })
            })
        }.toGeminiFunctionParameters()

        val script = schema["properties"]!!.jsonObject["script"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals("string", script["type"]!!.jsonPrimitive.content)
        assertFalse(containsKeyRecursively(schema, "title"))
        assertFalse(containsKeyRecursively(schema, "anyOf"))
    }

    @Test
    fun `inlines defs before removing reference fields`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("draft", buildJsonObject { put("\$ref", "#/\$defs/Draft") })
            })
            put("required", buildJsonArray { add(JsonPrimitive("draft")) })
            put("\$defs", buildJsonObject {
                put("Draft", buildJsonObject {
                    put("type", "object")
                    put("title", "Draft")
                    put("properties", buildJsonObject {
                        put("identifier", buildJsonObject {
                            put("title", "Identifier")
                            put("type", buildJsonArray {
                                add(JsonPrimitive("string"))
                                add(JsonPrimitive("null"))
                            })
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("identifier")) })
                })
            })
        }.toGeminiFunctionParameters()

        val draft = schema["properties"]!!.jsonObject["draft"]!!.jsonObject
        val identifier = draft["properties"]!!.jsonObject["identifier"]!!.jsonObject
        assertEquals("object", draft["type"]!!.jsonPrimitive.content)
        assertEquals("string", identifier["type"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("identifier"))), draft["required"])
        assertFalse(containsKeyRecursively(schema, "\$ref"))
        assertFalse(containsKeyRecursively(schema, "\$defs"))
        assertFalse(containsKeyRecursively(schema, "title"))
    }

    @Test
    fun `emits a typed schema for untyped MCP properties`() {
        val schema = buildJsonObject {
            put("properties", buildJsonObject {
                put("kind", buildJsonObject {})
                put("revision", buildJsonObject { put("default", 0) })
                put("items", buildJsonObject {
                    put("items", buildJsonObject {})
                })
            })
        }.toGeminiFunctionParameters()

        assertTrue(allSchemaNodesHaveType(schema))
        val properties = schema["properties"]!!.jsonObject
        assertEquals("string", properties["kind"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", properties["revision"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("array", properties["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    private fun containsKeyRecursively(element: kotlinx.serialization.json.JsonElement, key: String): Boolean {
        return when (element) {
            is JsonObject -> key in element || element.values.any { containsKeyRecursively(it, key) }
            is JsonArray -> element.any { containsKeyRecursively(it, key) }
            else -> false
        }
    }

    private fun allSchemaNodesHaveType(schema: JsonObject): Boolean {
        if (schema["type"] !is JsonPrimitive) return false
        val properties = schema["properties"] as? JsonObject
        if (properties != null && properties.values.any { !allSchemaNodesHaveType(it.jsonObject) }) return false
        val items = schema["items"] as? JsonObject
        return items == null || allSchemaNodesHaveType(items)
    }
}
