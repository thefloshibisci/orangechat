package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyToolArgumentsTest {
    private fun history(input: String) = listOf(
        UIMessage.user("拍一拍"),
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("call-pat", "avatar_interaction", input, listOf(UIMessagePart.Text("done")))
        )),
        UIMessage.user("继续聊天"),
    )

    @Test fun `chat completions replays no argument tools and preserves existing arguments`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java, Model::class.java)
            .apply { isAccessible = true }
        for (input in listOf("", " \n\t", "{}", "{\"value\":42}")) {
            val messages = history(input)
            val body = method.invoke(api, messages, Model(modelId = "test")) as JsonArray
            val call = body.map { it.jsonObject }.first { "tool_calls" in it }["tool_calls"]!!
                .jsonArray.single().jsonObject["function"]!!.jsonObject
            assertEquals(input.ifBlank { "{}" }, call["arguments"]!!.jsonPrimitive.content)
            assertEquals(input, (messages[1].parts.single() as UIMessagePart.Tool).input)
            assertEquals("继续聊天", body.last().jsonObject["content"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `both responses APIs replay no argument tools without rewriting history`() {
        val builders: List<(List<UIMessage>) -> JsonArray> = listOf(
            ResponseAPI(OkHttpClient())::buildMessages,
            CodexResponseAPI(OkHttpClient())::buildMessages,
        )
        for (build in builders) for (input in listOf("", " \n\t", "{}", "{\"value\":42}")) {
            val messages = history(input)
            val calls = build(messages).map { it.jsonObject }
            val call = calls.single { it["type"]?.jsonPrimitive?.content == "function_call" }
            assertEquals(input.ifBlank { "{}" }, call["arguments"]!!.jsonPrimitive.content)
            assertEquals("call-pat", call["call_id"]!!.jsonPrimitive.content)
            assertEquals(1, calls.count { it["type"]?.jsonPrimitive?.content == "function_call_output" })
            assertEquals(input, (messages[1].parts.single() as UIMessagePart.Tool).input)
        }
    }
}
