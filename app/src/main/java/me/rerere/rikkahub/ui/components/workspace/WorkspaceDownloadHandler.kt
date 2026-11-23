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
    val targetedExport = rememberWorkspaceTargetedFileExporter()
    val repository = koinInject<WorkspaceRepository>()
    val roots by produceState(emptyMap<String, String>(), repository) {
        repository.listFlow().collectLatest { workspaces -> value = workspaces.associate { it.id to it.workspaceDir() } }
    }
    val root = roots[workspaceId] ?: "/workspace"
    return object : WorkspaceDownloadUriHandler {
        override val workspaceDir: String = root
        override fun openUri(uri: String) {
            if (uri.startsWith("workspace:")) {
                val target = WorkspaceDownloadPath.parseTargeted(uri, roots)
                if (target == null) toaster.show("工作区不存在或文件路径无效")
                else targetedExport(target.first, WorkspaceStorageArea.FILES, target.second)
                return
            }
            if (!WorkspaceDownloadPath.isWorkspaceLink(uri, workspaceDir)) {
                runCatching { fallback.openUri(uri) }.onFailure { toaster.show("无法打开此链接") }
                return
            }
            if (workspaceId == null) {
                toaster.show("请使用工具结果下方的文件按钮，按实际工作区下载")
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
