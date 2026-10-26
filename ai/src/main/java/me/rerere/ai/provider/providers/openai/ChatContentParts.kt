package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.openAiAudioFormat

/** Encode first, then decide whether a message has content: UI parts may not survive modality filtering. */
internal fun encodeChatContentParts(
    parts: List<UIMessagePart>,
    supportsImage: Boolean,
    allowAudio: Boolean,
): JsonArray = buildJsonArray {
    parts.forEach { part ->
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) {
                add(chatTextPart(part.text))
            }
            is UIMessagePart.Image -> if (supportsImage && part.url.isNotBlank()) {
                // Missing historical attachments must never turn into an invalid {type:text,text:""} block.
                // Retain the position of the attachment without pretending the model received its pixels.
                val encoded = part.encodeBase64().getOrNull()
                add(if (encoded != null) buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", encoded.base64) })
                } else chatTextPart("[Image unavailable: the attachment could not be read.]"))
            }
            is UIMessagePart.Audio -> if (part.url.isNotBlank()) {
                require(allowAudio) { "录音只能作为用户消息发送" }
                add(buildJsonObject {
                    put("type", "input_audio")
                    putJsonObject("input_audio") {
                        put("format", part.openAiAudioFormat())
                        put("data", part.encodeBase64(false).getOrThrow())
                    }
                })
            }
            else -> Unit
        }
    }
}

private fun chatTextPart(text: String) = buildJsonObject {
    put("type", "text")
    put("text", text)
}

internal fun encodeChatMessageContent(parts: JsonArray): JsonElement {
    if (parts.isEmpty()) return kotlinx.serialization.json.JsonPrimitive("")
    val single = parts.singleOrNull() as? JsonObject
    return if (single?.get("type")?.jsonPrimitive?.content == "text") {
        single.getValue("text")
    } else parts
}
