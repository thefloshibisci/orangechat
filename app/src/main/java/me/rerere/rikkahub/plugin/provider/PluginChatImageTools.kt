package me.rerere.rikkahub.plugin.provider

import kotlinx.serialization.json.*
import me.rerere.rikkahub.plugin.model.PluginToolDefinition

internal val IMAGE_VALUE_SOURCES = setOf("chat_image_base64", "chat_image_mime")

private val VALID_JSON_SCHEMA_TYPES = setOf("string", "number", "integer", "boolean", "array", "object")

private fun normalizeJsonSchemaType(rawType: String): String = when (val t = rawType.trim().lowercase()) {
    "int" -> "integer"
    "float", "double" -> "number"
    "bool" -> "boolean"
    in VALID_JSON_SCHEMA_TYPES -> t
    else -> "string"
}

/** The selector belongs to the host even when an older plugin omitted it in its manifest. */
internal fun pluginToolParameters(tool: PluginToolDefinition): JsonObject = buildJsonObject {
    tool.parameters.filter { it.source == null }.forEach { param ->
        val normalizedType = normalizeJsonSchemaType(param.type)
        put(param.name, buildJsonObject {
            put("type", normalizedType)
            param.description?.let { put("description", it) }
            if (normalizedType == "array") putJsonObject("items") { put("type", "string") }
            if (normalizedType == "object") putJsonObject("properties") {}
        })
    }
    if (tool.parameters.any { it.source in IMAGE_VALUE_SOURCES }) {
        putJsonObject("attachment_id") {
            put("type", "string")
            put("description", "从 chat_images 返回的当前分支图片编号；多张图片时必传。先预览再选图，不可用名称、顺序或路径代替编号。")
        }
    }
}
