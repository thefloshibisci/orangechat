package me.rerere.rikkahub.ui.components.workspace

import android.net.Uri
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
import java.io.FileNotFoundException

/** All workspace downloads share collision handling, error reporting and a captured source. */
@Composable
internal fun rememberWorkspaceFileExporter(workspaceId: String?): (WorkspaceStorageArea, String) -> Unit {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val repository = koinInject<WorkspaceRepository>()
    val scope = rememberCoroutineScope()
    val destination = remember(context) { WorkspaceExportDestination(context) }
    var pending by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun save(source: ArrayList<String>, tree: Uri) {
        busy = true
        scope.launch {
            try {
                val savedName = withContext(Dispatchers.IO) {
                    val (id, areaName, path) = source
                    val area = WorkspaceStorageArea.valueOf(areaName)
                    val size = repository.fileSize(id, area, path)
                    try {
                        exportNewWorkspaceDocument(context.contentResolver, tree, path.substringAfterLast('/'), size) {
                            repository.exportFile(id, area, path, it)
                        }
                    } catch (e: SecurityException) {
                        destination.forget(tree)
                        throw IllegalStateException("文件夹权限已失效，请再次下载并重新选择文件夹", e)
                    } catch (e: FileNotFoundException) {
                        destination.forget(tree)
                        throw IllegalStateException("文件或保存位置已不可用，请再次下载并重新选择文件夹", e)
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

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val source = pending
        pending = null
        if (tree != null && source != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        destination.remember(tree)
                    }
                    save(source, tree)
                } catch (e: CancellationException) {
                    busy = false
                    throw e
                } catch (e: Exception) {
                    toaster.show("无法记住下载文件夹：${e.message ?: "请重新选择"}")
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
                        val source = arrayListOf(id, area.name, path)
                        val tree = withContext(Dispatchers.IO) { destination.current() }
                        if (tree == null) {
                            pending = source
                            launcher.launch(null)
                            busy = false
                        } else {
                            save(source, tree)
                        }
                    } catch (e: CancellationException) {
                        pending = null
                        busy = false
                        throw e
                    } catch (e: Exception) {
                        pending = null
                        toaster.show("无法保存此文件：${e.message ?: "未知错误"}")
                        busy = false
                    }
                }
            }
        }
    }
}
