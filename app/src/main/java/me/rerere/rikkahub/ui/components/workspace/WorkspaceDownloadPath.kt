package me.rerere.rikkahub.ui.components.workspace

import java.net.URLDecoder

/** Links address files in the conversation's bound workspace, never host filesystem paths. */
internal object WorkspaceDownloadPath {
    fun isWorkspaceLink(url: String) = url.startsWith("sandbox:") || url.startsWith("/workspace/")

    fun parse(url: String): String? {
        if (!isWorkspaceLink(url)) return null
        val path = runCatching {
            URLDecoder.decode(url.removePrefix("sandbox:").replace("+", "%2B"), "UTF-8")
        }.getOrNull() ?: return null
        if (!path.startsWith("/workspace/")) return null
        val relative = path.removePrefix("/workspace/")
        if (relative.isBlank() || relative.any { it == '\\' || it.code < 32 }) return null
        if (relative.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return relative
    }

    // Older replies often put paths in inline code. Leave code examples intact and offer a separate save action.
    fun inlinePaths(text: String): List<String> = Regex("(?<!`)`(/workspace/[^`\\r\\n]+)`(?!`)")
        .findAll(text).map { it.groupValues[1] }.distinct().take(12).toList()
}
