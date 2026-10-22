package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.sync.ChatHistoryBackup
import me.rerere.rikkahub.data.sync.ChatHistoryRestoreMode
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import kotlin.system.exitProcess

@Composable
internal fun ChatHistoryBackupCard() {
    val backup = koinInject<ChatHistoryBackup>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var busy by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(ChatHistoryRestoreMode.INCREMENTAL) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(backup.hasPendingRestore()) }
    var result by remember { mutableStateOf(backup.lastRestoreResult()) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val exported = withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri)) { "无法打开导出文件" }
                            .use { backup.export(it) }
                    }
                    result = if (exported.unavailableAttachments == 0) "聊天记录和附件导出成功" else
                        "聊天记录导出成功；${exported.unavailableAttachments} 个附件不可用，已保留缺失说明和已有语音转写。本机记录未修改。"
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) { toaster.show("聊天导出失败：${error.message}")
                } finally { busy = false }
            }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            val selectedMode = mode
            scope.launch {
                try {
                    val count = withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openInputStream(uri)) { "无法打开备份" }
                            .use { backup.stage(it, selectedMode) }
                    }
                    pending = true
                    result = "已校验 $count 个会话，退出并重新打开后将自动恢复并显示进度。现在的聊天记录尚未修改。"
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) { toaster.show("聊天导入失败：${error.message}")
                } finally { busy = false }
            }
        }
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅聊天记录", style = MaterialTheme.typography.titleMedium)
            Text("包含所有助手的会话、消息分支、压缩记录、消息收藏和本地附件。保留本机模型、助手设置与插件；备份中的助手不存在时，会话归到当前助手。",
                style = MaterialTheme.typography.bodyMedium)
            Text("缺失、空或无权读取的附件会在备份中转为缺失说明，保留已有语音转写；本机记录不变。",
                style = MaterialTheme.typography.bodyMedium)
            Text("增量：只新增本机没有的会话，同 ID 会话原样保留本机记录。\n覆盖：新增本机没有的会话，并完整替换同 ID 会话。\n两种方式都会保留备份之外的本机会话。",
                style = MaterialTheme.typography.bodyMedium)
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (pending) {
                Button(enabled = !busy, onClick = { exitProcess(0) }) { Text("退出后重新打开以恢复") }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            backup.cancelPending()
                            pending = false
                            result = "已取消待恢复任务，聊天记录未修改"
                        } catch (error: CancellationException) { throw error
                        } catch (error: Exception) { toaster.show("取消失败：${error.message}")
                        } finally { busy = false }
                    }
                }) { Text("取消待恢复任务") }
            } else {
                ChatHistoryActions(
                    enabled = !busy,
                    onExport = { export.launch("orangechat_chats_${System.currentTimeMillis()}.zip") },
                    onIncremental = {
                        mode = ChatHistoryRestoreMode.INCREMENTAL
                        restore.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    onOverwrite = { confirmOverwrite = true },
                )
            }
        }
    }
    if (confirmOverwrite) AlertDialog(
        onDismissRequest = { confirmOverwrite = false },
        title = { Text("覆盖备份中的会话？") },
        text = { Text("将新增本机没有的会话，并完整替换同 ID 会话，包括消息、分支和压缩记录。备份之外的本机会话会保留。建议先导出当前聊天记录。") },
        confirmButton = { TextButton(onClick = {
            confirmOverwrite = false
            mode = ChatHistoryRestoreMode.OVERWRITE_MATCHING
            restore.launch(arrayOf("application/zip", "application/octet-stream"))
        }) { Text("选择备份") } },
        dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("取消") } },
    )
}

/** Action group, not a selection control: no selected/checkmark state for restore modes. */
@Composable
internal fun ChatHistoryActions(enabled: Boolean, onExport: () -> Unit, onIncremental: () -> Unit, onOverwrite: () -> Unit) {
    // Match the visible container height of the backup-item SegmentedButtons.
    val actionHeight = 40.dp
    val shape = RoundedCornerShape(28.dp)
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSecondaryContainer.copy(alpha = if (enabled) 1f else 0.38f)
    val border = BorderStroke(1.dp, colors.outline.copy(alpha = if (enabled) 1f else 0.38f))
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(onClick = onExport, enabled = enabled, shape = shape, border = border,
            color = colors.secondaryContainer, contentColor = foreground,
            modifier = Modifier.fillMaxWidth().height(actionHeight)) {
            Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text("仅导出聊天记录", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        }
        Surface(shape = shape, border = border, color = colors.secondaryContainer, contentColor = foreground,
            modifier = Modifier.fillMaxWidth().height(actionHeight)) {
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxHeight()
                    .clickable(enabled = enabled, role = Role.Button, onClick = onIncremental)
                    .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text("增量恢复", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
                VerticalDivider(Modifier.fillMaxHeight(), color = colors.outline)
                Box(Modifier.weight(1f).fillMaxHeight()
                    .clickable(enabled = enabled, role = Role.Button, onClick = onOverwrite)
                    .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text("覆盖恢复", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
