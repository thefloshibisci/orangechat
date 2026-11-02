package me.rerere.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

private const val MAX_FILE_BYTES = 24L * 1024 * 1024
private const val MAX_MIMO_BYTES = 7_000_000L // Keep base64 under MiMo's 10 MB request limit.
private const val MAX_RESPONSE_BYTES = 1024 * 1024

/**
 * Transcribe an existing WAV/MP3 file using the selected ASR configuration, without recording,
 * making a persistent copy or treating speech transcription as non-speech sound understanding.
 * The caller owns the input file. Cancelling the coroutine cancels the HTTP upload as well.
 */
suspend fun transcribeAudioFile(
    httpClient: OkHttpClient,
    provider: ASRProviderSetting,
    file: File,
): String = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    if (provider is ASRProviderSetting.Volcengine) {
        throw UnsupportedOperationException("当前火山 ASR 配置仅支持实时录音，视频文件请使用其他文件转写服务或支持音频的聊天模型")
    }
    require(file.isFile && file.canRead() && file.length() in 1..MAX_FILE_BYTES) {
        "音频文件不可读、为空或超过 24 MB"
    }
    val format = file.extension.lowercase()
    require(format == "wav" || format == "mp3") { "文件转写目前支持 WAV 或 MP3" }
    val mime = if (format == "wav") "audio/wav" else "audio/mpeg"
    val request = when (provider) {
        is ASRProviderSetting.SiliconFlow -> {
            require(provider.apiKey.isNotBlank() && provider.model.isNotBlank()) { "请先配置语音识别 API Key 和模型" }
            checkAsrKey(provider.apiKey)
            multipartRequest(
                url = checkedAsrUrl(provider.baseUrl),
                key = provider.apiKey,
                model = provider.model,
                file = file,
                mime = mime,
            )
        }
        is ASRProviderSetting.OpenAIRealtime -> {
            require(provider.apiKey.isNotBlank() && provider.model.isNotBlank()) { "请先配置语音识别 API Key 和模型" }
            checkAsrKey(provider.apiKey)
            multipartRequest(
                url = openAIFileTranscriptionUrl(provider.websocketUrl),
                key = provider.apiKey,
                model = provider.model,
                file = file,
                mime = mime,
                language = provider.language,
                prompt = provider.prompt,
                jsonResponse = true,
            )
        }
        is ASRProviderSetting.MiMo -> {
            require(provider.apiKey.isNotBlank() && provider.model.isNotBlank()) { "请先配置语音识别 API Key 和模型" }
            checkAsrKey(provider.apiKey)
            require(file.length() <= MAX_MIMO_BYTES) { "MiMo 单次音频过大，请缩短视频片段" }
            val base = checkedAsrUrl(provider.baseUrl)
            val url = if (base.encodedPath.trimEnd('/').endsWith("/chat/completions")) base else
                base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/chat/completions").build()
            val audioData = Base64.getEncoder().encodeToString(file.readBytes())
            val body = buildJsonObject {
                put("model", provider.model)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "input_audio")
                                put("input_audio", buildJsonObject { put("data", "data:$mime;base64,$audioData") })
                            })
                        })
                    })
                })
                if (provider.language.isNotBlank()) {
                    put("asr_options", buildJsonObject { put("language", provider.language) })
                }
            }
            Request.Builder().url(url).header("api-key", provider.apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        }
        is ASRProviderSetting.Volcengine -> error("Unsupported file ASR")
    }
    currentCoroutineContext().ensureActive()
    val client = httpClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    client.newCall(request).awaitAudioFileTranscript(provider is ASRProviderSetting.MiMo)
}

private fun multipartRequest(
    url: HttpUrl,
    key: String,
    model: String,
    file: File,
    mime: String,
    language: String = "",
    prompt: String = "",
    jsonResponse: Boolean = false,
): Request {
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("model", model)
        .addFormDataPart("file", "audio.${file.extension.lowercase()}", file.asRequestBody(mime.toMediaType()))
        .apply {
            if (language.isNotBlank()) addFormDataPart("language", language)
            if (prompt.isNotBlank()) addFormDataPart("prompt", prompt)
            if (jsonResponse) addFormDataPart("response_format", "json")
        }.build()
    return Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build()
}

private fun checkedAsrUrl(value: String): HttpUrl {
    val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("语音识别服务地址无效")
    require(url.username.isBlank() && url.password.isBlank() && url.query == null && url.fragment == null) {
        "语音识别地址请勿包含账号、查询参数或片段"
    }
    return url
}

private fun checkAsrKey(key: String) {
    require(key.all { it.code in 32..126 }) { "语音识别 API Key 格式无效" }
}

/** Preserve a proxy's path prefix; never send credentials to a guessed different host or path. */
internal fun openAIFileTranscriptionUrl(websocketUrl: String): HttpUrl {
    val http = websocketUrl.trim().replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
        .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
    val url = http.toHttpUrlOrNull() ?: throw IllegalArgumentException("语音识别服务地址无效")
    require(url.username.isBlank() && url.password.isBlank() && url.fragment == null) { "语音识别服务地址无效" }
    require(url.queryParameterNames.all { it == "intent" || it == "model" }) {
        "该实时识别代理地址无法确定文件转写接口，请配置文件转写服务"
    }
    val path = url.encodedPath.trimEnd('/')
    require(path.endsWith("/realtime")) {
        "该实时识别代理地址无法确定文件转写接口，请配置文件转写服务"
    }
    return url.newBuilder().encodedPath(path.removeSuffix("/realtime") + "/audio/transcriptions")
        .query(null).build()
}

internal fun parseAudioFileTranscript(body: String, mimo: Boolean): String {
    val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
        ?: throw IOException("音频转写响应格式无效")
    if (root["error"] != null || root["code"] != null) throw IOException("语音识别服务返回错误")
    val value = if (mimo) {
        runCatching { root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content") }
            .getOrNull()
    } else root["text"]
    val text = value as? JsonPrimitive
    if (text == null || !text.isString) throw IOException("音频转写响应缺少文本")
    return text.content.trim()
}

private suspend fun Call.awaitAudioFileTranscript(mimo: Boolean): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(
                IOException("音频转写连接失败，请检查语音识别服务")
            )
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!it.isSuccessful) throw IOException("音频转写失败（HTTP ${it.code}），请检查语音识别配置")
                    val body = it.body ?: throw IOException("音频转写响应为空")
                    if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("音频转写响应过大")
                    val output = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (continuation.isActive) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("音频转写响应过大")
                            output.write(buffer, 0, count)
                        }
                    }
                    if (continuation.isActive) continuation.resume(parseAudioFileTranscript(output.toString("UTF-8"), mimo)) { _, _, _ -> }
                }
            } catch (error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
