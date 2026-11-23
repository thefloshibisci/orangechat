package me.rerere.rikkahub.plugin.provider

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.model.PluginToolDefinition
import me.rerere.rikkahub.plugin.model.PluginToolParameter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginChatImageSelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val parameters = listOf(
        PluginToolParameter("bytes", "string", source = "chat_image_base64"),
        PluginToolParameter("mime", "string", source = "chat_image_mime"),
    )

    private fun image(name: String, color: Int, width: Int = 64, height: Int = 32): File {
        val file = File(context.filesDir, "images/selection/$name.png").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        return file
    }

    private fun history(): Pair<List<UIMessage>, File> {
        val tool = image("nai", Color.MAGENTA)
        return listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool("draw-123", "nai_generate", "{}", listOf(UIMessagePart.Image(tool.toURI().toString()))),
            )),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(image("newer-user", Color.BLACK).toURI().toString()))),
        ) to tool
    }

    private fun metadata(parts: List<UIMessagePart>) = Json.parseToJsonElement(
        (parts.first() as UIMessagePart.Text).text,
    ).jsonObject

    @Test fun olderPluginManifestStillExposesHostSelectorAndHidesBytes() {
        val old = PluginToolDefinition("upload", "upload", parameters)
        val schema = pluginToolParameters(old)
        assertEquals("string", schema["attachment_id"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(schema.containsKey("bytes")); assertFalse(schema.containsKey("mime"))
        val ordinary = PluginToolDefinition("normal", "normal", listOf(PluginToolParameter("title", "string")))
        assertEquals(setOf("title"), pluginToolParameters(ordinary).keys)
    }

    @Test fun exactToolImageIdReachesInjectedBytesAndReceiptEvenWithNewerUserImage() = runBlocking {
        val service = PluginChatImages(context)
        val (messages, file) = history()
        val browser = service.browserTool(messages, "chat")
        val rows = metadata(browser.execute(buildJsonObject { put("action", "list") }))["images"]!!.jsonArray
        val chosen = rows.first().jsonObject
        assertEquals("tool", chosen["origin"]!!.jsonPrimitive.content)
        assertEquals("nai_generate", chosen["tool_name"]!!.jsonPrimitive.content)
        assertEquals(64, chosen["width"]!!.jsonPrimitive.int)
        var receipt: JsonObject? = null
        val input = service.resolveToolParameters(parameters, buildJsonObject {
            put("attachment_id", chosen.getValue("attachment_id")); put("bytes", "forged")
        }, messages, "chat", listOf("conversation_sync"), onImageResolved = { receipt = it })
        val bytes = Base64.decode(input["bytes"]!!.jsonPrimitive.content, Base64.DEFAULT)
        assertArrayEquals(file.readBytes(), bytes)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(Color.MAGENTA, decoded.getPixel(0, 0)); decoded.recycle()
        assertEquals(chosen["attachment_id"], receipt!!["attachment_id"])
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(hash, receipt!!["sha256"]!!.jsonPrimitive.content)
        assertEquals(hash, receipt!!["original_sha256"]!!.jsonPrimitive.content)
    }

    @Test fun missingNullBlankAndInvalidSelectorsNeverChooseTheLatestImage() = runBlocking {
        val service = PluginChatImages(context)
        val (messages) = history()
        for (selector in listOf<JsonElement?>(null, JsonNull, JsonPrimitive(""), JsonPrimitive("wrong"), JsonPrimitive(123))) {
            var received = false
            val result = runCatching { service.resolveToolParameters(parameters, buildJsonObject {
                selector?.let { put("attachment_id", it) }
            }, messages, "chat", listOf("conversation_sync"), onImageResolved = { received = true }) }
            assertTrue("selector=$selector", result.isFailure)
            assertFalse(received)
        }
        val single = service.resolveToolParameters(parameters, JsonObject(emptyMap()), messages.take(1), "chat", listOf("conversation_sync"))
        assertTrue(single["bytes"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test fun previewKeepsWholePortraitAndDoesNotEnterGalleryAgain() = runBlocking {
        val service = PluginChatImages(context)
        val file = image("portrait", Color.MAGENTA, 400, 1600)
        val bitmap = BitmapFactory.decodeFile(file.path).copy(Bitmap.Config.ARGB_8888, true)
        // Distinct top and bottom edges detect center-crop or selection of another file.
        for (x in 0 until bitmap.width) for (y in 0 until 40) {
            bitmap.setPixel(x, y, Color.RED); bitmap.setPixel(x, bitmap.height - y - 1, Color.BLUE)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(file.toURI().toString()))))
        val id = service.gallerySources(messages, "chat").single().id
        val result = service.browserTool(messages, "chat").execute(buildJsonObject {
            put("action", "preview"); put("attachment_id", id)
        })
        val meta = metadata(result)
        assertEquals(id, meta["attachment_id"]!!.jsonPrimitive.content)
        assertEquals(400, meta["width"]!!.jsonPrimitive.int); assertEquals(1600, meta["height"]!!.jsonPrimitive.int)
        val preview = File(requireNotNull((result[1] as UIMessagePart.Image).url.toUri().path))
        val decoded = BitmapFactory.decodeFile(preview.path)
        assertEquals(160, decoded.width); assertEquals(640, decoded.height)
        assertEquals(Color.RED, decoded.getPixel(80, 0)); assertEquals(Color.BLUE, decoded.getPixel(80, 639)); decoded.recycle()
        val withPreview = messages + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("preview", "chat_images", "{}", result)))
        assertEquals(listOf(id), service.gallerySources(withPreview, "chat").map { it.id })
    }

    @Test fun previewRejectsWrongBranchMissingIdAndChangedAttachment() = runBlocking {
        val service = PluginChatImages(context)
        val (messages) = history()
        val id = service.gallerySources(messages, "chat").first().id
        for (params in listOf(
            buildJsonObject { put("action", "preview") },
            buildJsonObject { put("action", "preview"); put("attachment_id", id) },
        )) assertFalse(metadata(service.browserTool(messages, "foreign").execute(params))["success"]!!.jsonPrimitive.boolean)
        val edited = messages.take(1).map { it.copy(parts = listOf(UIMessagePart.Image(image("edited", Color.GREEN).toURI().toString()))) }
        assertFalse(metadata(service.browse(buildJsonObject { put("action", "preview"); put("attachment_id", id) }, edited, "chat"))["success"]!!.jsonPrimitive.boolean)
    }

    @Test fun unreadableImageDoesNotHideTheOtherListEntriesAndSnapshotIsFixed() = runBlocking {
        val service = PluginChatImages(context)
        val (messages) = history()
        val mutable = messages.toMutableList()
        val browser = service.browserTool(mutable, "chat")
        mutable.clear()
        assertEquals(2, metadata(browser.execute(buildJsonObject { put("action", "list") }))["images"]!!.jsonArray.size)
        val broken = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///missing.png")))
        val rows = metadata(service.browse(buildJsonObject { put("action", "list") }, listOf(broken) + messages, "chat"))["images"]!!.jsonArray
        assertEquals(3, rows.size)
        assertFalse(rows.first().jsonObject["available"]!!.jsonPrimitive.boolean)
        assertTrue(rows.last().jsonObject["available"]!!.jsonPrimitive.boolean)
    }
}
