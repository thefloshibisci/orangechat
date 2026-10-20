package me.rerere.rikkahub.ui.components.workspace

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.workspace.WorkspaceStorageArea

internal val LocalWorkspaceDownloadHandler = staticCompositionLocalOf<UriHandler?> { null }

@Composable
internal fun rememberWorkspaceDownloadHandler(workspaceId: String?): UriHandler {
    val fallback = LocalUriHandler.current
    val toaster = LocalToaster.current
    val export = rememberWorkspaceFileExporter(workspaceId)
    return object : UriHandler {
        override fun openUri(uri: String) {
            if (!WorkspaceDownloadPath.isWorkspaceLink(uri)) {
                runCatching { fallback.openUri(uri) }.onFailure { toaster.show("无法打开此链接") }
                return
            }
            val path = WorkspaceDownloadPath.parse(uri)
            if (path == null) toaster.show("无效的工作区文件路径")
            else export(WorkspaceStorageArea.FILES, path)
        }
    }
}

@Composable
internal fun WorkspaceInlineDownloads(text: String) {
    val handler = LocalWorkspaceDownloadHandler.current ?: return
    val paths = remember(text) { WorkspaceDownloadPath.inlinePaths(text) }
    paths.forEach { path ->
        TextButton(onClick = { handler.openUri("sandbox:" + Uri.encode(path, "/")) }) {
            Text("保存文件 · ${path.substringAfterLast('/')}")
        }
    }
}
