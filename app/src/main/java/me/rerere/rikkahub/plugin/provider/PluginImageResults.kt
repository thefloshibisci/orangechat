package me.rerere.rikkahub.plugin.provider

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.GifContactSheet
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.plugin.data.PluginDataStore
import java.io.File
import java.util.UUID

/** Resolves only the calling plugin's chunked image assets. Never accepts paths or remote URLs. */
class PluginImageResults internal constructor(private val context: Context, private val imagesDir: () -> File) {
    constructor(context: Context, filesManager: FilesManager) : this(context, { filesManager.getImagesDir() })
    suspend fun convert(pluginId: String, result: JsonElement): List<UIMessagePart> = withContext(Dispatchers.IO) {
        val root = result as? JsonObject ?: return@withContext listOf(UIMessagePart.Text(result.toString()))
        val descriptor = root["_orange_image"] ?: return@withContext listOf(UIMessagePart.Text(result.toString()))
        val clean = root.toMutableMap().apply { remove("_orange_image") }
        val store = PluginDataStore(context, pluginId)
        var output: File? = null
        val image = try {
            val spec = descriptor as? JsonObject ?: error("Invalid image descriptor")
            require((spec["version"] as? JsonPrimitive)?.intOrNull == 1)
            val key = (spec["asset_key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            require(Regex("[A-Za-z0-9_.-]{1,160}").matches(key))
            val metaText = store.getData(key) ?: error("Image asset is missing")
            require(metaText.length <= 4096)
            val meta = Json.parseToJsonElement(metaText).jsonObject
            val count = (meta["chunks"] as? JsonPrimitive)?.intOrNull ?: error("Missing chunks")
            require(count in 1..700)
            // 逐块流式解码：不再先拼成一整段 base64 字符串（最多 16M 字符 ≈ 32MB）再整体复制解码，
            // 峰值只剩解码后的字节和当前一块。块按需读取，Base64InputStream 能处理任意切块边界。
            var encodedLength = 0
            var index = 0
            val chunks = object : java.util.Enumeration<java.io.InputStream> {
                override fun hasMoreElements() = index < count
                override fun nextElement(): java.io.InputStream {
                    val chunk = store.getData("$key.${index++}") ?: error("Image chunk is missing")
                    encodedLength += chunk.length
                    require(chunk.length <= 24000 && encodedLength <= 16000000)
                    return chunk.toByteArray(Charsets.ISO_8859_1).inputStream()
                }
            }
            var bytes = android.util.Base64InputStream(java.io.SequenceInputStream(chunks), Base64.DEFAULT)
                .use { it.readBytes() }
            require(bytes.isNotEmpty() && bytes.size <= 12000000)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192)
            require(bounds.outWidth.toLong() * bounds.outHeight <= 16777216)
            val extension = when (bounds.outMimeType) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> {
                    // Models only see one still; send sampled frames as a single PNG sheet.
                    val sheet = GifContactSheet.render(bytes)
                    bytes = sheet.png
                    clean["image_frames_note"] = JsonPrimitive(
                        if (sheet.frames > 1) {
                            "Animated GIF (${sheet.durationMs} ms). The attached image shows ${sheet.frames} " +
                                "frames sampled evenly in time order, left to right, top to bottom."
                        } else {
                            "Single-frame GIF, converted to a still image."
                        }
                    )
                    "png"
                }
                else -> error("Unsupported image format")
            }
            val file = File(imagesDir(), "plugin_${UUID.randomUUID()}.$extension")
            output = file
            file.writeBytes(bytes)
            store.setData("orange.image.attached.$key", "true")
            clean["image_attached"] = JsonPrimitive(true)
            UIMessagePart.Image(file.toUri().toString())
        } catch (e: CancellationException) {
            output?.delete()
            throw e
        } catch (e: Exception) {
            output?.delete()
            clean.remove("image_frames_note")
            clean["image_attached"] = JsonPrimitive(false)
            clean["image_attachment_error"] = JsonPrimitive("Image attachment could not be loaded; the generation result is unchanged. Retrieve the existing asset; do not generate again.")
            null
        }
        buildList {
            add(UIMessagePart.Text(JsonObject(clean).toString()))
            if (image != null) add(image)
        }
    }
}
