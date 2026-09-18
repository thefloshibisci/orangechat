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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

import java.io.IOException

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
            .post(buildRequestBody(messages, params).toString().toRequestBody("application/json".toMediaType()))
            .build()
        var completed = false
        val callIds = mutableMapOf<String, String>()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { if (completed) close() else close(IOException("Codex stream ended before completion")); return }
                try {
                    val raw = json.parseToJsonElement(data).jsonObject
                    val eventType = raw["type"]?.jsonPrimitive?.contentOrNull ?: type
                    if (eventType in listOf("error", "response.failed", "response.incomplete")) {
                        close(IOException("Codex response failed or was incomplete")); return
                    }
                    val item = raw["item"] as? JsonObject
                    var event = raw
                    if (eventType == "response.output_item.added" && item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                        val itemId = item.getValue("id").jsonPrimitive.content
                        val callId = item.getValue("call_id").jsonPrimitive.content
                        callIds[itemId] = callId
                        // The done event carries the complete arguments. Do not append an
                        // initial snapshot twice through UIMessagePart.Tool.merge().
                        event = JsonObject(raw + ("item" to JsonObject(item + mapOf(
                            "id" to JsonPrimitive(callId), "arguments" to JsonPrimitive(""),
                        ))))
                    } else if (eventType == "response.function_call_arguments.done") {
                        val itemId = raw.getValue("item_id").jsonPrimitive.content
                        event = JsonObject(raw + ("item_id" to JsonPrimitive(callIds[itemId] ?: itemId)))
                    }
                    parseResponseDelta(event)?.let { trySend(it) }
                    if (eventType == "response.completed") { completed = true; close() }
                } catch (_: Exception) {
                    close(IOException("Invalid Codex stream event"))
                }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                response?.close()
                close(IOException("Codex stream request failed (HTTP ${response?.code ?: 0})"))
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
                params.tools.sortedBy { it.name }.forEach { tool -> add(buildJsonObject {
                    put("type", "function"); put("name", tool.name); put("description", tool.description)
                    put("parameters", json.encodeToJsonElement(tool.parameters()))
                }) }
            }
            put("parallel_tool_calls", true)
        }
        // Deliberately no arbitrary headers/body merge, API-key sampling fields or endpoint overrides.
    }

    companion object {
        const val ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
        // Verified against the installed official Codex CLI, 2026-09-18.
        const val CLIENT_VERSION = "0.155.0"
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
                                if (part.metadata?.get("encrypted_content")?.jsonPrimitiveOrNull?.contentOrNull.isNullOrBlank()) return@forEach
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
                                        add(buildJsonObject {
                                            put("type", "summary_text")
                                            put("text", part.reasoning)
                                        })
                                    })
                                    part.metadata?.get("encrypted_content")?.jsonPrimitiveOrNull?.contentOrNull?.let {
                                        put(
                                            "encrypted_content",
                                            part.metadata?.get("encrypted_content")?.jsonPrimitive?.contentOrNull ?: ""
                                        )
                                    }
                                })
                            }

                            is UIMessagePart.Image -> {
                                val callId = part.metadata?.get("openai_image_call_id")?.jsonPrimitive?.contentOrNull
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
                            put("arguments", tool.input)
                        })
                        val textOutput = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                        val imageOutput = tool.output.filterIsInstance<UIMessagePart.Image>()

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
                                                put("text", "[Image encoding failed]")
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
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
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
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun parseResponseDelta(jsonObject: JsonObject): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")

        when (chunkType) {
            "response.output_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage.assistant(
                                jsonObject["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = jsonObject["delta"]?.jsonPrimitive?.contentOrNull
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
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
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
                                            toolName = item["name"]?.jsonPrimitive?.content ?: "",
                                            input = item["arguments"]?.jsonPrimitive?.content
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
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
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
                                                put("encrypted_content", encryptedContent)
                                                put("reasoning_id", id)
                                            }
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val callId = item["id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    return MessageChunk(
                        id = callId,
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
                                                put("openai_image_call_id", callId)
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
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
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
                                                put("encrypted_content", encryptedContent)
                                                put("reasoning_id", id)
                                            }
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val result = item["result"]?.jsonPrimitive?.content ?: error("result not found")
                    return MessageChunk(
                        id = item["id"]?.jsonPrimitive?.content ?: error("item_id not found"),
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
                                                put("openai_image_call_id", item["id"]?.jsonPrimitive?.content ?: "")
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
                    jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
                val arguments =
                    jsonObject["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
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
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = emptyList(),
                    usage = parseTokenUsage(jsonObject["response"]?.jsonObject?.get("usage")?.jsonObject)
                )
            }
        }

        return null
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}
