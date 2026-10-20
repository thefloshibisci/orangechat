package me.rerere.rikkahub.plugin.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.data.PluginDataStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Only resolves attachments from the invoking tool surface's branch snapshot. */
class PluginChatImages(private val context: Context) {
    data class Source(val id: String, val messageId: String, val uri: String, val name: String)
    internal fun sources(messages: List<UIMessage>, conversationId: String): List<Source> = messages
        .filter { it.role == MessageRole.USER }
        .flatMap { message ->
            message.parts.mapIndexedNotNull { index, part ->
                val uri = when (part) {
                    is UIMessagePart.Image -> part.url
                    is UIMessagePart.Document -> if (part.mime.startsWith("image/") ||
                        part.fileName.substringAfterLast('.', "").lowercase() in listOf("png", "jpg", "jpeg", "webp")) part.url else null
                    else -> null
                } ?: return@mapIndexedNotNull null
                val identity = "$conversationId\n${message.id}\n$index\n$uri"
                val attachmentId = "attachment_" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                    .joinToString("") { "%02x".format(it) }.take(40)
                Source(attachmentId, message.id.toString(), uri,
                    (part as? UIMessagePart.Document)?.fileName ?: "image_${index + 1}")
            }
        }.takeLast(30)

    suspend fun execute(pluginId: String, params: JsonElement, messages: List<UIMessage>, conversationId: String): JsonElement =
        withContext(Dispatchers.IO) {
            try {
                require(pluginId == "com.nanzhi.naistudio") { "Unsupported plugin" }
                val p = params.jsonObject
                val available = if (conversationId.isNotBlank()) sources(messages, conversationId) else emptyList()
                when (p["action"]?.jsonPrimitive?.content) {
                    "list" -> buildJsonObject {
                        put("success", true)
                        putJsonArray("images") {
                            available.forEach { source -> add(buildJsonObject {
                                put("attachment_id", source.id)
                                put("message_id", source.messageId)
                                put("name", source.name)
                            }) }
                        }
                    }
                    "import" -> {
                        val source = available.singleOrNull { it.id == p["attachment_id"]?.jsonPrimitive?.content }
                            ?: error("Attachment is not in this conversation branch. List images first.")
                        val bytes = readAttachment(source)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                            bounds.outWidth.toLong() * bounds.outHeight <= 16777216) { "Unsupported image dimensions" }
                        require(bounds.outMimeType in listOf("image/png", "image/jpeg", "image/webp")) { "Unsupported image format" }
                        val encoded = if (bounds.outMimeType == "image/png") {
                            Base64.encodeToString(bytes, Base64.NO_WRAP)
                        } else {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Image decode failed")
                            try {
                                val out = ByteArrayOutputStream()
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            } finally { bitmap.recycle() }
                        }
                        require(encoded.length <= 16000000) { "PNG exceeds local asset limit" }
                        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        val id = "chat_${digest.take(40)}"
                        val store = PluginDataStore(context, pluginId)
                        val chunks = encoded.chunked(24000)
                        chunks.forEachIndexed { index, text -> store.setData("nai.asset.$id.$index", text) }
                        store.setData("nai.asset.$id", buildJsonObject {
                            put("chunks", chunks.size); put("mime", "image/png")
                            put("width", bounds.outWidth); put("height", bounds.outHeight)
                        }.toString())
                        val result = buildJsonObject {
                            put("success", true); put("image_id", id)
                            put("width", bounds.outWidth); put("height", bounds.outHeight)
                            put("generated", false); put("defaults_changed", false)
                        }
                        store.setData("nai.chat.latest", result.toString())
                        result
                    }
                    else -> error("Use action=list or import")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                buildJsonObject { put("success", false); put("error", e.message ?: "Import failed") }
            }
        }
    private fun readAttachment(source: Source): ByteArray {
        val uri = source.uri.toUri()
        val input = when (uri.scheme) {
            "file" -> {
                val file = File(requireNotNull(uri.path)).canonicalFile
                val roots = listOf("images", "upload").map { File(context.filesDir, it).canonicalFile }
                require(roots.any { file.path.startsWith(it.path + File.separator) }) { "Unsupported attachment location" }
                file.inputStream()
            }
            "content" -> context.contentResolver.openInputStream(uri) ?: error("Attachment cannot be opened")
            else -> error("Only local chat attachments can be imported")
        }
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(out.size() + count <= 12000000) { "Attachment exceeds 12 MB" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    /** Copy of a chat image for the gallery plugin: longest side <= 1600px; small PNGs keep transparency. */
    data class GalleryUpload(val base64: String, val mime: String, val width: Int, val height: Int)

    internal fun galleryList(messages: List<UIMessage>, conversationId: String): JsonElement = buildJsonObject {
        put("success", true)
        putJsonArray("images") {
            (if (conversationId.isNotBlank()) sources(messages, conversationId) else emptyList()).forEach { source ->
                add(buildJsonObject {
                    put("attachment_id", source.id); put("message_id", source.messageId); put("name", source.name)
                })
            }
        }
    }

    /** attachment_id blank = the newest image the user sent in this branch. */
    suspend fun prepareGalleryUpload(attachmentId: String?, messages: List<UIMessage>, conversationId: String): GalleryUpload =
        withContext(Dispatchers.IO) {
            val available = if (conversationId.isNotBlank()) sources(messages, conversationId) else emptyList()
            val source = if (attachmentId.isNullOrBlank()) available.lastOrNull() ?: error("No image in this conversation")
            else available.singleOrNull { it.id == attachmentId } ?: error("Attachment is not in this conversation branch. List images first.")
            val bytes = readAttachment(source)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 16777216) { "Unsupported image dimensions" }
            require(bounds.outMimeType in listOf("image/png", "image/jpeg", "image/webp")) { "Unsupported image format" }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 1600 && bytes.size < 700_000 && bounds.outMimeType != "image/webp") {
                return@withContext GalleryUpload(Base64.encodeToString(bytes, Base64.NO_WRAP), bounds.outMimeType,
                    bounds.outWidth, bounds.outHeight)
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Image decode failed")
            val scale = minOf(1f, 1600f / longest)
            val width = (bounds.outWidth * scale).toInt().coerceAtLeast(1)
            val height = (bounds.outHeight * scale).toInt().coerceAtLeast(1)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(decoded, width, height, true) else decoded
            try {
                val keepPng = bounds.outMimeType == "image/png" && scaled.hasAlpha()
                val out = ByteArrayOutputStream()
                check(scaled.compress(if (keepPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    if (keepPng) 100 else 85, out))
                GalleryUpload(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                    if (keepPng) "image/png" else "image/jpeg", width, height)
            } finally {
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
        }
}
