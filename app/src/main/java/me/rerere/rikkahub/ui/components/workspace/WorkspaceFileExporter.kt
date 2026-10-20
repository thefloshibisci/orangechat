package me.rerere.rikkahub.ui.components.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/** All workspace downloads share collision handling, error reporting and a captured source. */
@Composable
internal fun rememberWorkspaceFileExporter(workspaceId: String?): (WorkspaceStorageArea, String) -> Unit {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val repository = koinInject<WorkspaceRepository>()
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val source = pending
        pending = null
        if (tree != null && source != null) {
            busy = true
            scope.launch {
                try {
                    val savedName = withContext(Dispatchers.IO) {
                        val (id, areaName, path) = source
                        val area = WorkspaceStorageArea.valueOf(areaName)
                        val size = repository.fileSize(id, area, path)
                        exportNewWorkspaceDocument(context.contentResolver, tree, path.substringAfterLast('/'), size) {
                            repository.exportFile(id, area, path, it)
                        }
                    }
                    toaster.show("已保存 $savedName")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toaster.show("文件保存失败：${e.message ?: "未知错误"}")
                } finally {
                    busy = false
                }
            }
        }
    }
    return { area, path ->
        when {
            workspaceId == null -> toaster.show("此会话的助手未绑定工作区")
            busy || pending != null -> toaster.show("请先完成当前文件保存")
            else -> {
                val id = workspaceId
                busy = true
                scope.launch {
                    try {
                        repository.fileSize(id, area, path)
                        pending = arrayListOf(id, area.name, path)
                        launcher.launch(null)
                    } catch (e: CancellationException) {
                        pending = null
                        throw e
                    } catch (e: Exception) {
                        pending = null
                        toaster.show("无法保存此文件：${e.message ?: "未知错误"}")
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
}
