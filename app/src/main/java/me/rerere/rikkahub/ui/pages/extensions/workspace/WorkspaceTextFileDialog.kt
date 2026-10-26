package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.workspace.WorkspaceTextPreview

@Composable
internal fun WorkspaceTextFileDialog(
    name: String,
    preview: WorkspaceTextPreview?,
    loading: Boolean,
    error: String?,
    editable: Boolean,
    onRetry: () -> Unit,
    onSave: suspend (original: String, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var original by remember(preview?.text) { mutableStateOf(preview?.text.orEmpty()) }
    var draft by remember(preview?.text) { mutableStateOf(preview?.text.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val dirty = draft != original
    val canEdit = editable && preview != null && !preview.isBinary && !preview.truncated && error == null && !loading
    fun close() {
        if (saving) return
        if (dirty) discard = true else onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("重新读取") }
                    }
                    preview?.isBinary == true -> Text("二进制文件不能作为文本编辑。")
                    editing && canEdit -> OutlinedTextField(
                        value = draft, onValueChange = { draft = it; saveError = null }, enabled = !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("workspace_file_text"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        minLines = 6,
                    )
                    else -> {
                        SelectionContainer {
                            Text(original, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                                    .verticalScroll(rememberScrollState()))
                        }
                        if (preview?.truncated == true) {
                            Text("文件较大，仅显示部分内容，无法在此保存修改。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (canEdit) TextButton(
                enabled = !saving && (!editing || dirty),
                onClick = {
                    if (!editing) editing = true else {
                        val submitted = draft
                        saving = true
                        scope.launch {
                            try {
                                onSave(original, submitted)
                                original = submitted
                                draft = submitted
                                editing = false
                                saveError = null
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { saveError = failure.message ?: "保存失败，修改仍保留在编辑框中。" }
                            finally { saving = false }
                        }
                    }
                },
            ) { Text(if (saving) "保存中…" else if (editing) "保存" else "编辑") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = ::close) { Text("关闭") } },
    )
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text("放弃未保存的修改？") },
        text = { Text("关闭后将丢弃当前编辑内容。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
    )
}
