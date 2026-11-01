package me.rerere.tts.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.model.SpeechMarkup
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProviderSetting

/** Verified against the MiniMax HTTP OpenAPI, 2026-11-02. Unknown IDs get no optional controls. */
internal fun miniMaxRequestBody(setting: TTSProviderSetting.MiniMax, request: TTSRequest): JsonObject {
    val model = setting.model
    val knownModel = model in setOf(
        "speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-2.6-turbo",
        "speech-02-hd", "speech-02-turbo", "speech-01-hd", "speech-01-turbo",
    )
    val is28 = model in setOf("speech-2.8-hd", "speech-2.8-turbo")
    val is26 = model in setOf("speech-2.6-hd", "speech-2.6-turbo")
    val emotion = request.style.emotion ?: setting.emotion
    val allowedEmotion = emotion in setOf("happy", "sad", "angry", "fearful", "disgusted", "surprised", "calm") ||
        is26 && emotion in setOf("fluent", "whisper")
    return buildJsonObject {
        put("model", model)
        put("text", if (knownModel) SpeechMarkup.miniMaxText(request.text, is28) else SpeechMarkup.plainText(request.text))
        put("stream", true)
        put("output_format", "hex")
        put("stream_options", buildJsonObject { put("exclude_aggregated_audio", true) })
        put("voice_setting", buildJsonObject {
            put("voice_id", setting.voiceId)
            if (knownModel) {
                // "auto" and unsupported values intentionally omit the field, allowing natural inference.
                if (allowedEmotion) put("emotion", emotion)
                val speed = (request.style.speed ?: setting.speed).takeIf(Float::isFinite) ?: 1f
                put("speed", speed.coerceIn(0.5f, 2f))
                request.style.pitch?.let { put("pitch", it.coerceIn(-12, 12)) }
                request.style.volume?.takeIf(Float::isFinite)?.let { put("vol", it.coerceIn(0.01f, 10f)) }
            }
        })
    }
}
