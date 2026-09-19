package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ProactiveRequestTest {
    private fun wire(messages: List<UIMessage>, google: Boolean): JsonArray {
        val provider = if (google) GoogleProvider(OkHttpClient()) else ClaudeProvider(OkHttpClient())
        val method = if (google) provider.javaClass.getDeclaredMethod("buildContents", List::class.java)
        else provider.javaClass.getDeclaredMethod(
            "buildMessages", List::class.java, Boolean::class.javaPrimitiveType, ClaudePromptCacheTtl::class.java,
        )
        method.isAccessible = true
        return if (google) method.invoke(provider, messages) as JsonArray
        else method.invoke(provider, messages, false, ClaudePromptCacheTtl.FIVE_MINUTES) as JsonArray
    }

    @Test fun `both strict providers end initial proactive request in user without modifying history`() {
        val history = listOf(UIMessage.system("persona"), UIMessage.user("later"), UIMessage.assistant("ok"))
        val original = history.toList()
        val request = beginProactiveRequest(history, "device context")
        assertEquals(original, history)
        assertEquals(original, request.dropLast(1))
        assertEquals(MessageRole.USER, request.last().role)
        assertTrue(request.last().toText().contains("device context"))
        for (google in listOf(true, false)) {
            assertEquals("user", wire(request, google).last().jsonObject["role"]?.jsonPrimitive?.content)
        }
        val stream = beginProactiveAssistantTurn(request, null)
        assertNotEquals(history.last().id, stream.last().id)
        assertEquals(history.size + 1, request.size)
    }

    @Test fun `tool continuations retain paired results and do not need another trigger`() {
        val request = beginProactiveRequest(listOf(UIMessage.user("later"), UIMessage.assistant("ok")))
        val tool = UIMessagePart.Tool("call_1", "lookup", "{}", listOf(UIMessagePart.Text("found")))
        val continued = request + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        assertEquals(continued, prepareProactiveToolContinuation(continued))
        assertEquals(1, continued.count { it.toText().contains("<proactive_trigger>") })
        for (google in listOf(true, false)) {
            val result = wire(continued, google)
            assertEquals("user", result.last().jsonObject["role"]?.jsonPrimitive?.content)
            val parts = result.last().jsonObject[if (google) "parts" else "content"]!!.jsonArray
            if (google) assertTrue(parts.first().jsonObject.containsKey("functionResponse"))
            else assertEquals("call_1", parts.first().jsonObject["tool_use_id"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `initial wake follows a completed historical tool turn with final text`() {
        val tool = UIMessagePart.Tool("old_call", "lookup", "{}", listOf(UIMessagePart.Text("old result")))
        val historical = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool, UIMessagePart.Text("done")))
        val request = beginProactiveRequest(listOf(UIMessage.user("lookup"), historical))
        for (google in listOf(true, false)) {
            val result = wire(request, google)
            assertEquals("user", result.last().jsonObject["role"]?.jsonPrimitive?.content)
            assertEquals(if (google) "model" else "assistant",
                result[result.lastIndex - 1].jsonObject["role"]?.jsonPrimitive?.content)
        }
        assertEquals(historical, request[1])
    }

    @Test fun `empty and user-ended histories also receive an explicit background event`() {
        for (history in listOf(emptyList(), listOf(UIMessage.user("later")))) {
            val result = beginProactiveRequest(history)
            assertEquals(history.size + 1, result.size)
            assertEquals(MessageRole.USER, result.last().role)
            assertTrue(result.last().toText().contains("不是对方的新发言"))
        }
    }

    @Test fun `tool step with trailing assistant content cannot become an unsupported prefill`() {
        val tool = UIMessagePart.Tool("current", "lookup", "{}", listOf(UIMessagePart.Text("result")))
        val turn = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool, UIMessagePart.Text("continuing")))
        val history = listOf(UIMessage.user("background event"), turn)
        val request = prepareProactiveToolContinuation(history)
        assertEquals(history, request.dropLast(1))
        for (google in listOf(true, false)) {
            assertEquals("user", wire(request, google).last().jsonObject["role"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `unexecuted tool turn is never followed by a synthetic user`() {
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("pending", "lookup", "{}", emptyList()),
        ))
        val request = prepareProactiveToolContinuation(listOf(UIMessage.user("event"), pending))
        assertEquals(2, request.size)
        assertEquals(MessageRole.ASSISTANT, request.last().role)
    }
}
