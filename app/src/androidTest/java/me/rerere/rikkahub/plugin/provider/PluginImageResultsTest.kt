package me.rerere.rikkahub.plugin.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.GifContactSheet
import me.rerere.rikkahub.plugin.data.PluginDataStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PluginImageResultsTest {
    @Test fun animatedGifSamplesBothFramesInOrder() {
        val sheet = GifContactSheet.render(Base64.decode(TWO_FRAME_GIF, Base64.DEFAULT))
        assertEquals(2, sheet.frames)
        val bitmap = BitmapFactory.decodeByteArray(sheet.png, 0, sheet.png.size)
        try {
            assertEquals(Color.RED, bitmap.getPixel(1, 1))
            assertEquals(Color.BLUE, bitmap.getPixel(9, 1))
            assertTrue(bitmap.width <= 728 && bitmap.height <= 728)
        } finally { bitmap.recycle() }
    }

    private companion object {
        const val TWO_FRAME_GIF =
            "R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQBCgABACwAAAAABAAEAIEAAP8AAAAAAAAAAAAICQABCBxIsCCAgAA7"
    }

    @Test fun attachesOwnedImageAndRejectsMissingOrForeignAssets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "image_test_${UUID.randomUUID()}"
        val store = PluginDataStore(context, owner)
        val directory = File(context.cacheDir, owner).apply { mkdirs() }
        val converter = PluginImageResults(context) { directory }
        val result = Json.parseToJsonElement("""{"success":true,"_orange_image":{"version":1,"asset_key":"test.image"}}""")
        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            bitmap.recycle()
            store.setData("test.image", """{"chunks":1}""")
            store.setData("test.image.0", Base64.encodeToString(bytes, Base64.NO_WRAP))
            val attached = converter.convert(owner, result)
            assertTrue(attached.any { it is UIMessagePart.Image })
            assertEquals("true", store.getData("orange.image.attached.test.image"))
            assertEquals(1, directory.listFiles()!!.size)
            assertFalse(converter.convert("${owner}_other", result).any { it is UIMessagePart.Image })
            store.deleteData("test.image.0")
            assertFalse(converter.convert(owner, result).any { it is UIMessagePart.Image })
            store.setData("test.image", """{"chunks":701}""")
            assertFalse(converter.convert(owner, result).any { it is UIMessagePart.Image })
            val plain = buildJsonObject { put("success", true) }
            assertEquals(listOf(UIMessagePart.Text(plain.toString())), converter.convert(owner, plain))

            // Two-frame 4x4 GIF (red, then blue) becomes a PNG contact sheet with a frames note.
            store.setData("test.image", """{"chunks":1}""")
            store.setData("test.image.0", TWO_FRAME_GIF)
            val gif = converter.convert(owner, result)
            val image = gif.filterIsInstance<UIMessagePart.Image>().single()
            assertTrue(image.url.endsWith(".png"))
            val meta = Json.parseToJsonElement((gif.first() as UIMessagePart.Text).text).jsonObject
            assertEquals(true, meta["image_attached"]?.jsonPrimitive?.boolean)
            assertTrue(meta["image_frames_note"]!!.jsonPrimitive.content.contains("GIF"))
        } finally {
            store.clearAll()
            directory.deleteRecursively()
        }
    }
}
