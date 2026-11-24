package me.rerere.tts.provider.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.model.SpeechMarkup
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Official convert endpoint: output_format is a query parameter, not a JSON body field. */
internal fun elevenLabsSpeechUrl(setting: TTSProviderSetting.ElevenLabs): HttpUrl =
    setting.baseUrl.trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegment("text-to-speech").addPathSegment(setting.voiceId)
        .setQueryParameter("output_format", "mp3_44100_128").build()

internal fun elevenLabsRequestBody(setting: TTSProviderSetting.ElevenLabs, request: TTSRequest) = buildJsonObject {
    fun unit(value: Float?, fallback: Float) = value?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: fallback
    val requestedStability = unit(request.style.stability, unit(setting.stability, .5f))
    val stability = if (setting.modelId == "eleven_v3") {
        listOf(0f, .5f, 1f).minBy { kotlin.math.abs(it - requestedStability) }
    } else requestedStability
    val words = SpeechMarkup.plainText(request.text)
    put("text", if (setting.modelId in setOf("eleven_v3", "eleven_v4", "eleven_v4_turbo")) words else SpeechMarkup.withoutElevenLabsTags(words))
    put("model_id", setting.modelId)
    put("voice_settings", buildJsonObject {
        put("stability", stability)
        put("similarity_boost", unit(request.style.similarityBoost, unit(setting.similarityBoost, .75f)))
    })
}
