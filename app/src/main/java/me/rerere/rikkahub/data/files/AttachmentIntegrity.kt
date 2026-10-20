package me.rerere.rikkahub.data.files

import android.graphics.BitmapFactory
import androidx.core.net.toFile
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import java.io.File

internal fun localAttachmentFile(url: String): File? = when {
    url.startsWith("file:") -> runCatching { url.toUri().toFile() }.getOrNull()
    url.startsWith("/") -> File(url)
    else -> null
}

/** Call on IO. Cache is scoped to one scan/request so deletion is noticed on the next request. */
internal class AttachmentValidator(private val forSending: Boolean = false) {
    private val results = mutableMapOf<Pair<String, Boolean>, Boolean>()

    fun isValid(part: UIMessagePart): Boolean {
        val url = part.attachmentUrl() ?: return true
        val file = localAttachmentFile(url) ?: return !url.startsWith("file:")
        return results.getOrPut(url to (part is UIMessagePart.Image)) {
            file.isFile && file.canRead() && file.length() > 0 &&
                (part !is UIMessagePart.Image ||
                    (!forSending && file.extension.equals("svg", ignoreCase = true)) || isDecodableImage(file))
        }
    }
}

internal fun isDecodableImage(file: File): Boolean = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
    val options = BitmapFactory.Options().apply {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
        inSampleSize = sample
    }
    BitmapFactory.decodeFile(file.path, options)?.let { it.recycle(); true } ?: false
}.getOrDefault(false)
