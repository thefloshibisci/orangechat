package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.serein.LocalScene
import me.rerere.rikkahub.data.serein.LocalSceneRepository
import me.rerere.rikkahub.data.serein.LocalSceneRevision
import me.rerere.rikkahub.data.serein.SceneConflict
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun LocalScenesCard(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    repository: LocalSceneRepository = koinInject(),
) {
    var browsing by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本地记忆", style = MaterialTheme.typography.titleMedium)
                    Text("让助手记下重要的事，按需查找。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(assistant.enableLocalSceneTools, { onUpdateAssistant(assistant.copy(enableLocalSceneTools = it)) })
            }
            Text("开启后，助手可用工具记下、查找和修改自己的记忆。尚不自动召回；关闭后已有记忆保留。",
                style = MaterialTheme.typography.bodySmall)
            Text("记忆保存在本机；工具查到的内容会发给当前聊天模型。它是整理后的记忆，不是原话档案。",
                style = MaterialTheme.typography.bodySmall)
            Text("启用 Serein 自动召回时，此处的聊天工具暂停，手动管理仍可用。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { browsing = true }) { Text("管理本地记忆") }
        }
    }
    if (browsing) SceneBrowser(assistant, repository) { browsing = false }
}

@Composable
private fun SceneBrowser(assistant: Assistant, repository: LocalSceneRepository, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val owner = assistant.id.toString()
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<LocalScene>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LocalScene?>(null) }
    var history by remember { mutableStateOf<List<LocalSceneRevision>?>(null) }
    var deleting by remember { mutableStateOf<LocalScene?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun fail(e: Exception) { error = if (e is SceneConflict) e.message else "操作未完成，请关闭后重试。" }
    LaunchedEffect(owner, query, refresh) {
        loading = true
        error = null
        try {
            if (query.isNotBlank()) delay(200)
            rows = repository.search(owner, query)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { rows = emptyList(); fail(e) }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("${assistant.name.ifBlank { "此助手" }}的本地记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { if (it.length <= 200) query = it }, label = { Text("搜索记忆") }, singleLine = true)
                Text("按原词或短语搜索，最多显示最近 50 条。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    loading -> Text("正在读取…")
                    rows.isEmpty() -> Text(if (query.isBlank()) "还没有记忆，可以先记下一件重要的事。" else "没有找到，试试更短的关键词。")
                    else -> LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(rows, key = { it.id }) { row ->
                            Column(Modifier.fillMaxWidth().clickable { selected = row; editor = true }) {
                                Text(row.title, style = MaterialTheme.typography.titleSmall)
                                Text(row.body, maxLines = 3)
                                Text("${if (row.author == "user") "你记下的" else "助手记下的"} · 修订 ${row.revision}",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { selected = null; editor = true }) { Text("记一条") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("关闭") } },
    )
    if (editor) SceneEditor(selected, onDismiss = { editor = false }, onSave = { title, body, cues, operation ->
        val old = selected
        if (old == null) repository.create(owner, operation, title, body, cues)
        else repository.edit(owner, old.id, old.revision, title, body, cues)
        editor = false
        refresh++
    }, onHistory = {
        selected?.let { row -> scope.launch {
            try { history = repository.history(owner, row.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
        } }
    }, onDelete = { deleting = selected })
    history?.let { revisions ->
        AlertDialog(onDismissRequest = { history = null }, title = { Text("记忆修订") }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (revisions.isEmpty()) item { Text("这条记忆已删除或无法读取。") }
                items(revisions, key = { it.revision }) { item -> Column {
                    Text("修订 ${item.revision} · ${if (item.author == "user") "你" else "助手"}")
                    Text(item.title)
                    SelectionContainer { Text(item.body) }
                    if (item.cues.isNotBlank()) Text("线索：${item.cues}", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }, confirmButton = { TextButton(onClick = { history = null }) { Text("返回") } })
    }
    deleting?.let { row ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除这条记忆？") },
            text = { Column {
                Text("将删除“${row.title}”及其本地修订。聊天原话不受影响，旧备份可能仍保留内容。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try { repository.delete(owner, row.id, row.revision); deleting = null; editor = false; refresh++ }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { fail(e) }
                    finally { busy = false }
                }
            }) { Text("删除") } }, dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun SceneEditor(
    scene: LocalScene?, onDismiss: () -> Unit,
    onSave: suspend (String, String, String, String) -> Unit,
    onHistory: () -> Unit, onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val operation = remember { Uuid.random().toString() }
    var title by remember { mutableStateOf(scene?.title.orEmpty()) }
    var body by remember { mutableStateOf(scene?.body.orEmpty()) }
    var cues by remember { mutableStateOf(scene?.cues.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (scene == null) "记下一件事" else "修改记忆") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(title, { if (it.length <= 120) title = it }, label = { Text("标题") }, singleLine = true, enabled = !busy) }
            item { OutlinedTextField(body, { if (it.length <= 1500) body = it }, label = { Text("记忆内容") }, minLines = 3, maxLines = 8,
                supportingText = { Text("${body.length} / 1500") }, enabled = !busy) }
            item { OutlinedTextField(cues, { if (it.length <= 800) cues = it }, label = { Text("查找线索（可选，用分号分隔）") }, maxLines = 3, enabled = !busy) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (scene != null) item { Row {
                TextButton(enabled = !busy, onClick = onHistory) { Text("查看修订") }
                TextButton(enabled = !busy, onClick = onDelete) { Text("删除记忆") }
            } }
        } }, confirmButton = { TextButton(enabled = !busy && title.isNotBlank() && body.isNotBlank(), onClick = {
            scope.launch {
                busy = true; error = null
                try { onSave(title, body, cues, operation) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = when (e) {
                    is SceneConflict, is IllegalArgumentException -> e.message
                    else -> "保存未完成，请重试。"
                } }
                finally { busy = false }
            }
        }) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
