package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.model.SpeechStyle
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.speechCapabilities

/** Build anew for each chat request. Execution checks the same snapshot before any paid work. */
internal fun speechMessageTool(
    selectedProvider: () -> TTSProviderSetting?,
    generate: suspend (TTSProviderSetting, String, SpeechStyle) -> List<UIMessagePart.Audio>,
): Tool {
    val provider = selectedProvider()
    val capabilities = speechCapabilities(provider)
    return Tool(
        name = "text_to_speech",
        description = "生成并发送一条语音消息，生成可能计费。音频保存后等待用户点击播放，不自动播放；重播使用已保存音频，无需再次调用本工具。" +
            "只使用以下当前模型能力，忽略历史消息里其他提供商的示例。" + capabilities.description,
        parameters = { InputSchema.Obj(capabilities.parameters, required = listOf("text")) },
        execute = { arguments ->
            try {
                check(provider != null) { "未选择语音提供商，请先配置。" }
                check(selectedProvider() == provider) { "语音提供商或配置已切换，请依据新工具参数重新发送；本次未请求语音服务。" }
                val args = arguments.jsonObject
                val style = speechToolStyle(args, capabilities.parameters)
                val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: error("text 必须是非空文字")
                val clips = generate(provider, text, style)
                check(clips.isNotEmpty()) { "语音服务未返回音频" }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true); put("saved", true); put("autoplay", false)
                    put("message", "语音已保存，等待用户点击播放。重播无需重新调用此工具。")
                }.toString())) + clips
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "语音生成或保存失败")
                    put("message", "未自动重试。若请求已发出，服务商可能已计费，请勿为播放反复调用工具。")
                }.toString())) + ((e as? SavedSpeechFailure)?.saved ?: emptyList())
            }
        },
    )
}

internal fun speechToolStyle(args: JsonObject, schema: JsonObject): SpeechStyle {
    require(args.keys.all { it in schema }) { "当前模型不支持参数：${(args.keys - schema.keys).joinToString()}；本次未请求语音服务。" }
    for ((name, value) in args) {
        if (name == "text") continue
        val rule = schema.getValue(name).jsonObject
        val primitive = value as? JsonPrimitive ?: error("$name 参数类型错误")
        rule["enum"]?.jsonArray?.let { allowed ->
            require(allowed.any { it == value || (!primitive.isString && it.jsonPrimitive.doubleOrNull == primitive.doubleOrNull) }) {
                "$name 参数不在当前模型支持范围内"
            }
        }
        if (rule["type"]?.jsonPrimitive?.content != "string") {
            val number = primitive.takeIf { !it.isString }?.doubleOrNull?.takeIf(Double::isFinite)
                ?: error("$name 必须是数字")
            rule["minimum"]?.jsonPrimitive?.doubleOrNull?.let { require(number >= it) { "$name 小于允许范围" } }
            rule["maximum"]?.jsonPrimitive?.doubleOrNull?.let { require(number <= it) { "$name 大于允许范围" } }
            if (rule["type"]?.jsonPrimitive?.content == "integer") require(primitive.intOrNull != null) { "$name 必须是整数" }
        }
    }
    fun number(name: String) = (args[name] as? JsonPrimitive)?.doubleOrNull?.toFloat()
    return SpeechStyle(
        emotion = (args["emotion"] as? JsonPrimitive)?.contentOrNull,
        speed = number("speed"), pitch = (args["pitch"] as? JsonPrimitive)?.intOrNull,
        volume = number("volume"), stability = number("stability"), similarityBoost = number("similarity_boost"),
    )
}
