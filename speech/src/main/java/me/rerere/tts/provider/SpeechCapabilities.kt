package me.rerere.tts.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val miniMaxModels = setOf(
    "speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-2.6-turbo",
    "speech-02-hd", "speech-02-turbo", "speech-01-hd", "speech-01-turbo",
)

/** Request-time capabilities. Never include credentials, URLs or a stale provider's instructions. */
data class SpeechCapabilities(val description: String, val parameters: JsonObject)

fun speechCapabilities(provider: TTSProviderSetting?): SpeechCapabilities {
    val description = when (provider) {
        null -> "未选择语音提供商，请先在语音设置中选择。"
        is TTSProviderSetting.MiniMax -> if (provider.model in miniMaxModels) {
            "当前提供商 MiniMax，模型 ${provider.model}。" +
                speechDeliveryPrompt(provider).substringAfter("避免Markdown。")
        } else "当前提供商 MiniMax，模型 ${provider.model}。该自定义模型仅支持普通文字，不提供未经核实的控制参数。"
        is TTSProviderSetting.ElevenLabs -> buildString {
            append("当前提供商 ElevenLabs，模型 ${provider.modelId}。可用 stability 和 similarity_boost（0~1），省略则沿用用户设置。")
            when (provider.modelId) {
                "eleven_v4" -> append("语气写在 text 内：例如 [whispers]轻声说话、[laughs]、[sighs]、[excited]、[sad]，")
                    .append("停顿用 [short pause]、[pause]、[long pause]，语速用 [rushed]、[slow]。标签按语境适量使用。")
                    .append("本模型不支持 speed/style 参数；不要使用 emotion/pitch/volume、[[voice]]、<#0.3#> 或 (laughs) 等 MiniMax 控制语法。")
                "eleven_v3" -> append("stability 只能选 0、0.5、1。语气写在 text 内，如 [whispers]、[laughs]、[sighs]、[excited]；")
                    .append("不要使用 MiniMax 控制语法或 emotion/pitch/volume。")
                else -> append("只使用普通文字；不要使用 MiniMax 或其他模型的语音标签。")
            }
        }
        else -> "当前提供商 ${provider::class.simpleName}。只支持普通文字，按用户配置的音色生成，不提供其他提供商的语音控制参数或标签。"
    }
    val parameters = buildJsonObject {
        put("text", buildJsonObject { put("type", "string"); put("description", description) })
        fun number(name: String, min: Double, max: Double, integer: Boolean = false) {
            put(name, buildJsonObject {
                put("type", if (integer) "integer" else "number")
                put("minimum", min); put("maximum", max)
            })
        }
        when (provider) {
            is TTSProviderSetting.MiniMax -> if (provider.model in miniMaxModels) {
                put("emotion", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        listOf("auto", "happy", "sad", "angry", "fearful", "disgusted", "surprised", "calm").forEach { add(it) }
                        if (provider.model in setOf("speech-2.6-hd", "speech-2.6-turbo")) {
                            add("fluent"); add("whisper")
                        }
                    })
                })
                number("speed", .5, 2.0); number("pitch", -12.0, 12.0, true); number("volume", .01, 10.0)
            }
            is TTSProviderSetting.ElevenLabs -> {
                if (provider.modelId == "eleven_v3") put("stability", buildJsonObject {
                    put("type", "number"); put("enum", buildJsonArray { add(0); add(.5); add(1) })
                }) else number("stability", 0.0, 1.0)
                number("similarity_boost", 0.0, 1.0)
            }
            else -> Unit
        }
    }
    return SpeechCapabilities(description, parameters)
}
