package me.rerere.rikkahub.data.gongdu

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.ai.transformers.historyMediaTransformers
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class GongduToolResultTest {
    @Test fun commentReceiptsCannotBecomePagedListsOrUnqualifiedSuccess() {
        for (raw in listOf("""[{"type":"text","text":"评论发表成功"}]""",
            """{"_gongdu_compact":1,"omitted":1,"items":[]}""",
            """{"comment_id":"target","content":"回复内容"}""")) {
            val output = text(compact(raw, "reply_notification"))
            val result = Json.parseToJsonElement(output).jsonObject
            assertEquals("submitted_unconfirmed", result.str("status"))
            assertEquals(JsonPrimitive(false), result["retry_safe"])
            assertFalse(result.containsKey("result_ref"))
            assertEquals(output, text(compact(output, "reply_notification")))
        }
    }
    @Test fun verifiedCurrentAccountVisibilityDoesNotImplyPublicVisibility() {
        val result = Json.parseToJsonElement(text(compact(
            """{"status":"visible_to_current_account","public_visibility":"unverified"}""", "post_comment_to_feed"))).jsonObject
        assertEquals("visible_to_current_account", result.str("status"))
        assertEquals("unverified", result.str("public_visibility"))
    }
    private val server = Uuid.random()
    private val assistant = Assistant(mcpServers = setOf(server))
    private val settings = Settings.dummy().copy(gongdu = GongduSetting(serverId = server), assistants = listOf(assistant))
    private fun context() = TransformerContext(RuntimeEnvironment.getApplication(), Model(), assistant, settings)
    private val token = "complete-token-" + "q".repeat(200)
    private fun listing(count: Int = 35): String = buildJsonObject {
        put("feeds", buildJsonArray { repeat(count) { index -> add(buildJsonObject {
            put("id", "note-$index"); put("xsecToken", token)
            put("noteCard", buildJsonObject {
                put("displayTitle", "第${index}条中文笔记"); put("type", "video")
                put("user", buildJsonObject { put("userId", "author-$index"); put("nickname", "作者$index") })
                put("imageList", buildJsonArray { repeat(5) { add(buildJsonObject {
                    put("urlDefault", "https://cdn.example/" + "tracking".repeat(500))
                }) } })
            })
        }) } })
    }.toString()
    private fun compact(raw: String, name: String = "list_feeds") =
        GongduToolResultPolicy.compact(server, assistant.id, name, listOf(UIMessagePart.Text(raw)))
    private fun text(parts: List<UIMessagePart>) = parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
    private fun tool(raw: String, otherServer: Uuid = server) = UIMessagePart.Tool(
        "call-id", ToolNaming.buildMcpToolName(otherServer, "list_feeds"), "{}", listOf(UIMessagePart.Text(raw)))

    @Test fun largeListBecomesBoundedValidJsonWithCompleteReferences() {
        val raw = listing()
        val reduced = text(compact(raw))
        val result = Json.parseToJsonElement(reduced).jsonObject
        assertTrue(raw.length > 100000)
        assertTrue(reduced.length <= GongduToolResultPolicy.RESULT_LIMIT)
        assertEquals(35, result["source_total"]!!.jsonPrimitive.int)
        assertEquals(8, result["returned"]!!.jsonPrimitive.int)
        assertTrue(result["has_more"]!!.jsonPrimitive.boolean)
        val item = result["items"]!!.jsonArray.first().jsonObject
        assertEquals("note-0", item.str("feed_id"))
        assertEquals(token, item.str("xsec_token"))
        assertFalse(reduced.contains("tracking"))
    }
    @Test fun nextPageReadsSameSnapshotWithoutFetchingPlatformAgain() {
        val first = Json.parseToJsonElement(text(compact(listing()))).jsonObject
        val next = Json.parseToJsonElement(GongduToolResultPolicy.page(server, assistant.id, first.str("result_ref"), 8, 8)).jsonObject
        assertEquals("note-8", next["items"]!!.jsonArray.first().jsonObject.str("feed_id"))
        assertEquals(16, next["next_offset"]!!.jsonPrimitive.int)
        assertEquals(35, next["cached_total"]!!.jsonPrimitive.int)
    }
    @Test fun cacheCannotBeReadByAnotherAssistantOrServer() {
        val ref = Json.parseToJsonElement(text(compact(listing()))).jsonObject.str("result_ref")
        assertTrue(GongduToolResultPolicy.page(server, Uuid.random(), ref, 0, 8).contains("无权限"))
        assertTrue(GongduToolResultPolicy.page(Uuid.random(), assistant.id, ref, 0, 8).contains("无权限"))
    }
    @Test fun invalidOffsetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { GongduToolResultPolicy.page(server, assistant.id, "missing", -1, 8) }
        assertThrows(IllegalArgumentException::class.java) { GongduToolResultPolicy.page(server, assistant.id, "missing", 0, 30) }
    }
    @Test fun textModelCanReadLinksAndCachedPagesWithoutWorkspaceOrVideoTools() {
        val configured = settings.copy(mcpServers = listOf(McpServerConfig.StreamableHTTPServer(id = server, url = "https://example.test/mcp")))
        val names = gongduTools(configured, assistant.id).map { it.name }.toSet()
        assertTrue("gongdu_read_link" in names); assertTrue("gongdu_result_page" in names)
        assertFalse("gongdu_read_video" in names)
        assertTrue(gongduTools(configured.copy(assistants = listOf(assistant.copy(mcpServers = emptySet()))), assistant.id).isEmpty())
    }
    @Test fun followToolsRetainPublicActionApproval() {
        assertTrue(GongduRepository.initialApproval(server, server, "follow_user"))
        assertTrue(GongduRepository.initialApproval(server, server, "follow_back"))
        assertFalse(GongduRepository.initialApproval(server, Uuid.random(), "follow_back"))
    }
    @Test fun modelDetailDropsCodecDataWithoutChangingOriginalResponse() {
        val raw = """{"note":{"id":"detail","desc":"正文","video":{"media":{"stream":{"h264":[{"masterUrl":"https://v.xhscdn.com/v.mp4"}]}}}}}"""
        val reduced = text(compact(raw, "get_feed_detail"))
        assertTrue(reduced.contains("正文")); assertTrue(reduced.contains("video_available"))
        assertFalse(reduced.contains("masterUrl"))
        assertEquals("https://v.xhscdn.com/v.mp4", GongduVideoReader.source(Json.parseToJsonElement(raw).jsonObject).url)
    }
    @Test fun businessFailureAndQrImageArePreserved() {
        val error = """{"success":false,"message":"已点击提交，尚未确认，不要重复发布"}"""
        val image = UIMessagePart.Image("file:///qr.png")
        val parts = listOf(UIMessagePart.Text(error), image)
        assertEquals(parts, GongduToolResultPolicy.compact(server, assistant.id, "get_login_qrcode", parts))
    }
    @Test fun oversizeReferenceIsOmittedRatherThanSilentlyTruncated() {
        val raw = """{"feeds":[{"id":"id","xsecToken":"${"x".repeat(3000)}"}]}"""
        val result = Json.parseToJsonElement(text(compact(raw))).jsonObject
        assertEquals(0, result["returned"]!!.jsonPrimitive.int)
        assertEquals(1, result["omitted"]!!.jsonPrimitive.int)
    }
    @Test fun manyTextChunksCannotExceedResultBudget() {
        val parts = (0 until 300).map { UIMessagePart.Text("x".repeat(12000)) }
        val result = GongduToolResultPolicy.compact(server, assistant.id, "unknown", parts)
        assertTrue(text(result).length <= GongduToolResultPolicy.RESULT_LIMIT)
    }
    @Test fun hugeDetailNeverReturnsPartialTokensOrMalformedJson() {
        val raw = buildJsonObject { put("note", buildJsonObject {
            put("id", "detail"); put("xsec_token", token)
            put("desc", "正文".repeat(20000))
            put("comments", buildJsonArray { repeat(8) { add(buildJsonObject { put("content", "评论".repeat(20000)) }) } })
        }) }.toString()
        val result = text(compact(raw, "get_feed_detail"))
        assertTrue(result.length <= GongduToolResultPolicy.RESULT_LIMIT)
        val data = Json.parseToJsonElement(result).jsonObject["data"]!!.jsonObject
        assertEquals(token, (data.obj("note") ?: data).str("xsec_token"))
    }
    @Test fun alreadyCompactedResultIsIdempotent() {
        val once = compact(listing())
        assertEquals(once, GongduToolResultPolicy.compact(server, assistant.id, "list_feeds", once))
    }
    @Test fun historyCopyShrinksButRetainsToolIdentityAndSavedData() = runBlocking {
        val raw = listing()
        val originalTool = tool(raw)
        val stored = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(originalTool)))
        val result = GongduToolResultTransformer.transform(context(), stored)
        val adapted = result.single().parts.single() as UIMessagePart.Tool
        assertEquals(originalTool.toolCallId, adapted.toolCallId); assertEquals(originalTool.input, adapted.input)
        assertTrue(adapted.isExecuted); assertEquals(raw, text(originalTool.output))
        assertTrue(text(adapted.output).length < raw.length / 10)
        assertEquals(result, GongduToolResultTransformer.transform(context(), result))
    }
    @Test fun otherServersSameToolNameIsUntouched() = runBlocking {
        val original = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(listing(), Uuid.random()))))
        assertEquals(original, GongduToolResultTransformer.transform(context(), original))
    }
    @Test fun sharedPipelineBoundsTotalGongduHistoryAndPreservesNestedAudio() = runBlocking {
        val audio = UIMessagePart.Audio("file:///audio.wav")
        val messages = (0 until 25).map { UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(tool(listing()).copy(output = listOf(UIMessagePart.Text(listing()), audio)))) }
        val transformed = GongduToolResultTransformer.transform(context(), messages)
        val tools = transformed.map { it.parts.single() as UIMessagePart.Tool }
        assertTrue(tools.sumOf { text(it.output).length } <= GongduToolResultPolicy.HISTORY_LIMIT)
        assertTrue(tools.all { it.isExecuted && it.output.last() == audio })
        assertTrue(historyMediaTransformers.contains(GongduToolResultTransformer))
        assertTrue(text(tools.last().output).contains("note-0"))
    }
}
