package me.rerere.rikkahub.data.files

import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.security.MessageDigest

/** duplicate file name -> the byte-identical file every reference should point to instead. */
internal data class DuplicateAttachmentPlan(val canonicalByDuplicate: Map<String, File>, val directory: File) {
    val isEmpty get() = canonicalByDuplicate.isEmpty()
}

/**
 * Groups upload files by size, then SHA-256, and picks one survivor per identical group.
 * Survivor preference: an original upload over a "chat-" restore copy, then the oldest file,
 * then the name, so the result is deterministic across rescans.
 */
internal fun planDuplicateAttachments(directory: File): DuplicateAttachmentPlan {
    val files = directory.listFiles().orEmpty()
        .filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 }
    val result = linkedMapOf<String, File>()
    files.groupBy { it.length() }.values.filter { it.size > 1 }.forEach { sameSize ->
        sameSize.groupBy { sha256(it) }.values.filter { it.size > 1 }.forEach { group ->
            val canonical = group.sortedWith(
                compareBy<File>({ it.name.startsWith("chat-") }, { it.lastModified() }, { it.name })
            ).first()
            group.filter { it != canonical }.forEach { result[it.name] = canonical }
        }
    }
    return DuplicateAttachmentPlan(result, directory.canonicalFile)
}

/**
 * Rewrites every attachment part (including voice messages and nested tool output) whose local
 * file is a planned duplicate so it points at the survivor. Returns the same list instance when
 * nothing changed, so callers can skip the write.
 */
internal fun remapDuplicateAttachments(
    messages: List<UIMessage>,
    plan: DuplicateAttachmentPlan,
): List<UIMessage> {
    fun target(url: String): String? {
        val source = localAttachmentFile(url)?.canonicalFile ?: return null
        if (source.parentFile != plan.directory) return null
        return plan.canonicalByDuplicate[source.name]?.toUri()?.toString()
    }
    fun remap(parts: List<UIMessagePart>): List<UIMessagePart> {
        var changed = false
        val result = parts.map { part ->
            val next = when (part) {
                is UIMessagePart.Image -> target(part.url)?.let { part.copy(url = it) }
                is UIMessagePart.Document -> target(part.url)?.let { part.copy(url = it) }
                is UIMessagePart.Audio -> target(part.url)?.let { part.copy(url = it) }
                is UIMessagePart.Video -> target(part.url)?.let { part.copy(url = it) }
                is UIMessagePart.VoiceMessage -> target(part.url)?.let { part.copy(url = it) }
                is UIMessagePart.Tool -> remap(part.output).takeIf { it !== part.output }
                    ?.let { part.copy(output = it) }
                else -> null
            }
            if (next != null) { changed = true; next } else part
        }
        return if (changed) result else parts
    }
    var changed = false
    val result = messages.map { message ->
        val parts = remap(message.parts)
        if (parts === message.parts) message else { changed = true; message.copy(parts = parts) }
    }
    return if (changed) result else messages
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
