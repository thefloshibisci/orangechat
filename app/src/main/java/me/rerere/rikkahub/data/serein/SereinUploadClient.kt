package me.rerere.rikkahub.data.serein

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class UploadResult(val status: String, val error: String = "")

/** No request logging, redirects, or hidden retries carrying original text or bearer credentials. */
class SereinUploadClient internal constructor(private val http: OkHttpClient) {
    constructor() : this(OkHttpClient.Builder().build())

    internal suspend fun send(config: SereinHookConfig, row: SereinUpload): UploadResult {
        val root = config.rootUrl() ?: return UploadResult("retry", "invalid_config")
        val request = Request.Builder().url("$root/api/ingest-raw")
            .header("Authorization", "Bearer ${config.gatewayKey}")
            .post(row.payload.toRequestBody("application/json".toMediaType())).build()
        val call = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(30, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .build().newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(UploadResult("retry", "network_error"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        try {
                            if (!it.isSuccessful) UploadResult(
                                if (it.code in 400..499 && it.code !in setOf(401, 403, 404, 405, 408, 429)) "blocked" else "retry",
                                "http_${it.code}")
                            else {
                                val source = requireNotNull(it.body).source()
                                source.request(65537)
                                if (source.buffer.size > 65536) UploadResult("retry", "invalid_response")
                                else parseUploadResult(source.readUtf8(), row.payload)
                            }
                        } catch (_: Exception) { UploadResult("retry", "invalid_response") }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}

internal fun parseUploadResult(raw: String, payload: String): UploadResult = try {
    val obj = Json.parseToJsonElement(raw).jsonObject
    require(obj["ok"]?.jsonPrimitive?.booleanOrNull == true)
    val item = obj.getValue("items").jsonArray.single().jsonObject
    val expected = Json.parseToJsonElement(payload).jsonObject.getValue("events").jsonArray.single().jsonObject
    require(item.getValue("source_event_id") == expected.getValue("source_event_id"))
    when (item.getValue("status").jsonPrimitive.content) {
        "inserted", "duplicate" -> {
            require(item["id"]?.jsonPrimitive?.longOrNull?.let { it > 0 } == true)
            require(item["source"]?.jsonPrimitive?.content == "orangechat")
            UploadResult("sent")
        }
        "rejected" -> UploadResult("blocked", "server_rejected")
        else -> UploadResult("retry", "invalid_response")
    }
} catch (_: Exception) { UploadResult("retry", "invalid_response") }
