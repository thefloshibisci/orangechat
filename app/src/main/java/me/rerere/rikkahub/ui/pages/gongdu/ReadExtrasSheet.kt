package me.rerere.rikkahub.ui.pages.gongdu

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.reading.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ReadSaveRequest(val kind: String, val bookId: String?)

/** The same paper, typography and controls as the reader; no second settings application. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadExtrasSheet(
    repo: ReadRepository, conversationId: String, assistantId: String?, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentBook by repo.currentBook.collectAsStateWithLifecycle()
    val session by repo.session.collectAsStateWithLifecycle()
    val records by repo.records.collectAsStateWithLifecycle()
    val library by repo.library.collectAsStateWithLifecycle()
    val book = currentBook?.takeIf { session.conversationId == conversationId }
    val ownRecords = records.filter { it.bookId == book?.id && it.conversationId == conversationId && it.assistantId == assistantId }
    var draft by remember(book?.id, conversationId, assistantId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ReadBackup?>(null) }
    var request by remember { mutableStateOf<ReadSaveRequest?>(null) }

    fun action(block: suspend () -> String) {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try { feedback = block(); failed = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { feedback = error.message ?: "操作未完成，请重新选择文件后再试"; failed = true }
            finally { busy = false }
        }
    }
    fun saveDocument(uri: Uri?) {
        val captured = request
        request = null
        if (uri == null || captured == null) return
        action {
            val snapshot = repo.exportSnapshot(if (captured.kind == "backup") null else captured.bookId)
            val bytes = withContext(Dispatchers.Default) {
                if (captured.kind == "backup") ReadArchive.encode(snapshot)
                else {
                    val selected = snapshot.books.firstOrNull { it.id == captured.bookId } ?: error("这本书已移出书架，请重新打开后导出")
                    val notes = snapshot.annotations.filter { it.bookId == selected.id && it.conversationId == conversationId && it.assistantId == assistantId }
                    val journal = snapshot.records.filter { it.bookId == selected.id && it.conversationId == conversationId && it.assistantId == assistantId }
                    if (captured.kind == "markdown") ReadArchive.markdown(selected, notes, journal).toByteArray(Charsets.UTF_8)
                    else ReadArchive.epub(selected, notes, journal)
                }
            }
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入此位置，请选择另一个文件夹")
                output.use { it.write(bytes); it.flush() }
            }
            if (captured.kind == "backup") "阅读备份已保存，包含书籍、插图、进度、批注和记录" else "已导出本书及当前聊天的批注与札记"
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), ::saveDocument)
    val markdownLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), ::saveDocument)
    val epubLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip"), ::saveDocument)
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            preview = withContext(Dispatchers.IO) {
                val source = context.contentResolver.openInputStream(uri) ?: error("无法打开所选备份")
                val bytes = source.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        require(output.size().toLong() + size <= READ_BACKUP_MAX_BYTES) { "阅读备份超过 64 MB，请选择较小的备份" }
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                }
                ReadArchive.decode(bytes)
            }
            "备份已检查，请确认要恢复的书籍"
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        ReadExtrasContent(book?.title, ownRecords, draft, busy, assistantId != null && book != null,
            feedback, failed, Modifier.fillMaxWidth().fillMaxHeight(.93f).imePadding(),
            onDraft = { if (it.length <= 4000) draft = it },
            onSave = {
                val selected = book
                val companion = assistantId
                val text = draft
                if (selected != null && companion != null) action {
                    repo.addJournal(selected.id, conversationId, companion, text)
                    draft = ""
                    "札记已留在这本书里"
                }
            }, onBackup = { request = ReadSaveRequest("backup", null); backupLauncher.launch("共度阅读-${System.currentTimeMillis()}.zip") },
            onRestore = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            onMarkdown = {
                request = ReadSaveRequest("markdown", book?.id)
                markdownLauncher.launch(readExportFilename(book?.title.orEmpty()) + ".md")
            }, onEpub = {
                request = ReadSaveRequest("epub", book?.id)
                epubLauncher.launch(readExportFilename(book?.title.orEmpty()) + "-共读.epub")
            })
    }
    preview?.let { backup ->
        val existing = library.map { it.id }.toSet()
        val added = backup.books.filterNot { it.id in existing }
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("恢复阅读备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("新增 ${added.size} 本，跳过已有 ${backup.books.size - added.size} 本。")
                    Text(added.take(5).joinToString("\n") { "《${it.title}》" }.ifBlank { "这些书已经在书架中了" })
                    Text("新增书籍会带回进度、批注和记录。已有书籍与当前外观保留；陪读授权需要重新开启。")
                }
            }, confirmButton = {
                TextButton(enabled = !busy && added.isNotEmpty(), onClick = { action {
                    val result = repo.restoreSnapshot(backup)
                    preview = null
                    "已恢复 ${result.imported} 本书，跳过已有 ${result.skipped} 本" +
                        result.warning?.let { "\n$it" }.orEmpty()
                } }) { Text(if (busy) "正在恢复…" else "恢复新增书籍") }
            }, dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("取消") } })
    }
}

@Composable
internal fun ReadExtrasContent(
    bookTitle: String?, records: List<ReadRecord>, draft: String, busy: Boolean, canWrite: Boolean,
    feedback: String?, failed: Boolean, modifier: Modifier = Modifier,
    onDraft: (String) -> Unit, onSave: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit,
    onMarkdown: () -> Unit, onEpub: () -> Unit,
) {
    val seconds = records.filter { it.kind == "human_reading" }.sumOf { it.seconds.toLong() }
    val assistantReads = records.count { it.kind == "assistant_reading" }
    val journals = records.filter { it.kind == "journal" }.sortedByDescending { it.createdAt }
    val formatter = remember { SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE) }
    LazyColumn(modifier.testTag("read-extras"), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("我们的阅读札记", fontFamily = GongduBook, style = MaterialTheme.typography.headlineSmall)
            Text(bookTitle?.let { "《$it》 · 当前聊天" } ?: "打开一本书，就能留下札记", Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (feedback != null) item {
            Text(feedback, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("read-extras-feedback"))
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (bookTitle != null) {
            item {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("我的阅读 ${seconds / 60} 分 ${seconds % 60} 秒", fontFamily = GongduBook,
                            style = MaterialTheme.typography.titleLarge)
                        Text("助手读取 $assistantReads 个片段 · 札记 ${journals.size} 则", style = MaterialTheme.typography.bodyMedium)
                        Text("只记录前台且近期有操作的时间；助手只记录实际读取过的片段。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        records.lastOrNull { it.kind == "assistant_reading" }?.let {
                            Text("助手最近读到 第 ${it.chapter + 1} 章 · 第 ${it.paragraph + 1} 段 · 字符 ${it.start}–${it.end}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(value = draft, onValueChange = onDraft, label = { Text("读完这一页，想记住什么？") },
                    supportingText = { Text("${draft.length} / 4000") }, minLines = 3, maxLines = 6,
                    enabled = !busy && canWrite, modifier = Modifier.fillMaxWidth().testTag("read-journal-input"))
                TextButton(enabled = !busy && canWrite && draft.isNotBlank(), onClick = onSave,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("留下札记") }
            }
            items(journals, key = { it.id }) { record ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${if (record.author == "assistant") "助手" else "我"} · ${formatter.format(Date(record.createdAt))}",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Text(record.text, style = MaterialTheme.typography.bodyLarge)
                        Text("第 ${record.chapter + 1} 章 · 第 ${record.paragraph + 1} 段",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("把阅读留存下来", fontFamily = GongduBook, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Text("阅读备份包含整个书架；本书导出包含当前聊天的批注和札记。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBackup, enabled = !busy) { Text("保存阅读备份") }
                OutlinedButton(onClick = onRestore, enabled = !busy) { Text("从备份恢复") }
            }
            if (bookTitle != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onMarkdown, enabled = !busy) { Text("导出 Markdown") }
                TextButton(onClick = onEpub, enabled = !busy) { Text("导出批注 EPUB") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

internal fun readExportFilename(title: String): String = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
    .trim().take(80).ifBlank { "共度阅读" }
