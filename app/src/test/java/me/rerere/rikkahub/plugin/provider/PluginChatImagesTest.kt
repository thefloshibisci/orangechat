package me.rerere.rikkahub.plugin.provider

import android.app.Application
import android.graphics.Bitmap
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginToolDefinition
import me.rerere.rikkahub.plugin.model.PluginToolParameter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginChatImagesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val pluginId = "com.nanzhi.naistudio"
    private fun message(uri: String, role: MessageRole = MessageRole.USER) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Image(uri)))
    private fun execute(service: PluginChatImages, action: String, messages: List<UIMessage>, id: String = "") =
        runBlocking { service.execute(pluginId, buildJsonObject {
            put("action", action); put("attachment_id", id)
        }, messages, "test-chat").jsonObject }

    @Test fun branchAndRoleIsolation() {
        val service = PluginChatImages(context)
        val old = message("file:///old.png")
        val selected = message("file:///new.png")
        val otherChat = message("file:///other.png")
        val assistant = message("file:///assistant.png", MessageRole.ASSISTANT)
        val branch = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            MessageNode(messages = listOf(old, selected), selectIndex = 1), MessageNode.of(assistant),
        )).currentMessages
        val listed = execute(service, "list", branch)["images"]!!.jsonArray
        assertEquals(1, listed.size)
        assertEquals(service.sources(listOf(selected), "test-chat").single().id, listed.single().jsonObject["attachment_id"]!!.jsonPrimitive.content)
        for (foreign in listOf(old, otherChat, assistant)) {
            val foreignId = service.sources(listOf(foreign.copy(role = MessageRole.USER)), "test-chat").single().id
            assertFalse(execute(service, "import", branch, foreignId)["success"]!!.jsonPrimitive.boolean)
        }
        val clonedChatId = service.sources(listOf(selected), "another-chat").single().id
        assertFalse(execute(service, "import", branch, clonedChatId)["success"]!!.jsonPrimitive.boolean)
        val editedSameMessage = selected.copy(parts = listOf(UIMessagePart.Image("file:///edited.png")))
        val staleId = service.sources(listOf(selected), "test-chat").single().id
        assertFalse(execute(service, "import", listOf(editedSameMessage), staleId)["success"]!!.jsonPrimitive.boolean)
        assertEquals(0, execute(service, "list", emptyList())["images"]!!.jsonArray.size)
    }

    @Test fun localImportsPreserveDefaultsAndDeduplicate() {
        val service = PluginChatImages(context)
        val store = PluginDataStore(context, pluginId)
        store.setData("nai.studio.v2", "unchanged-defaults")
        val directory = File(context.filesDir, "upload").apply { mkdirs() }
        for ((format, extension) in listOf(Bitmap.CompressFormat.PNG to "png", Bitmap.CompressFormat.JPEG to "jpg", Bitmap.CompressFormat.WEBP to "webp")) {
            val file = File(directory, "cat.$extension")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.CYAN)
            file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }; bitmap.recycle()
            val uri = "content://atelier-test/cat.$extension".toUri()
            shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { file.inputStream() }
            val m = message(uri.toString())
            val attachmentId = service.sources(listOf(m), "test-chat").single().id
            val first = execute(service, "import", listOf(m), attachmentId)
            assertTrue(first.toString(), first["success"]!!.jsonPrimitive.boolean)
            val second = execute(service, "import", listOf(m), attachmentId)
            assertEquals(first["image_id"], second["image_id"])
            val key = "nai.asset.${first["image_id"]!!.jsonPrimitive.content}"
            assertEquals("image/png", Json.parseToJsonElement(store.getData(key)!!).jsonObject["mime"]!!.jsonPrimitive.content)
            assertEquals("unchanged-defaults", store.getData("nai.studio.v2"))
            assertFalse(first.toString().contains("base64"))
        }
    }

    @Test fun imageDocumentsOnlyAndUntrustedPathsRejected() {
        val service = PluginChatImages(context)
        val m = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Document(url = "file:///outside/cat.png", fileName = "cat.png", mime = "image/png"),
            UIMessagePart.Document(url = "file:///outside/a.txt", fileName = "a.txt", mime = "text/plain"),
        ))
        val sources = service.sources(listOf(m), "test-chat")
        assertEquals(1, sources.size)
        assertFalse(execute(service, "import", listOf(m), sources.single().id)["success"]!!.jsonPrimitive.boolean)
    }
    private fun registerImage(name: String, width: Int, height: Int, format: Bitmap.CompressFormat, alpha: Boolean = false): UIMessage {
        val directory = File(context.filesDir, "upload").apply { mkdirs() }
        val file = File(directory, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (alpha) android.graphics.Color.TRANSPARENT else android.graphics.Color.CYAN)
        file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }; bitmap.recycle()
        val uri = "content://gallery-test/$name".toUri()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { file.inputStream() }
        return message(uri.toString())
    }

    @Test fun galleryUploadCopiesNewestImageAndScalesLargeOnes() = runBlocking {
        val service = PluginChatImages(context)
        val small = registerImage("small.png", 64, 64, Bitmap.CompressFormat.PNG)
        val large = registerImage("large.jpg", 3200, 1600, Bitmap.CompressFormat.JPEG)
        val history = listOf(small, large)
        val newest = service.prepareGalleryUpload(null, history, "test-chat")
        assertEquals("image/jpeg", newest.mime)
        assertEquals(1600, newest.width)
        assertEquals(800, newest.height)
        val picked = service.prepareGalleryUpload(service.sources(listOf(small), "test-chat").single().id, history, "test-chat")
        assertEquals("image/png", picked.mime)
        assertEquals(64, picked.width)
        val listed = service.galleryList(history, "test-chat").jsonObject["images"]!!.jsonArray
        assertEquals(2, listed.size)
    }

    @Test fun galleryUploadRejectsForeignBranchAndEmptyChat() = runBlocking {
        val service = PluginChatImages(context)
        val image = registerImage("cat.png", 32, 32, Bitmap.CompressFormat.PNG)
        val foreignId = service.sources(listOf(image), "other-chat").single().id
        assertTrue(runCatching { service.prepareGalleryUpload(foreignId, listOf(image), "test-chat") }.isFailure)
        assertTrue(runCatching { service.prepareGalleryUpload(null, emptyList(), "test-chat") }.isFailure)
        assertTrue(runCatching { service.prepareGalleryUpload(null, listOf(image), "") }.isFailure)
    }

    @Test fun hostParametersReplaceForgedValuesAndShareOneImageRead() = runBlocking {
        val service = PluginChatImages(context)
        val image = registerImage("host.png", 48, 32, Bitmap.CompressFormat.PNG, alpha = true)
        val tool = Json.decodeFromString<PluginToolDefinition>("""{
            "name":"upload","description":"copy image","parameters":[
                {"name":"bytes","type":"string","required":true,"source":"chat_image_base64"},
                {"name":"mime","type":"string","source":"chat_image_mime"},
                {"name":"images","type":"array","source":"chat_images_list"}
            ]
        }""")
        val uri = (image.parts.single() as UIMessagePart.Image).url.toUri()
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        var reads = 0
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { reads++; bytes.inputStream() }
        val supplied = buildJsonObject { put("bytes", "forged"); put("mime", "forged"); put("album", "keep") }
        val result = service.resolveToolParameters(tool.parameters, supplied, listOf(image), "chat", listOf("conversation_sync"))
        assertEquals(1, reads)
        assertEquals("image/png", result["mime"]!!.jsonPrimitive.content)
        assertArrayEquals(bytes, android.util.Base64.decode(result["bytes"]!!.jsonPrimitive.content, android.util.Base64.DEFAULT))
        assertEquals(1, result["images"]!!.jsonArray.size)
        assertEquals("keep", result["album"]!!.jsonPrimitive.content)
        assertEquals("forged", supplied["bytes"]!!.jsonPrimitive.content)
    }

    @Test fun hostParametersRequirePermissionAndConversationEvenForList() = runBlocking {
        val service = PluginChatImages(context)
        val parameters = listOf(PluginToolParameter("images", "array", source = "chat_images_list"))
        val image = registerImage("permission.png", 32, 32, Bitmap.CompressFormat.PNG)
        val noPermission = runCatching { service.resolveToolParameters(parameters, JsonObject(emptyMap()), listOf(image), "chat", emptyList()) }
        assertTrue(noPermission.exceptionOrNull()?.message.orEmpty().contains("conversation_sync"))
        val noConversation = runCatching { service.resolveToolParameters(parameters, JsonObject(emptyMap()), listOf(image), "", listOf("conversation_sync")) }
        assertTrue(noConversation.exceptionOrNull()?.message.orEmpty().contains("conversation"))
    }

    @Test fun invalidHostDeclarationsFailBeforeOpeningAttachments() = runBlocking {
        val service = PluginChatImages(context)
        for ((source, type) in listOf("chat_image_typo" to "string", "chat_images_list" to "string", "chat_image_mime" to "array")) {
            val parameters = listOf(
                PluginToolParameter("bytes", "string", source = "chat_image_base64"),
                PluginToolParameter("bad", type, source = source),
            )
            val result = runCatching { service.resolveToolParameters(parameters, JsonObject(emptyMap()), emptyList(), "chat", listOf("conversation_sync")) }
            val error = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(error, error.contains("source") || error.contains("type"))
        }
    }

    @Test fun hostImageSelectionRejectsForeignIdsAndMalformedParameters() = runBlocking {
        val service = PluginChatImages(context)
        val image = registerImage("selection.png", 32, 32, Bitmap.CompressFormat.PNG)
        val parameters = listOf(PluginToolParameter("bytes", "string", source = "chat_image_base64"))
        val foreignId = service.sources(listOf(image), "other").single().id
        val foreign = runCatching { service.resolveToolParameters(parameters, buildJsonObject { put("attachment_id", foreignId) }, listOf(image), "chat", listOf("conversation_sync")) }
        assertTrue(foreign.exceptionOrNull()?.message.orEmpty().contains("branch"))
        for (params in listOf(JsonPrimitive("invalid"), buildJsonObject { put("attachment_id", 123) })) {
            assertTrue(runCatching { service.resolveToolParameters(parameters, params, listOf(image), "chat", listOf("conversation_sync")) }.isFailure)
        }
    }
}
