package me.rerere.rikkahub.ui.components.workspace

import java.net.URLDecoder

/** Links address files in the conversation's bound workspace, never host filesystem paths. */
internal object WorkspaceDownloadPath {
    /** Explicit target links remain safe when the chat default or binding order changes. */
    fun parseTargeted(url: String, roots: Map<String, String>): Pair<String, String>? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "workspace" || uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null || uri.port != -1) return null
        val id = uri.host ?: return null
        val root = roots[id] ?: return null
        val encodedPath = uri.rawPath ?: return null
        return parse("sandbox:$encodedPath", root)?.let { id to it }
    }

    fun isWorkspaceLink(url: String, workspaceDir: String = "/workspace") =
        url.startsWith("sandbox:") || url.startsWith(workspaceDir.trimEnd('/') + "/")

    fun parse(url: String, workspaceDir: String = "/workspace"): String? {
        if (!isWorkspaceLink(url, workspaceDir)) return null
        val path = runCatching {
            URLDecoder.decode(url.removePrefix("sandbox:").replace("+", "%2B"), "UTF-8")
        }.getOrNull() ?: return null
        val prefix = workspaceDir.trimEnd('/') + "/"
        if (!path.startsWith(prefix)) return null
        val relative = path.removePrefix(prefix)
        if (relative.isBlank() || relative.any { it == '\\' || it.code < 32 }) return null
        if (relative.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return relative
    }

    // Older replies often put paths in inline code. Leave code examples intact and offer a separate save action.
    fun inlinePaths(text: String, workspaceDir: String = "/workspace"): List<String> =
        Regex("(?<!`)`(" + Regex.escape(workspaceDir.trimEnd('/') + "/") + "[^`\\r\\n]+)`(?!`)")
        .findAll(text).map { it.groupValues[1] }.distinct().take(12).toList()
}
