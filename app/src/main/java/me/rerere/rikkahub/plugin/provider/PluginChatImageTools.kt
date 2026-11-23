package me.rerere.rikkahub.plugin.provider

import kotlinx.serialization.json.*
import me.rerere.rikkahub.plugin.model.PluginToolDefinition

internal val IMAGE_VALUE_SOURCES = setOf("chat_image_base64", "chat_image_mime")

/** The selector belongs to the host even when an older plugin omitted it in its manifest. */
internal fun pluginToolParameters(tool: PluginToolDefinition): JsonObject = buildJsonObject {
    tool.parameters.filter { it.source == null }.forEach { param ->
        put(param.name, buildJsonObject {
            put("type", param.type)
            param.description?.let { put("description", it) }
            if (param.type == "array") putJsonObject("items") { put("type", "string") }
        })
    }
    if (tool.parameters.any { it.source in IMAGE_VALUE_SOURCES }) {
        putJsonObject("attachment_id") {
            put("type", "string")
            put("description", "从 chat_images 返回的当前分支图片编号；多张图片时必传。先预览再选图，不可用名称、顺序或路径代替编号。")
        }
    }
}
