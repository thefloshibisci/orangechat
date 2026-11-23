/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "ElevenLabsTTSProvider"

class ElevenLabsTTSProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) : TTSProvider<TTSProviderSetting.ElevenLabs> {

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.ElevenLabs,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        try {
            val requestBody = elevenLabsRequestBody(providerSetting, request)

            val httpRequest = Request.Builder()
                .url(elevenLabsSpeechUrl(providerSetting))
                .addHeader("xi-api-key", providerSetting.apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val audioData = httpClient.newCall(httpRequest).execute().use { response ->
                check(response.isSuccessful) { "ElevenLabs TTS request failed: ${response.code} ${response.message}" }
                response.body.bytes().also { check(it.isNotEmpty()) { "ElevenLabs returned empty audio" } }
            }

            emit(
                AudioChunk(
                    data = audioData,
                    format = AudioFormat.MP3,
                    isLast = true,
                    metadata = mapOf(
                        "provider" to "elevenlabs",
                        "model" to providerSetting.modelId,
                        "voice" to providerSetting.voiceId
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "generateSpeech exception: voiceId=${providerSetting.voiceId}, modelId=${providerSetting.modelId}", e)
            throw e
        }
    }
}
