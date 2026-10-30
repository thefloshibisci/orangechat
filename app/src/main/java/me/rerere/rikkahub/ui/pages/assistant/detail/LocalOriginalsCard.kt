package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.serein.RawArchiveRevision
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

/** Phase one intentionally offers archiving only. No model setup or promise of automatic recall. */
@Composable
internal fun LocalOriginalsCard(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    repository: ConversationRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CardGroup {
            item(
                headlineContent = { Text("保存聊天原话") },
                supportingContent = { MemoryNotes(listOf(
                    "把和这位助手的聊天原文，连同每次修改，都存一份在手机里。",
                    "只是存档，方便你翻看；不会整理成记忆，也不会拿来回复。关掉后不再记新的，已经存的还在。",
                )) },
                trailingContent = {
                    Switch(checked = assistant.saveLocalRawMessages, onCheckedChange = {
                        onUpdateAssistant(assistant.copy(saveLocalRawMessages = it))
                    })
                },
            )
            item(
                enabled = assistant.saveLocalRawMessages && !importing,
                onClick = {
                    scope.launch {
                        importing = true
                        try {
                            val count = repository.backfillRawOriginals(assistant.id) { current, total ->
                                withContext(Dispatchers.Main.immediate) { status = "正在导入 $current / $total 个聊天…" }
                            }
                            status = "已导入 $count 个聊天，可重复导入以更新原话。"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            status = "导入未完成。请确认开关已开启后重试，已存档的原话会保留。"
                        } finally {
                            importing = false
                        }
                    }
                },
                headlineContent = { Text(if (importing) "正在导入…" else "导入已有聊天") },
                supportingContent = { Text("把打开开关之前的聊天也存进来。") },
            )
            item(onClick = { browsing = true }, headlineContent = { Text("查看原话") })
        }
        status?.let { Text(it, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall) }
    }
    if (browsing) OriginalBrowser(assistant, repository) { browsing = false }
}

@Composable
private fun OriginalBrowser(assistant: Assistant, repository: ConversationRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<RawArchiveRevision>>(emptyList()) }
    var revisions by remember { mutableStateOf<List<RawArchiveRevision>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(assistant.id, query) {
        loading = true
        error = null
        try {
            if (query.isNotBlank()) delay(250)
            rows = repository.searchRawOriginals(assistant.id, query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            rows = emptyList()
            error = "原话暂时无法读取，请关闭后重试。"
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${assistant.name.ifBlank { "此助手" }}的原话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索原话") }, singleLine = true)
                Text("显示最近 50 条当前选中的原话，点开可看完整内容和修订。", style = MaterialTheme.typography.bodySmall)
                when {
                    loading -> Text("正在读取…")
                    error != null -> Text(error!!)
                    rows.isEmpty() -> Text(if (query.isBlank()) "还没有原话，请开启存档后聊天，或导入已有聊天。" else "没有找到这段原话。")
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(rows, key = { it.id }) { row ->
                            Column(Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    try {
                                        revisions = repository.rawOriginalHistory(assistant.id, row.conversationId, row.messageId)
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { error = "修订暂时无法读取，请重试。" }
                                }
                            }) {
                                Text("${if (row.role == "user") "你" else assistant.name.ifBlank { "助手" }} · ${row.createdAt.replace('T', ' ')}",
                                    style = MaterialTheme.typography.labelSmall)
                                Text(row.text, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
    revisions?.let { history ->
        AlertDialog(
            onDismissRequest = { revisions = null },
            title = { Text("原话与修订") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (history.isEmpty()) item { Text("这条消息已移除，原话不再作为当前来源。") }
                    items(history, key = { it.id }) { row ->
                        Column {
                            Text("修订 ${row.revision} · ${when (row.state) { "complete" -> "已完成"; "interrupted" -> "已中断"; else -> "未完成" }}",
                                style = MaterialTheme.typography.labelMedium)
                            Text(row.createdAt.replace('T', ' '), style = MaterialTheme.typography.labelSmall)
                            SelectionContainer { Text(row.text) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { revisions = null }) { Text("返回") } },
        )
    }
}
