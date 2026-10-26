package me.rerere.rikkahub.ui.components.workspace

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

private interface WorkspaceDownloadUriHandler : UriHandler { val workspaceDir: String }

internal val LocalWorkspaceDownloadHandler = staticCompositionLocalOf<UriHandler?> { null }

@Composable
internal fun rememberWorkspaceDownloadHandler(workspaceId: String?): UriHandler {
    val fallback = LocalUriHandler.current
    val toaster = LocalToaster.current
    val export = rememberWorkspaceFileExporter(workspaceId)
    val repository = koinInject<WorkspaceRepository>()
    val root by produceState("/workspace", workspaceId, repository) {
        repository.listFlow().collectLatest { workspaces ->
            value = workspaces.firstOrNull { it.id == workspaceId }?.workspaceDir() ?: "/workspace"
        }
    }
    return object : WorkspaceDownloadUriHandler {
        override val workspaceDir: String = root
        override fun openUri(uri: String) {
            if (!WorkspaceDownloadPath.isWorkspaceLink(uri, workspaceDir)) {
                runCatching { fallback.openUri(uri) }.onFailure { toaster.show("无法打开此链接") }
                return
            }
            val path = WorkspaceDownloadPath.parse(uri, workspaceDir)
            if (path == null) toaster.show("无效的工作区文件路径")
            else export(WorkspaceStorageArea.FILES, path)
        }
    }
}

@Composable
internal fun WorkspaceInlineDownloads(text: String) {
    val handler = LocalWorkspaceDownloadHandler.current ?: return
    val root = (handler as? WorkspaceDownloadUriHandler)?.workspaceDir ?: "/workspace"
    val paths = remember(text, root) { WorkspaceDownloadPath.inlinePaths(text, root) }
    paths.forEach { path ->
        TextButton(onClick = { handler.openUri("sandbox:" + Uri.encode(path, "/")) }) {
            Text("保存文件 · ${path.substringAfterLast('/')}")
        }
    }
}
