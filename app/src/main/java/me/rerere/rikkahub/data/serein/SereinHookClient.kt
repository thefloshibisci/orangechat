package me.rerere.rikkahub.data.serein

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class HookRecall(val context: String = "", val ids: List<String> = emptyList(), val status: String = "empty",
    val diagnostics: JsonObject = JsonObject(emptyMap()))

/** Dedicated client: no chat logging interceptors, no redirects carrying the instance-wide key. */
class SereinHookClient internal constructor(private val client: OkHttpClient) {
    constructor() : this(OkHttpClient.Builder().build())

    suspend fun recall(config: SereinHookConfig, query: String, session: String, delivered: List<String>): HookRecall {
        val root = config.rootUrl() ?: return HookRecall(status = "invalid_config")
        if (config.gatewayKey.isBlank()) return HookRecall(status = "invalid_config")
        val payload = buildJsonObject {
            put("query", query); put("session_id", session); put("max_notes", 2)
            put("max_chars", 1200); put("max_context_chars", 4200)
            put("delivered_ids", JsonArray(delivered.map(::JsonPrimitive)))
        }
        val request = Request.Builder().url("$root/api/hook/recall")
            .header("Authorization", "Bearer ${config.gatewayKey}")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(config.waitMs, TimeUnit.MILLISECONDS)
            .connectTimeout(config.waitMs, TimeUnit.MILLISECONDS).readTimeout(config.waitMs, TimeUnit.MILLISECONDS)
            .build().newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(HookRecall(
                        status = if (e is InterruptedIOException) "timeout" else "network_error"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        try {
                            if (!it.isSuccessful) HookRecall(status = "http_${it.code}")
                            else {
                                val body = it.body ?: error("body missing")
                                // Debug payloads are not retained. Bound bytes before JSON allocation.
                                val source = body.source()
                                source.request(262145)
                                if (source.buffer.size > 262144) HookRecall(status = "invalid_response")
                                else parseHookRecall(source.readUtf8())
                            }
                        } catch (_: Exception) { HookRecall(status = "invalid_response") }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}

internal fun parseHookRecall(raw: String): HookRecall = try {
    val obj = Json.parseToJsonElement(raw).jsonObject
    require(obj["ok"]?.jsonPrimitive?.booleanOrNull == true)
    require(obj["injected"]?.jsonPrimitive?.booleanOrNull == false)
    val context = obj.getValue("additional_context").jsonPrimitive.let { require(it.isString); it.content }
    val ids = obj.getValue("recalled_ids").jsonArray.map { element ->
        element.jsonPrimitive.let { require(it.isString); it.content }
    }
    require(context.length <= 12000 && ids.size <= 2 && ids.distinct().size == ids.size)
    require(ids.all { it.length in 7..250 && (it.startsWith("scene:") || it.startsWith("event:")) })
    val diagnostics = buildJsonObject {
        val semantic = (obj["debug"] as? JsonObject)?.get("semantic_recall_debug") as? JsonObject
        val router = semantic?.get("semantic_recall_router") as? JsonObject
        fun field(value: JsonElement?, key: String, limit: Int) {
            (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put(key, it.take(limit)) }
        }
        field(router?.get("route") ?: semantic?.get("route"), "route", 120)
        field(router?.get("reason"), "reason", 240)
        val cards = (obj["cards"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (cards.isNotEmpty()) put("candidates", buildJsonArray {
            ids.forEach { id -> cards.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == id }?.let { card ->
                add(buildJsonObject {
                    put("id", id)
                    put("title", (card["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(240))
                    put("source_kind", (card["source_kind"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(16))
                    val score = card["score"]
                    val number = ((score as? JsonObject)?.let { it["final"] ?: it["semantic"] } ?: score) as? JsonPrimitive
                    number?.doubleOrNull?.takeIf { it.isFinite() }?.let { put("score", it) }
                })
            } }
        })
    }
    if (context.isBlank() || ids.isEmpty()) HookRecall(diagnostics = diagnostics)
    else HookRecall(context, ids, "ready", diagnostics)
} catch (_: Exception) { HookRecall(status = "invalid_response") }
