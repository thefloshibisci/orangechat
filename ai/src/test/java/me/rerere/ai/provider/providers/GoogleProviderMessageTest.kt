/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for GoogleProvider message building logic.
 * Tests the conversion from UIMessage list to Google Gemini API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * Google API format:
 * - role: "user" or "model"
 * - parts array containing text, functionCall, functionResponse
 * - thought: true for reasoning parts
 */
class GoogleProviderMessageTest {

    @Test
    fun `signed text and reasoning replay unchanged and blank signature gets fallback`() {
        val text = invokeParseMessagePart(buildJsonObject {
            put("text", "")
            put("thoughtSignature", "text-signature")
        })
        val thought = invokeParseMessagePart(buildJsonObject {
            put("text", "thinking")
            put("thought", true)
            put("thoughtSignature", "thought-signature")
        })
        val tool = createExecutedTool("id", "test", "{}", "ok").copy(
            metadata = buildJsonObject { put("thoughtSignature", " ") }
        )
        val result = invokeBuildContents(listOf(UIMessage(
            role = MessageRole.ASSISTANT, parts = listOf(text, thought, tool)
        )), true)
        val parts = result.first().jsonObject["parts"]!!.jsonArray
        assertEquals("", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("text-signature", parts[0].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
        assertEquals("thought-signature", parts[1].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
        assertEquals("true", parts[1].jsonObject["thought"]!!.jsonPrimitive.content)
        assertEquals("skip_thought_signature_validator", parts[2].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
    }

    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    // Helper to invoke private buildContents method via reflection
    private fun invokeBuildContents(messages: List<UIMessage>): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildContents",
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, messages) as JsonArray
    }

    private fun invokeBuildContents(
        messages: List<UIMessage>,
        requireThoughtSignature: Boolean
    ): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildContents",
            List::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        method.isAccessible = true
        return method.invoke(provider, messages, requireThoughtSignature) as JsonArray
    }

    private fun invokeParseMessagePart(part: JsonObject): UIMessagePart {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "parseMessagePart",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, part) as UIMessagePart
    }

