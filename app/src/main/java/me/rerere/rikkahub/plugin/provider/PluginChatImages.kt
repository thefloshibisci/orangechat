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
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginToolParameter
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Only resolves attachments from the invoking tool surface's branch snapshot. */
class PluginChatImages(private val context: Context) {
    data class Source(
        val id: String, val messageId: String, val uri: String, val name: String,
        val origin: String = "user", val toolName: String? = null,
    )
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

    private fun attachmentRoots() = listOf("images", "upload").map { File(context.filesDir, it).canonicalFile }

    /** True only for file: URIs that resolve inside the app's own chat image/upload directories. */
    private fun isAppAttachmentFile(uri: String): Boolean = try {
        val parsed = uri.toUri()
        if (parsed.scheme != "file" || parsed.path == null) false else {
            val file = File(requireNotNull(parsed.path)).canonicalFile
            attachmentRoots().any { file.path.startsWith(it.path + File.separator) }
        }
    } catch (_: Exception) {
        // One malformed tool URI must not prevent listing the other chat images.
        false
    }

    /**
     * Gallery includes real local image parts returned by assistant tools, not paths in text.
     * Assistant/tool output is untrusted, so it may only reference files the app itself stored;
     * content: URIs (e.g. other apps' media) are accepted only when the user attached them.
     */
    internal fun gallerySources(messages: List<UIMessage>, conversationId: String): List<Source> =
        messages.flatMap { message ->
            if (message.role == MessageRole.USER) {
                sources(listOf(message), conversationId)
            } else if (message.role == MessageRole.ASSISTANT) {
                buildList {
                    fun collect(part: UIMessagePart, partPath: String, origin: String, toolName: String? = null) {
                        val uri = when (part) {
                            is UIMessagePart.Image -> part.url
                            is UIMessagePart.Document -> if (part.mime.startsWith("image/") ||
                                part.fileName.substringAfterLast('.', "").lowercase() in listOf("png", "jpg", "jpeg", "webp")) part.url else null
                            else -> null
                        }
                        if (uri != null && isAppAttachmentFile(uri)) {
                            val identity = listOf(conversationId, message.id.toString(), partPath, uri).joinToString("\n")
                            val id = "attachment_" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                                .joinToString("") { "%02x".format(it) }.take(40)
                            add(Source(id, message.id.toString(), uri,
                                (part as? UIMessagePart.Document)?.fileName ?: "${origin}_image_${size + 1}", origin, toolName))
                        }
                    }
                    message.parts.forEachIndexed { index, part ->
                        if (part is UIMessagePart.Tool) {
                            part.output.forEachIndexed { outputIndex, output ->
                                collect(output, "tool:$index:${part.toolCallId}:$outputIndex", "tool", part.toolName)
                            }
                        } else collect(part, "assistant:$index", "assistant")
                    }
                }
            } else emptyList()
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
                require(attachmentRoots().any { file.path.startsWith(it.path + File.separator) }) { "Unsupported attachment location" }
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
    data class GalleryUpload(
        val base64: String, val mime: String, val width: Int, val height: Int,
        val source: Source, val originalSha256: String, val sha256: String,
    )

    internal suspend fun resolveToolParameters(
        parameters: List<PluginToolParameter>,
        params: JsonElement,
        messages: List<UIMessage>,
        conversationId: String,
        permissions: List<String>,
        onImageResolved: ((JsonObject) -> Unit)? = null,
    ): JsonObject {
        val supplied = params as? JsonObject ?: error("Tool parameters must be an object")
        val hostParameters = parameters.filter { it.source != null }
        // Validate every declaration before opening any attachment or invoking plugin code.
        hostParameters.forEach { parameter ->
            require(parameter.name != "attachment_id") { "attachment_id is reserved for image selection" }
            val expectedType = when (parameter.source) {
                "chat_image_base64", "chat_image_mime" -> "string"
                "chat_images_list" -> "array"
                else -> error("Unsupported host parameter source: ${parameter.source}")
            }
            require(parameter.type == expectedType) { "Host parameter ${parameter.name} must have type $expectedType" }
        }
        require("conversation_sync" in permissions) { "Chat image parameters require conversation_sync permission" }
        require(conversationId.isNotBlank()) { "Chat image parameters require a conversation" }
        val attachment = supplied["attachment_id"]
        require(attachment == null || attachment == JsonNull || attachment is JsonPrimitive && attachment.isString) {
            "attachment_id must be a string"
        }
        val attachmentId = (attachment as? JsonPrimitive)?.contentOrNull
        var image: GalleryUpload? = null
        if (hostParameters.any { it.source in IMAGE_VALUE_SOURCES } && attachmentId.isNullOrBlank()) {
            require(gallerySources(messages, conversationId).size <= 1) {
                "Multiple chat images: attachment_id is required. Use chat_images to list/preview, then pass the exact attachment_id."
            }
        }
        val values = hostParameters.associate { parameter ->
            parameter.name to when (parameter.source) {
                "chat_images_list" -> galleryList(messages, conversationId).jsonObject.getValue("images")
                else -> {
                    val resolved = image ?: prepareGalleryUpload(attachmentId, messages, conversationId).also { image = it }
                    JsonPrimitive(if (parameter.source == "chat_image_base64") resolved.base64 else resolved.mime)
                }
            }
        }
        image?.let { resolved ->
            onImageResolved?.invoke(buildJsonObject {
                sourceFields(resolved.source)
                put("width", resolved.width); put("height", resolved.height); put("mime", resolved.mime)
                put("original_sha256", resolved.originalSha256); put("sha256", resolved.sha256)
            })
        }
        // Model-supplied values cannot override host-owned fields.
        return JsonObject(supplied + values)
    }

    internal fun galleryList(messages: List<UIMessage>, conversationId: String): JsonElement = buildJsonObject {
        put("success", true)
        putJsonArray("images") {
            (if (conversationId.isNotBlank()) gallerySources(messages, conversationId) else emptyList()).forEach { source ->
                add(buildJsonObject {
                    sourceFields(source)
                })
            }
        }
    }

    /** A missing selector is unambiguous only when this branch contains a single image. */
    suspend fun prepareGalleryUpload(attachmentId: String?, messages: List<UIMessage>, conversationId: String): GalleryUpload =
        withContext(Dispatchers.IO) {
            val available = if (conversationId.isNotBlank()) gallerySources(messages, conversationId) else emptyList()
            val source = if (attachmentId.isNullOrBlank()) {
                require(available.size <= 1) { "Multiple chat images: attachment_id is required. Use chat_images to list/preview first." }
                available.singleOrNull() ?: error("No image in this conversation")
            }
            else available.singleOrNull { it.id == attachmentId } ?: error("Attachment is not in this conversation branch. List images first.")
            val bytes = readAttachment(source)
            val originalSha256 = sha256(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 16777216) { "Unsupported image dimensions" }
            require(bounds.outMimeType in listOf("image/png", "image/jpeg", "image/webp")) { "Unsupported image format" }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 1600 && bytes.size < 700_000 && bounds.outMimeType != "image/webp") {
                return@withContext GalleryUpload(Base64.encodeToString(bytes, Base64.NO_WRAP), bounds.outMimeType,
                    bounds.outWidth, bounds.outHeight, source, originalSha256, originalSha256)
            }
            val scale = minOf(1f, 1600f / longest)
            val width = (bounds.outWidth * scale).toInt().coerceAtLeast(1)
            val height = (bounds.outHeight * scale).toInt().coerceAtLeast(1)
            val options = BitmapFactory.Options().apply {
                while (longest / (inSampleSize.coerceAtLeast(1) * 2) >= 1600) {
                    inSampleSize = inSampleSize.coerceAtLeast(1) * 2
                }
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Image decode failed")
            var scaled = decoded
            try {
                if (decoded.width != width || decoded.height != height) {
                    scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
                }
                val keepPng = bounds.outMimeType == "image/png" && scaled.hasAlpha()
                val out = ByteArrayOutputStream()
                check(scaled.compress(if (keepPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    if (keepPng) 100 else 85, out))
                val encodedBytes = out.toByteArray()
                GalleryUpload(Base64.encodeToString(encodedBytes, Base64.NO_WRAP),
                    if (keepPng) "image/png" else "image/jpeg", width, height, source, originalSha256, sha256(encodedBytes))
            } finally {
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObjectBuilder.sourceFields(source: Source) {
        put("attachment_id", source.id); put("message_id", source.messageId); put("name", source.name)
        put("origin", source.origin)
        source.toolName?.let { put("tool_name", it) }
    }

    internal fun browserTool(messages: List<UIMessage>, conversationId: String): Tool {
        val snapshot = messages.map { it.copy(parts = it.parts.toList()) }
        return Tool(
            name = "chat_images",
            description = "列出当前聊天分支最近30张用户/助手/工具图片的编号、来源和原始尺寸。action=list仅返回清单；action=preview需attachment_id，返回一张最长边640px的完整预览附件及原图SHA256。预览不裁切、不上传图库。看清后将同一编号传给图片插件。只读取调用时的分支快照，本轮新生成的图需下一次消息再读取。",
            parameters = { InputSchema.Obj(buildJsonObject {
                putJsonObject("action") {
                    put("type", "string"); putJsonArray("enum") { add("list"); add("preview") }
                }
                putJsonObject("attachment_id") { put("type", "string") }
            }, required = listOf("action")) },
            execute = { browse(it, snapshot, conversationId) },
        )
    }

    internal suspend fun browse(params: JsonElement, messages: List<UIMessage>, conversationId: String): List<UIMessagePart> =
        withContext(Dispatchers.IO) {
            try {
                require(conversationId.isNotBlank()) { "Chat images require a conversation" }
                val p = params as? JsonObject ?: error("Tool parameters must be an object")
                val available = gallerySources(messages, conversationId)
                when ((p["action"] as? JsonPrimitive)?.contentOrNull) {
                    "list" -> listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", true)
                        putJsonArray("images") {
                            available.forEach { source -> add(buildJsonObject {
                                sourceFields(source)
                                try {
                                    // The bounded attachment reader also validates locations; never follow text URLs.
                                    val bytes = readAttachment(source)
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image decode failed" }
                                    put("width", bounds.outWidth); put("height", bounds.outHeight)
                                    put("mime", bounds.outMimeType); put("available", true)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    put("available", false); put("error", e.message ?: "Cannot read image")
                                }
                            }) }
                        }
                    }.toString()))
                    "preview" -> {
                        val id = p["attachment_id"] as? JsonPrimitive
                        require(id != null && id.isString && id.content.isNotBlank()) { "preview requires attachment_id" }
                        val source = available.singleOrNull { it.id == id.content }
                            ?: error("Attachment is not in this conversation branch. List images first.")
                        val bytes = readAttachment(source)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                            bounds.outWidth.toLong() * bounds.outHeight <= 16777216) { "Unsupported image dimensions" }
                        require(bounds.outMimeType in listOf("image/png", "image/jpeg", "image/webp")) { "Unsupported image format" }
                        val longest = maxOf(bounds.outWidth, bounds.outHeight)
                        val scale = minOf(1f, 640f / longest)
                        val width = (bounds.outWidth * scale).toInt().coerceAtLeast(1)
                        val height = (bounds.outHeight * scale).toInt().coerceAtLeast(1)
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            while (longest / (inSampleSize * 2) >= 640) inSampleSize *= 2
                        }
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Image decode failed")
                        var scaled = decoded
                        val directory = File(context.cacheDir, "chat-image-previews").apply { mkdirs() }
                        // Outside images/upload: preview outputs must not become fresh gallery candidates.
                        val file = File(directory, "${source.id}_${sha256(bytes)}.png")
                        try {
                            if (decoded.width != width || decoded.height != height) {
                                scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
                            }
                            if (!file.exists()) {
                                try {
                                    val temporary = File.createTempFile("preview_", ".tmp", directory)
                                    try {
                                        temporary.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                        check(temporary.renameTo(file) || file.exists()) { "Could not save preview" }
                                    } finally {
                                        temporary.delete()
                                    }
                                } catch (e: Exception) {
                                    file.delete(); throw e
                                }
                            }
                        } finally {
                            if (scaled !== decoded) scaled.recycle()
                            decoded.recycle()
                        }
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", true); sourceFields(source)
                            put("width", bounds.outWidth); put("height", bounds.outHeight); put("mime", bounds.outMimeType)
                            put("original_sha256", sha256(bytes))
                            put("preview_width", width); put("preview_height", height)
                        }.toString()), UIMessagePart.Image(file.toURI().toString()))
                    }
                    else -> error("action must be list or preview")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", false); put("error", e.message ?: "Cannot read chat images")
                }.toString()))
            }
        }
}
