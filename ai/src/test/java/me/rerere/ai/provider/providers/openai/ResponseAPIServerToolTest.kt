package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ResponseAPIServerToolTest {
    private val api = ResponseAPI(OkHttpClient())
    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    private fun call(arguments: String = "{}") = obj(
        """{"id":"item_1","type":"function_call","call_id":"call_1","name":"search","arguments":${kotlinx.serialization.json.JsonPrimitive(arguments)}}"""
    )
    private fun event(type: String, item: JsonObject) = obj("""{"type":"$type","output_index":0,"item":$item}""")
    private fun output(value: String) = obj("""{"type":"function_call_output","call_id":"call_1","output":$value}""")

    @Test
    fun `nonstream server output is paired by call id even before its call`() {
        val response = obj("""{"output":[${output("\"answer\"")},${call()}]}""")
        val tool = api.parseResponseOutput(response).choices.single().message!!.getTools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("answer", (tool.output.single() as UIMessagePart.Text).text)
        assertTrue(tool.isExecuted)
    }

    @Test
    fun `empty server output marks execution while missing output remains pending`() {
        for (value in listOf("null", "\"\"")) {
            val response = obj("""{"output":[${call()},${output(value)}]}""")
            val tool = api.parseResponseOutput(response).choices.single().message!!.getTools().single()
            assertTrue(tool.isExecuted)
            assertEquals("", (tool.output.single() as UIMessagePart.Text).text)
        }
        val pending = api.parseResponseOutput(obj("""{"output":[${call()}]}"""))
        assertFalse(pending.choices.single().message!!.getTools().single().isExecuted)
    }

    @Test
    fun `array and object tool results retain their content`() {
        assertEquals("a\nb", output("""[{"type":"input_text","text":"a"},{"type":"input_text","text":"b"}]""").functionCallOutputText())
        assertEquals("""{"ok":true}""", output("""{"ok":true}""").functionCallOutputText())
    }

    @Test
    fun `repeated snapshots merge into one call without duplicated arguments`() {
        val state = ResponseToolStreamState()
        var message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val events = listOf(
            event("response.output_item.added", call("{")),
            obj("""{"type":"response.function_call_arguments.done","item_id":"item_1","arguments":"{}"}"""),
            event("response.output_item.done", call()),
        )
        for (event in events) api.parseResponseDelta(event, state)?.let { message += it }
        val tool = message.getTools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("{}", tool.input)
        assertFalse(tool.isExecuted)
    }

    @Test
    fun `provisional output waits for done and is emitted once`() {
        val state = ResponseToolStreamState()
        var message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        message += api.parseResponseDelta(event("response.output_item.added", call()), state)!!
        assertNull(api.parseResponseDelta(event("response.output_item.added", output("\"\"")), state))
        message += api.parseResponseDelta(event("response.output_item.done", output("\"final\"")), state)!!
        assertNull(api.parseResponseDelta(event("response.output_item.done", output("\"final\"")), state))
        val complete = api.parseResponseDelta(obj("""{"type":"response.completed","response":{"output":[${output("\"final\"")}],"usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5}}}"""), state)!!
        message += complete
        assertEquals(5, complete.usage!!.totalTokens)
        assertEquals("final", (message.getTools().single().output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `completion recovers output and request states remain isolated`() {
        val state = ResponseToolStreamState()
        state.functionCall(call(), 0)
        assertEquals("call_1", state.callId("missing_item_id", 0))
        assertEquals("item_1", ResponseToolStreamState().callId("item_1", 0))
        val chunk = api.parseResponseDelta(obj("""{"type":"response.completed","response":{"output":[${output("null")}]}}"""), state)!!
        val tool = chunk.choices.single().delta!!.getTools().single()
        assertEquals("call_1", tool.toolCallId)
        assertTrue(tool.isExecuted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inconsistent argument snapshots fail instead of silently corrupting a call`() {
        val state = ResponseToolStreamState()
        state.argumentSuffix("call", "abc")
        state.argumentSuffix("call", "xyz")
    }
    @Test
    fun `request history strips blank blocks and gives empty tools a result marker`() {
        val response = api.buildMessages(listOf(
            UIMessage.user("  "),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(""), UIMessagePart.Text(" keep "))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("call_1", "test", "", listOf(UIMessagePart.Text(""))))),
        ))
        assertEquals(3, response.size)
        assertEquals(" keep ", response[0].jsonObject["content"]!!.toString().trim('"'))
        assertTrue(response[2].jsonObject["output"]!!.toString().contains("Tool completed"))
    }

}
