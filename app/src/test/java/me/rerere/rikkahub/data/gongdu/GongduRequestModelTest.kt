package me.rerere.rikkahub.data.gongdu

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GongduRequestModelTest {
    private val server = Uuid.random()
    private val text = Model(modelId = "text-default", inputModalities = listOf(Modality.TEXT))
    private val vision = Model(modelId = "vision-conversation", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
    private val assistant = Assistant(chatModelId = text.id, mcpServers = setOf(server))
    private val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant),
        providers = listOf(ProviderSetting.OpenAI(models = listOf(text, vision))),
        gongdu = GongduSetting(serverId = server),
        mcpServers = listOf(McpServerConfig.StreamableHTTPServer(id = server, url = "https://test.invalid/mcp")))

    private fun imageArgs(count: Int = 1): JsonObject {
        val raw = buildJsonObject { put("note", buildJsonObject {
            put("id", "image-note"); put("desc", "cached only")
            put("imageList", buildJsonArray { repeat(count) { index ->
                add(buildJsonObject { put("urlDefault", "https://sns.xhscdn.com/$index.jpg") })
            } })
        }) }.toString()
        val parts = GongduToolResultPolicy.compact(server, assistant.id, "get_feed_detail", listOf(UIMessagePart.Text(raw)))
        val data = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        return buildJsonObject {
            put("result_ref", data.getValue("images").jsonObject.getValue("result_ref"))
            put("offset", 0); put("limit", 1); put("include_images", true)
        }
    }
    private fun view(model: Model) = settings.withConversationConfig(Conversation(assistantId = assistant.id, messageNodes = emptyList(),
        config = ConversationConfig(chatModelId = model.id, mcpServers = setOf(server))))

    @Test fun imageToolDefaultsToSixAndContinuesAtTheSeventhImage() = runBlocking {
        val tool = gongduResultTools(view(vision), assistant.id) { settings }.single()
        val args = JsonObject(imageArgs(9).filterKeys { it != "limit" })
        val first = tool.execute(args)
        assertEquals(6, first.filterIsInstance<UIMessagePart.Image>().size)
        val page = Json.parseToJsonElement((first.first() as UIMessagePart.Text).text).jsonObject
        assertEquals(6, page["next_offset"]!!.jsonPrimitive.int)
        val second = tool.execute(JsonObject(args + ("offset" to JsonPrimitive(6))))
        assertEquals((6..8).map { "https://sns.xhscdn.com/$it.jpg" }, second.filterIsInstance<UIMessagePart.Image>().map { it.url })
    }

    @Test fun imagesUseConversationEvenWhenDefaultHasNoVision() = runBlocking {
        val tools = gongduResultTools(view(vision), assistant.id) { settings }
        assertTrue(tools.single().execute(imageArgs()).any { it is UIMessagePart.Image })
        assertEquals(text.id, settings.assistants.single().chatModelId)
    }

    @Test fun missingDefaultDoesNotBlockValidConversationVision() = runBlocking {
        val current = settings.copy(assistants = listOf(assistant.copy(chatModelId = Uuid.random())))
        val chat = Conversation(assistantId = assistant.id, messageNodes = emptyList(), config = ConversationConfig(chatModelId = vision.id, mcpServers = setOf(server)))
        val tools = gongduResultTools(current.withConversationConfig(chat), assistant.id) { current }
        assertTrue(tools.single().execute(imageArgs()).any { it is UIMessagePart.Image })
    }

    @Test fun textConversationCannotBorrowVisionFromDefault() = runBlocking {
        val current = settings.copy(assistants = listOf(assistant.copy(chatModelId = vision.id)))
        val tool = gongduResultTools(view(text), assistant.id) { current }.single()
        val failure = runCatching { tool.execute(imageArgs()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("未启用识图"))
    }

    @Test fun liveRevocationStillStopsAnAlreadyBuiltImageTool() = runBlocking {
        var live = settings
        val tool = gongduResultTools(view(vision), assistant.id) { live }.single()
        live = live.copy(assistants = listOf(assistant.copy(mcpServers = emptySet())))
        val failure = runCatching { tool.execute(imageArgs()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("工具已关闭"))
    }
}