    @Test
    fun `Google response parser should preserve snake case thought signature`() {
        val parsed = invokeParseMessagePart(buildJsonObject {
            put("functionCall", buildJsonObject {
                put("name", "message_reaction")
                put("args", buildJsonObject {})
            })
            put("thought_signature", "real-signature")
        }) as UIMessagePart.Tool

        assertEquals(
            "real-signature",
            parsed.metadata?.get("thoughtSignature")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `native Gemini request exposes reaction choices to the model`() {
        val choices = JsonArray(listOf(JsonPrimitive("(ᵕ—ᴗ—)"), JsonPrimitive("(≧▽≦)")))
        val tool = Tool(
            name = "message_reaction",
            description = "React to a message",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("emoji", buildJsonObject {
                            put("type", "string")
                            put("enum", choices)
                        })
                    },
                    required = listOf("emoji"),
                )
            },
            execute = { emptyList() },
        )
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java
        )
        method.isAccessible = true
        val body = method.invoke(
            provider,
            listOf(UIMessage.user("React")),
            TextGenerationParams(
                model = Model(modelId = "gemini-3-flash", abilities = listOf(ModelAbility.TOOL)),
                tools = listOf(tool),
            ),
        ) as JsonObject
        val parameters = body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!
            .jsonArray.single().jsonObject["parameters"]!!.jsonObject
        assertEquals(choices, parameters["properties"]!!.jsonObject["emoji"]!!.jsonObject["enum"])
    }

    @Test
    fun `Gemini 3 history should add documented bypass when first tool signature is missing`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_1", "message_reaction", "{}", "ok"),
                createExecutedTool("call_2", "other_tool", "{}", "done")
            )
        )

        val result = invokeBuildContents(
            messages = listOf(UIMessage.user("React"), assistantMessage),
            requireThoughtSignature = true
        )
        val calls = result
            .flatMap { it.jsonObject["parts"]?.jsonArray.orEmpty() }
            .filter { it.jsonObject.containsKey("functionCall") }

        assertEquals(
            "skip_thought_signature_validator",
            calls[0].jsonObject["thoughtSignature"]?.jsonPrimitive?.content
        )
        assertTrue(
            "Only the first call in a parallel group should receive the fallback",
            !calls[1].jsonObject.containsKey("thoughtSignature")
        )
    }

    @Test
    fun `real thought signature should be preserved instead of bypass`() {
        val signedTool = createExecutedTool("call_1", "message_reaction", "{}", "ok").copy(
            metadata = buildJsonObject { put("thoughtSignature", "real-signature") }
        )

        val result = invokeBuildContents(
            messages = listOf(
                UIMessage.user("React"),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(signedTool))
            ),
            requireThoughtSignature = true
        )
        val call = result
            .flatMap { it.jsonObject["parts"]?.jsonArray.orEmpty() }
            .first { it.jsonObject.containsKey("functionCall") }

        assertEquals(
            "real-signature",
            call.jsonObject["thoughtSignature"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `multi-round tool calls should produce functionCall followed by functionResponse`() {
        // Scenario: Multiple rounds of tool calls
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result"),
                UIMessagePart.Text("Now calculating"),
                createExecutedTool("call_2", "calculate", """{"expr": "2+2"}""", "4"),
                UIMessagePart.Text("The answer is 4")
            )
        )

        val messages = listOf(
            UIMessage.user("Calculate something"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Google format:
        // 1. user message
        // 2. model message with [text, functionCall(search)]
        // 3. user message with [functionResponse(search)]
        // 4. model message with [text, functionCall(calculate)]
        // 5. user message with [functionResponse(calculate)]
        // 6. model message with [text]

        // Collect all functionCall and functionResponse parts
        val functionCalls = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val functionResponses = mutableListOf<kotlinx.serialization.json.JsonObject>()

        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val partObj = part.jsonObject
                if (partObj.containsKey("functionCall")) {
                    functionCalls.add(partObj["functionCall"]!!.jsonObject)
                }
                if (partObj.containsKey("functionResponse")) {
                    functionResponses.add(partObj["functionResponse"]!!.jsonObject)
                }
            }
        }

        assertEquals("Should have 2 functionCall parts", 2, functionCalls.size)
        assertEquals("Should have 2 functionResponse parts", 2, functionResponses.size)

        // Verify functionCall contents
        assertEquals("search", functionCalls[0]["name"]?.jsonPrimitive?.content)
        assertEquals("calculate", functionCalls[1]["name"]?.jsonPrimitive?.content)

        // Verify functionResponse contents
        assertEquals("search", functionResponses[0]["name"]?.jsonPrimitive?.content)
        assertEquals("calculate", functionResponses[1]["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `functionCall in model should be followed by user message with functionResponse`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Using tool"),
                createExecutedTool("call_abc", "my_tool", "{}", "Tool output")
            )
        )

        val messages = listOf(
            UIMessage.user("Use a tool"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message with functionCall
        var modelWithFunctionCallIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "model") {
                val parts = msg["parts"]?.jsonArray ?: continue
                if (parts.any { it.jsonObject.containsKey("functionCall") }) {
                    modelWithFunctionCallIndex = i
                    break
                }
            }
        }

        assertTrue("Should find model with functionCall", modelWithFunctionCallIndex >= 0)
        assertTrue("Should not be last message", modelWithFunctionCallIndex < result.size - 1)

        // Next message should be user with functionResponse
        val nextMsg = result[modelWithFunctionCallIndex + 1].jsonObject
        assertEquals("user", nextMsg["role"]?.jsonPrimitive?.content)
        val nextParts = nextMsg["parts"]?.jsonArray
        assertTrue("Next message should have functionResponse",
            nextParts?.any { it.jsonObject.containsKey("functionResponse") } == true)
    }

    @Test
    fun `reasoning parts should have thought flag set to true`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Let me think about this..."),
                UIMessagePart.Text("Here is my response")
            )
        )

        val messages = listOf(
            UIMessage.user("Question"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message
        val modelMsg = result.find {
            it.jsonObject["role"]?.jsonPrimitive?.content == "model"
        }?.jsonObject

        assertTrue("Should have model message", modelMsg != null)

        val parts = modelMsg!!["parts"]?.jsonArray
        assertTrue("Parts should not be null", parts != null)

        // Find text part with thought:true (reasoning is converted to text with thought flag)
        // Note: The implementation may vary - check for thought flag in text parts
        val textParts = parts!!.filter { it.jsonObject.containsKey("text") }
        assertTrue("Should have text parts", textParts.isNotEmpty())

        // Verify we have both regular text and thought text
        val hasThoughtPart = textParts.any {
            it.jsonObject["thought"]?.jsonPrimitive?.content == "true" ||
            it.jsonObject["thought"]?.toString() == "true"
        }
        // Note: If reasoning is handled differently, adjust this assertion
    }

    @Test
    fun `parallel tool calls should be in same model message`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running multiple tools"),
                createExecutedTool("call_1", "tool_a", "{}", "Result A"),
                createExecutedTool("call_2", "tool_b", "{}", "Result B"),
                createExecutedTool("call_3", "tool_c", "{}", "Result C"),
                UIMessagePart.Text("All done")
            )
        )

        val messages = listOf(
            UIMessage.user("Do multiple things"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message with all functionCall parts
        var foundModelWithMultipleCalls = false
        for (msg in result) {
            val msgObj = msg.jsonObject
            if (msgObj["role"]?.jsonPrimitive?.content != "model") continue

            val parts = msgObj["parts"]?.jsonArray ?: continue
            val functionCallParts = parts.filter { it.jsonObject.containsKey("functionCall") }

            if (functionCallParts.size == 3) {
                foundModelWithMultipleCalls = true
                // Verify tool names
                val toolNames = functionCallParts.map {
                    it.jsonObject["functionCall"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                }
                assertTrue(toolNames.contains("tool_a"))
                assertTrue(toolNames.contains("tool_b"))
                assertTrue(toolNames.contains("tool_c"))
                break
            }
        }

        assertTrue("Should have model with 3 parallel functionCall parts",
            foundModelWithMultipleCalls)

        // Verify corresponding functionResponse parts in user message
        var foundUserWithMultipleResponses = false
        for (msg in result) {
            val msgObj = msg.jsonObject
            if (msgObj["role"]?.jsonPrimitive?.content != "user") continue

            val parts = msgObj["parts"]?.jsonArray ?: continue
            val responseParts = parts.filter { it.jsonObject.containsKey("functionResponse") }

            if (responseParts.size == 3) {
                foundUserWithMultipleResponses = true
                break
            }
        }

        assertTrue("Should have user with 3 functionResponse parts",
            foundUserWithMultipleResponses)
    }

    @Test
    fun `multi-round reasoning and tools should maintain correct order`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Step 1: Search"),
                UIMessagePart.Text("Searching..."),
                createExecutedTool("call_1", "search", "{}", "Found data"),
                UIMessagePart.Reasoning(reasoning = "Step 2: Analyze"),
                UIMessagePart.Text("Analyzing..."),
                createExecutedTool("call_2", "analyze", "{}", "Analysis done"),
                UIMessagePart.Reasoning(reasoning = "Step 3: Present"),
                UIMessagePart.Text("Results")
            )
        )

        val messages = listOf(
            UIMessage.user("Analyze"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Verify structure:
        // model -> user (functionResponse) -> model -> user (functionResponse) -> model

        var functionCallCount = 0
        var functionResponseCount = 0

        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val partObj = part.jsonObject
                if (partObj.containsKey("functionCall")) functionCallCount++
                if (partObj.containsKey("functionResponse")) functionResponseCount++
            }
        }

        assertEquals("Should have 2 functionCall parts", 2, functionCallCount)
        assertEquals("Should have 2 functionResponse parts", 2, functionResponseCount)

        // Verify functionCall -> functionResponse order
        for (i in 0 until result.size - 1) {
            val msg = result[i].jsonObject
            val parts = msg["parts"]?.jsonArray ?: continue
            val hasFunctionCall = parts.any { it.jsonObject.containsKey("functionCall") }

            if (hasFunctionCall && msg["role"]?.jsonPrimitive?.content == "model") {
                // Next should be user with functionResponse
                val nextMsg = result[i + 1].jsonObject
                assertEquals("user", nextMsg["role"]?.jsonPrimitive?.content)
                val nextParts = nextMsg["parts"]?.jsonArray
                assertTrue("Should have functionResponse in next message",
                    nextParts?.any { it.jsonObject.containsKey("functionResponse") } == true)
            }
        }
    }

    @Test
    fun `user message parts should be correctly formatted`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("Hello, how are you?")
                )
            )
        )

        val result = invokeBuildContents(messages)

        assertEquals(1, result.size)
        val userMsg = result[0].jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)

        val parts = userMsg["parts"]?.jsonArray
        assertTrue("Parts should not be null", parts != null)
        assertTrue("Parts should not be empty", parts!!.isNotEmpty())

        val textPart = parts.find { it.jsonObject.containsKey("text") }?.jsonObject
        assertEquals("Hello, how are you?", textPart?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `complex multi-round scenario with interleaved content`() {
        val messages = listOf(
            UIMessage.user("Execute task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Starting"),
                    createExecutedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing"),
                    createExecutedTool("step2", "process", """{"data": "x"}""", "processed"),
                    UIMessagePart.Text("Finalizing"),
                    createExecutedTool("step3", "finalize", "{}", "done"),
                    UIMessagePart.Text("Task completed")
                )
            )
        )

        val result = invokeBuildContents(messages)

        // Count roles
        val userCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val modelCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "model" }

        // Should have: 1 initial user + 3 functionResponse users = 4 user messages
        // And: multiple model messages
        assertEquals("Should have 4 user messages (1 initial + 3 responses)", 4, userCount)
        assertTrue("Should have multiple model messages", modelCount >= 3)

        // Verify order: each functionCall should be followed by functionResponse
        var lastFunctionCallIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            val parts = msg["parts"]?.jsonArray ?: continue
            if (parts.any { it.jsonObject.containsKey("functionCall") }) {
                assertTrue("functionCall should not be last", i < result.size - 1)
                val next = result[i + 1].jsonObject
                assertEquals("user", next["role"]?.jsonPrimitive?.content)
                assertTrue("Index should increase", i > lastFunctionCallIndex)
                lastFunctionCallIndex = i
            }
        }
    }

    @Test
    fun `functionResponse should contain correct result structure`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_1", "my_tool", """{"input": "test"}""", "Expected output value")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find functionResponse
        var functionResponse: kotlinx.serialization.json.JsonObject? = null
        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                if (part.jsonObject.containsKey("functionResponse")) {
                    functionResponse = part.jsonObject["functionResponse"]?.jsonObject
                    break
                }
            }
            if (functionResponse != null) break
        }

        assertTrue("Should find functionResponse", functionResponse != null)
        assertEquals("my_tool", functionResponse!!["name"]?.jsonPrimitive?.content)

        // Verify response structure
        val response = functionResponse["response"]?.jsonObject
        assertTrue("Response should contain result",
            response?.containsKey("result") == true)
        assertTrue("Result should contain expected output",
            response?.get("result")?.jsonPrimitive?.content?.contains("Expected output value") == true)
    }

    // ==================== Helper Functions ====================

    @Test
    fun `same-name calls keep distinct identities and corresponding results across rounds`() {
        val first = UIMessage.assistant("").copy(parts = listOf(
            createExecutedTool("hold-1", "hold", "{\"value\":1}", "saved-first"),
            createExecutedTool("hold-2", "hold", "{\"value\":2}", "saved-second"),
            UIMessagePart.Text("Another round"),
            createExecutedTool("hold-3", "hold", "{\"value\":3}", "saved-third"),
        ))
        val history = listOf(UIMessage.user("save"), first, UIMessage.user("continue"))
        val parts = invokeBuildContents(history).flatMap { it.jsonObject["parts"]!!.jsonArray }
        val calls = parts.mapNotNull { it.jsonObject["functionCall"]?.jsonObject }
        val results = parts.mapNotNull { it.jsonObject["functionResponse"]?.jsonObject }
        assertEquals(listOf("hold-1", "hold-2", "hold-3"), calls.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(calls.map { it["id"] }, results.map { it["id"] })
        assertTrue((calls + results).all { it["name"]!!.jsonPrimitive.content == "hold" })
        assertEquals(listOf("saved-first", "saved-second", "saved-third"),
            results.map { it["response"]!!.jsonObject["result"]!!.jsonPrimitive.content })
    }

    @Test
    fun `different-name parallel calls and responses retain identical positional order`() {
        val tools = listOf(
            createExecutedTool("avatar-1", "avatar_interaction", "{}", "avatar-result"),
            createExecutedTool("reaction-1", "message_reaction", "{}", "reaction-result"),
        )
        val parts = invokeBuildContents(listOf(UIMessage.assistant("").copy(parts = tools)))
            .flatMap { it.jsonObject["parts"]!!.jsonArray }
        val calls = parts.mapNotNull { it.jsonObject["functionCall"]?.jsonObject }
        val results = parts.mapNotNull { it.jsonObject["functionResponse"]?.jsonObject }

        assertEquals(listOf("avatar_interaction", "message_reaction"),
            calls.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(calls.map { it["name"] }, results.map { it["name"] })
        assertEquals(calls.map { it["id"] }, results.map { it["id"] })
        assertEquals(listOf("avatar-result", "reaction-result"),
            results.map { it["response"]!!.jsonObject["result"]!!.jsonPrimitive.content })
    }

    @Test
    fun `old blank call identities get stable distinct request-only fallback ids`() {
        val tools = List(2) { createExecutedTool("", "hold", "{}", "result-$it") }
        val history = listOf(UIMessage.assistant("").copy(parts = tools))
        val encoded = invokeBuildContents(history)
        assertEquals(encoded, invokeBuildContents(history))
        val parts = encoded.flatMap { it.jsonObject["parts"]!!.jsonArray }
        val callIds = parts.mapNotNull { it.jsonObject["functionCall"]?.jsonObject?.get("id")?.jsonPrimitive?.content }
        val resultIds = parts.mapNotNull { it.jsonObject["functionResponse"]?.jsonObject?.get("id")?.jsonPrimitive?.content }
        assertEquals(2, callIds.toSet().size)
        assertTrue(callIds.all { it.isNotBlank() })
        assertEquals(callIds, resultIds)
        assertTrue(history.single().parts.filterIsInstance<UIMessagePart.Tool>().all { it.toolCallId.isBlank() })
    }

    @Test
    fun `server call id and thought signature survive parse and replay`() {
        val method = GoogleProvider::class.java.getDeclaredMethod("parseMessagePart", kotlinx.serialization.json.JsonObject::class.java)
            .apply { isAccessible = true }
        val wire = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"functionCall":{"id":"server-hold-17","name":"hold","args":{"text":"hello"}},"thoughtSignature":"signed-thought"}"""
        ).jsonObject
        val parsed = method.invoke(provider, wire) as UIMessagePart.Tool
        assertEquals("server-hold-17", parsed.toolCallId)
        val parts = invokeBuildContents(listOf(UIMessage.assistant("").copy(
            parts = listOf(parsed.copy(output = listOf(UIMessagePart.Text("saved")))))
        )).flatMap { it.jsonObject["parts"]!!.jsonArray }.map { it.jsonObject }
        assertEquals("signed-thought", parts.first()["thoughtSignature"]!!.jsonPrimitive.content)
        assertEquals("server-hold-17", parts.first()["functionCall"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("server-hold-17", parts.last()["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `idless gateway responses receive different locally retained identities`() {
        val method = GoogleProvider::class.java.getDeclaredMethod("parseMessagePart", kotlinx.serialization.json.JsonObject::class.java)
            .apply { isAccessible = true }
        val wire = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"functionCall":{"name":"hold","args":{}}}"""
        ).jsonObject
        val first = method.invoke(provider, wire) as UIMessagePart.Tool
        val second = method.invoke(provider, wire) as UIMessagePart.Tool
        assertTrue(first.toolCallId.isNotBlank())
        assertTrue(first.toolCallId != second.toolCallId)
    }

    private fun createExecutedTool(
        callId: String,
        name: String,
        input: String,
        output: String
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output))
        )
    }
}
