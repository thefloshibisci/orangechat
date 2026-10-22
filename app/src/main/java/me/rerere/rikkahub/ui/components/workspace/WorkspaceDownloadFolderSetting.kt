package me.rerere.rikkahub.ui.components.workspace

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.context.LocalToaster

@Composable
internal fun WorkspaceDownloadFolderSetting() {
    val context = LocalContext.current
    val destination = remember(context) { WorkspaceExportDestination(context) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val directory by remember(destination) { destination.observe() }.collectAsStateWithLifecycle(initialValue = null)
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { destination.remember(uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toaster.show("无法记住下载文件夹：${e.message ?: "请重新选择"}")
                } finally {
                    busy = false
                }
            }
        } else {
            busy = false
        }
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("下载文件夹", style = MaterialTheme.typography.titleSmall)
            Text(
                directory?.let { it.lastPathSegment.orEmpty().substringAfter(':') }
                    ?.takeIf { it.isNotBlank() } ?: "首次下载时选择",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "所有工作区共用此位置。下载会直接保存，同名文件自动编号。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(enabled = !busy, onClick = {
                runCatching { picker.launch(directory); busy = true }
                    .onFailure { toaster.show("无法打开文件夹选择器") }
            }) { Text(if (directory == null) "选择文件夹" else "更换文件夹") }
        }
    }
}
