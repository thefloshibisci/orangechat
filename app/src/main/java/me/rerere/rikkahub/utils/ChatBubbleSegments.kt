package me.rerere.rikkahub.utils

import me.rerere.rikkahub.data.datastore.ChatDisplayMode

internal data class BubbleQuote(val author: String?, val text: String, val reply: String)

/** Read a leading Markdown quote without rewriting the stored message or model context. */
internal fun String.bubbleQuote(): BubbleQuote? {
    val lines = trimStart().lines()
    if (lines.isEmpty() || !lines.first().startsWith(">")) return null
    val count = lines.takeWhile { it.startsWith(">") }.size
    val quoted = lines.take(count).map { it.removePrefix(">").removePrefix(" ") }
    val first = quoted.firstOrNull().orEmpty()
    val named = first.length > 4 && first.startsWith("**") && first.endsWith("**")
    val text = (if (named) quoted.drop(1) else quoted).joinToString("\n").trim()
    if (text.isBlank()) return null
    return BubbleQuote(if (named) first.removeSurrounding("**") else null, text,
        lines.drop(count).joinToString("\n").trimStart())
}

internal fun quotedBubbleSelection(author: String, text: String): String {
    val name = author.replace("\n", " ").replace("\r", " ").replace("**", "").trim()
    // Requoting a reply quotes that reply, rather than recursively nesting its old citation.
    val body = text.bubbleQuote()?.let { it.reply.ifBlank { it.text } } ?: text
    return "**$name**\n$body"
}

/** Keep citation and reply together; all other messages retain existing segmentation. */
internal fun String.chatBubbleSegments(mode: ChatDisplayMode, splitByLine: Boolean): List<String> =
    if (mode == ChatDisplayMode.EVERYDAY && bubbleQuote() != null) listOf(this)
    else if (mode == ChatDisplayMode.EVERYDAY || splitByLine) splitIntoBubbleSegments() else listOf(this)
