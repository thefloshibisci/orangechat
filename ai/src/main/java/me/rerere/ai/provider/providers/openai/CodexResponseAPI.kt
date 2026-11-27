/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.toJsonRequestBody
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

import java.io.IOException

class CodexHttpFailure(val code: Int, val detail: String? = null) : IOException(
    buildString {
        append(
            when (code) {
                401 -> "Codex 登录授权失败（HTTP 401）"
                403 -> "Codex 拒绝访问（HTTP 403）"
                429 -> "Codex 请求受限，请检查额度或稍后重试（HTTP 429）"
                else -> "Codex 请求失败（HTTP $code）"
            }
        )
        if (!detail.isNullOrBlank()) append("：").append(detail)
    }
)

class CodexStreamFailure(message: String) : IOException(message)

private val CODEX_BEARER_REGEX = Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE)
private val CODEX_SK_KEY_REGEX = Regex("""sk-[A-Za-z0-9_-]+""")
private val CODEX_JWT_REGEX = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?""")
private val CODEX_URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val CODEX_CONTROL_REGEX = Regex("""[\r\n\t\u0000-\u001F\u007F]+""")

internal fun sanitizeCodexErrorDetail(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val cleaned = raw
        .replace(CODEX_BEARER_REGEX, "[redacted]")
        .replace(CODEX_SK_KEY_REGEX, "[redacted]")
        .replace(CODEX_JWT_REGEX, "[redacted]")
        .replace(CODEX_URL_REGEX, "[url]")
        .replace(CODEX_CONTROL_REGEX, " ")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
        .take(160)
        .trim()
    return cleaned.takeIf { it.isNotBlank() }
}

internal fun extractCodexStructuredError(obj: JsonObject): String? {
    fun formatErrorObj(err: JsonObject): String? {
        val message = err["message"]?.jsonPrimitiveOrNull?.contentOrNull
        val code = err["code"]?.jsonPrimitiveOrNull?.contentOrNull
        val param = err["param"]?.jsonPrimitiveOrNull?.contentOrNull
        val type = err["type"]?.jsonPrimitiveOrNull?.contentOrNull
        val head = listOfNotNull(
            code?.takeIf { it.isNotBlank() },
            param?.takeIf { it.isNotBlank() }?.let { "param=$it" },
        ).joinToString(", ")
        return buildList {
            if (head.isNotBlank()) add("[$head]")
            else if (!type.isNullOrBlank()) add("[$type]")
            if (!message.isNullOrBlank()) add(message)
        }.joinToString(" ").takeIf { it.isNotBlank() }
    }
    val errorObj = obj["error"]?.jsonObjectOrNull
        ?: obj["response"]?.jsonObjectOrNull?.get("error")?.jsonObjectOrNull
    val candidate = errorObj?.let(::formatErrorObj)
        ?: obj["response"]?.jsonObjectOrNull?.get("incomplete_details")?.jsonObjectOrNull
            ?.get("reason")?.jsonPrimitiveOrNull?.contentOrNull
        ?: obj["detail"]?.jsonPrimitiveOrNull?.contentOrNull
    return sanitizeCodexErrorDetail(candidate)
}

/** Independent Codex transport; standard OpenAI ResponseAPI remains unchanged. */
class CodexResponseAPI(private val client: OkHttpClient) {
    suspend fun generateText(accessToken: String, accountId: String, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk {
        var message = UIMessage.assistant("")
        var usage: TokenUsage? = null
        streamText(accessToken, accountId, messages, params).collect { chunk ->
            message += chunk
            if (chunk.usage != null) usage = chunk.usage
        }
        return MessageChunk("", params.model.modelId, listOf(UIMessageChoice(0, null, message, "stop")), usage)
    }

    fun streamText(accessToken: String, accountId: String, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> = callbackFlow {
        val request = Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .header("ChatGPT-Account-Id", accountId)
            .header("OpenAI-Beta", "responses=experimental")
            .header("originator", "codex_cli_rs")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/event-stream")
            .apply { codexCacheKey(params)?.let { header("session_id", it) } }
            .post(buildRequestBody(messages, params).toJsonRequestBody())
            .build()
        var completed = false
        val callIds = mutableMapOf<String, String>()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { if (completed) close() else close(IOException("Codex stream ended before completion")); return }
                try {
                    val raw = json.parseToJsonElement(data).jsonObjectOrNull
                    if (raw == null) {
                        close(IOException("Invalid Codex stream event"))
                        return
                    }
                    val eventType = raw["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: type
                    if (eventType in listOf("error", "response.failed", "response.incomplete")) {
                        val detail = extractCodexStructuredError(raw)
                        close(CodexStreamFailure(detail?.let { "Codex 返回错误：$it" } ?: "Codex response failed or was incomplete"))
                        return
                    }
                    val item = raw["item"]?.jsonObjectOrNull
                    var event = raw
                    if (eventType == "response.output_item.added" && item?.get("type")?.jsonPrimitiveOrNull?.contentOrNull == "function_call") {
                        val itemId = item["id"]?.jsonPrimitiveOrNull?.contentOrNull
                        val callId = item["call_id"]?.jsonPrimitiveOrNull?.contentOrNull
                        if (itemId != null && callId != null) {
                            callIds[itemId] = callId
                            // The done event carries the complete arguments. Do not append an
                            // initial snapshot twice through UIMessagePart.Tool.merge().
                            event = JsonObject(raw + ("item" to JsonObject(item + mapOf(
                                "id" to JsonPrimitive(callId), "arguments" to JsonPrimitive(""),
                            ))))
                        }
                    } else if (eventType == "response.function_call_arguments.done") {
                        val itemId = raw["item_id"]?.jsonPrimitiveOrNull?.contentOrNull
                        if (itemId != null) {
                            event = JsonObject(raw + ("item_id" to JsonPrimitive(callIds[itemId] ?: itemId)))
                        }
                    }
                    parseResponseDelta(event)?.let { trySend(it) }
                    if (eventType == "response.completed") { completed = true; close() }
                } catch (_: Exception) {
                    close(IOException("Invalid Codex stream event"))
                }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val failure = if (response != null && !response.isSuccessful) {
                    val detail = runCatching {
                        val bodyText = response.peekBody(4096L).string()
                        val parsed = json.parseToJsonElement(bodyText) as? JsonObject
                        parsed?.let(::extractCodexStructuredError)
                    }.getOrNull()
                    val code = response.code
                    response.close()
                    CodexHttpFailure(code, detail)
                } else {
                    response?.close()
                    val errName = t?.javaClass?.simpleName?.takeIf { it.isNotBlank() }
                    CodexStreamFailure(
                        errName?.let { "Codex 网络连接失败（$it）；请检查代理/VPN 或网络状态" }
                            ?: "Codex 网络连接失败；请检查代理/VPN 或网络状态"
                    )
                }
                close(failure)
            }
            override fun onClosed(eventSource: EventSource) {
                if (completed) close() else close(IOException("Codex stream closed before completion"))
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    internal fun buildRequestBody(messages: List<UIMessage>, params: TextGenerationParams): JsonObject = buildJsonObject {
        put("model", params.model.modelId)
        put("stream", true) // Subscription backend also uses SSE for non-streaming callers.
        put("store", false)
        codexCacheKey(params)?.let { put("prompt_cache_key", it) }
        put("instructions", messages.filter { it.role == MessageRole.SYSTEM }.flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.ifBlank { "You are a helpful assistant." })
        put("input", buildMessages(messages))
        if (ModelAbility.REASONING in params.model.abilities) {
            put("reasoning", buildJsonObject {
                codexReasoningEffort(params)?.let { put("effort", it) }
                put("summary", "auto")
            })
            put("include", buildJsonArray { add("reasoning.encrypted_content") })
        }
        if (params.tools.isNotEmpty()) {
            putJsonArray("tools") {
                params.tools.distinctBy { it.name }.sortedBy { it.name }.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", tool.name)
                        put("description", tool.description)
                        val schema = tool.parameters()
                        put(
                            "parameters",
                            if (schema != null) json.encodeToJsonElement(schema)
                            else buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {})
                            },
                        )
                    })
                }
            }
            put("parallel_tool_calls", true)
        }
        // Deliberately no arbitrary headers/body merge, API-key sampling fields or endpoint overrides.
    }

    companion object {
        const val ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
        // Verified against the installed official Codex CLI, 2026-09-18.
        const val CLIENT_VERSION = "0.169.0"
        const val USER_AGENT = "codex_cli_rs/$CLIENT_VERSION (Android)"
    }

    internal fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantItems(message)
                } else {
                    addUserItems(message)
                }
            }
    }

    private fun JsonArrayBuilder.addAssistantItems(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                val encrypted = part.metadata?.get("encrypted_content")?.jsonPrimitiveOrNull?.contentOrNull
                                    ?.takeIf { it.isNotBlank() && it != "null" }
                                val providerTag = part.metadata?.get("reasoning_provider")?.jsonPrimitiveOrNull?.contentOrNull
                                if (encrypted == null || providerTag != "codex") return@forEach
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    part.metadata?.get("reasoning_id")?.jsonPrimitiveOrNull?.contentOrNull?.let {
                                        put("id", it)
                                    }
                                    put("summary", buildJsonArray {
                                        if (part.reasoning.isNotBlank()) {
                                            add(buildJsonObject {
                                                put("type", "summary_text")
                                                put("text", part.reasoning)
                                            })
                                        }
                                    })
                                    put("encrypted_content", encrypted)
                                })
                            }

                            is UIMessagePart.Image -> {
                                val callId = part.metadata?.get("openai_image_call_id")?.jsonPrimitiveOrNull?.contentOrNull
                                if (callId != null) {
                                    if (contentBuffer.isNotEmpty()) {
                                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                        contentBuffer.clear()
                                    }
                                    add(buildJsonObject {
                                        put("type", "image_generation_call")
                                        put("id", callId)
                                    })
                                } else {
                                    contentBuffer.add(part)
                                }
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }

                    // 输出 function_call + function_call_output
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            put("arguments", tool.input.ifBlank { "{}" }) // no-arg calls stream "", which strict proxies reject
                        })
                        val imageOutput = tool.output.filterIsInstance<UIMessagePart.Image>()
                        val textOutput = tool.output.filterIsInstance<UIMessagePart.Text>()
                            .joinToString("\n") { it.text }
                            .ifBlank {
                                if (imageOutput.isNotEmpty()) {
                                    "[Tool returned an image; see the following attachment.]"
                                } else {
                                    "[Tool completed with no text output.]"
                                }
                            }

                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            put("output", textOutput)
                        })

                        // If tool output contains images, inject a user message with the images
                        if (imageOutput.isNotEmpty()) {
                            add(buildJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", "[Tool ${tool.toolName} returned an image]")
                                    })
                                    imageOutput.forEach { imagePart ->
                                        add(buildJsonObject {
                                            imagePart.encodeBase64().onSuccess { encodedImage ->
                                                put("type", "input_image")
                                                put("image_url", encodedImage.base64)
                                            }.onFailure {
                                                put("type", "input_text")
                                                put("text", "[Image unavailable: the attachment could not be read. Do not infer its contents.]")
                                            }
                                        })
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        val usableParts = parts.filter { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> part.url.isNotBlank()
                else -> false
            }
        }
        if (usableParts.isEmpty()) return

        add(buildJsonObject {
            put("type", "message")
            put("role", JsonPrimitive(role.name.lowercase()))
            putJsonArray("content") {
                usableParts.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            add(buildJsonObject {
                                put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                put("text", part.text)
                            })
                        }

                        is UIMessagePart.Image -> {
                            add(buildJsonObject {
                                part.encodeBase64().onSuccess { encodedImage ->
                                    put("type", if (role == MessageRole.USER) "input_image" else "output_image")
                                    put("image_url", encodedImage.base64)
                                }.onFailure {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", "[Image unavailable: the attachment could not be read. Do not infer its contents.]")
                                }
                            })
                        }

                        else -> {}
                    }
                }
            }
        })
    }

    private fun parseResponseDelta(jsonObject: JsonObject): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null

        when (chunkType) {
            "response.output_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage.assistant(
                                jsonObject["delta"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = jsonObject["delta"]?.jsonPrimitiveOrNull?.contentOrNull
                                            ?: "",
                                        createdAt = Clock.System.now(),
                                        finishedAt = null
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_item.added" -> {
                val item = jsonObject["item"]?.jsonObjectOrNull ?: return null
                val type = item["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
                val id = item["id"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
                if (type == "function_call") {
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Tool(
                                            toolCallId = id,
                                            toolName = item["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
                                            input = item["arguments"]?.jsonPrimitiveOrNull?.contentOrNull
                                                ?: "",
                                            output = emptyList()
                                        )
                                    )
                                ),
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = buildJsonObject {
                                                put("reasoning_provider", "codex")
                                                put("reasoning_id", id)
                                                if (encryptedContent != null) {
                                                    put("encrypted_content", encryptedContent)
                                                }
                                            }
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(
                                            url = "",
                                            metadata = buildJsonObject {
                                                put("openai_image_call_id", id)
                                            }
                                        )
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.output_item.done" -> {
                val item = jsonObject["item"]?.jsonObjectOrNull ?: return null
                val type = item["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
                val id = item["id"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
                if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = buildJsonObject {
                                                put("reasoning_provider", "codex")
                                                put("reasoning_id", id)
                                                if (encryptedContent != null) {
                                                    put("encrypted_content", encryptedContent)
                                                }
                                            }
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val result = item["result"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(
                                            url = result,
                                            metadata = buildJsonObject {
                                                put("openai_image_call_id", id)
                                            }
                                        )
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.function_call_arguments.done" -> {
                val toolCallId =
                    jsonObject["item_id"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
                val arguments =
                    jsonObject["arguments"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
                return MessageChunk(
                    id = toolCallId,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Tool(
                                        toolCallId = toolCallId,
                                        toolName = "",
                                        input = arguments,
                                        output = emptyList()
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    ),
                )
            }

            "response.completed" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
                    model = "",
                    choices = emptyList(),
                    usage = parseTokenUsage(jsonObject["response"]?.jsonObjectOrNull?.get("usage")?.jsonObjectOrNull)
                )
            }
        }

        return null
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitiveOrNull?.intOrNull
                ?: 0
        )
    }
}
