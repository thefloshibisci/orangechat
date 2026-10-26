package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.files.attachmentReferenceGuard

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.localAttachmentFile
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ai.ui.UIMessagePart
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

@Serializable
private data class PendingChatHistory(
    val id: Uuid,
    val mode: ChatHistoryRestoreMode,
    val assistantId: Uuid,
    // Legacy/interrupted tasks require an explicit retry. New staging grants one automatic attempt.
    val allowAutomaticStart: Boolean = false,
)

data class ChatHistoryExportResult(val unavailableAttachments: Int)

/** Logical chat backup is independent of full settings/database replacement and ChatService's lifecycle. */
class ChatHistoryBackup(
    private val context: Context,
    private val repository: ConversationRepository,
    private val settingsStore: SettingsStore,
) {
    companion object {
        internal fun pendingFile(context: Context) = File(context.noBackupFilesDir, "chat-history-restore/pending.json")
        internal fun hasPending(context: Context): Boolean = pendingFile(context).exists()

        internal fun shouldStartAutomatically(context: Context): Boolean = runCatching {
            val file = pendingFile(context)
            require(file.length() in 1..4096)
            JsonInstant.decodeFromString<PendingChatHistory>(file.readText()).allowAutomaticStart
        }.getOrDefault(false)

        /** Persist before resolving the database or parsing the archive, so process death cannot
         * cause an automatic retry loop. AtomicFile retains the old plan on a failed write. */
        internal fun markAttemptStarted(context: Context) {
            val file = AtomicFile(pendingFile(context))
            val plan = JsonInstant.decodeFromString<PendingChatHistory>(
                file.openRead().bufferedReader().use { it.readText() })
            val stream = file.startWrite()
            try {
                stream.write(JsonInstant.encodeToString(plan.copy(allowAutomaticStart = false)).toByteArray())
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }

        /** Emergency exit must not resolve Room, settings, or parse the large archive. Staged
         * bytes are left for the next staging cleanup; the user's original ZIP is never touched. */
        internal fun discardPendingPointer(context: Context) {
            AtomicFile(pendingFile(context)).delete()
            check(!hasPending(context)) { "无法取消待恢复任务" }
        }
    }
    private val mutex = Mutex()
    private val root get() = File(context.noBackupFilesDir, "chat-history-restore")
    private val pending get() = AtomicFile(pendingFile(context))
    private val status get() = context.getSharedPreferences("chat_history_restore_result", Context.MODE_PRIVATE)

    fun lastRestoreResult(): String? = status.getString("result", null)
    fun hasPendingRestore(): Boolean = pending.baseFile.exists()

    suspend fun export(output: OutputStream): ChatHistoryExportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val conversations = repository.exportChatHistory()
            val sources = linkedMapOf<String, String>()
            val attachments = linkedMapOf<String, String>()
            val unavailable = hashSetOf<String>()
            val snapshots = linkedMapOf<String, File>()
            val directory = File(context.cacheDir, "chat-export-${Uuid.random()}")
            check(directory.mkdirs()) { "无法暂存聊天附件" }
            var total = 0L
            try {
                fun snapshot(url: String): Boolean {
                    if (url in unavailable) return false
                    if (url in snapshots) return true
                    val input = try {
                        val local = localAttachmentFile(url)
                        if (local != null) local.inputStream() else context.contentResolver.openInputStream(Uri.parse(url))
                    } catch (_: IOException) { null } catch (_: SecurityException) { null }
                    if (input == null) { unavailable.add(url); return false }
                    val target = File(directory, snapshots.size.toString())
                    var size = 0L
                    var readable = true
                    try {
                        val source = input
                        target.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = try { source.read(buffer) }
                                    catch (_: IOException) { readable = false; break }
                                    catch (_: SecurityException) { readable = false; break }
                                if (count < 0) break
                                size += count
                                require(total + size <= CHAT_ARCHIVE_LIMIT) { "聊天附件超过备份大小限制" }
                                out.write(buffer, 0, count)
                            }
                        }
                    } finally {
                        // A provider may revoke access while reading or closing its source.
                        // Destination writes above must still fail the whole export on IO errors.
                        try { input.close() }
                        catch (_: IOException) { readable = false }
                        catch (_: SecurityException) { readable = false }
                    }
                    if (!readable || size == 0L) {
                        target.delete()
                        unavailable.add(url)
                        return false
                    }
                    total += size
                    snapshots[url] = target
                    return true
                }
                val mapped = conversations.map { conversation ->
                    val urls = mapChatAttachmentUrls(conversation) { url ->
                        val local = localAttachmentFile(url)
                        if (local == null && !url.startsWith("content://")) {
                            require(isPortableChatUrl(url)) { "聊天记录含有无法归档的附件地址" }
                            url
                        } else {
                            if (!snapshot(url)) url else sources.getOrPut(url) {
                                val token = CHAT_ATTACHMENT_PREFIX + sources.size
                                val extension = Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")
                                    ?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,12}")) }
                                attachments[token] = "attachments/${sources.size}" + extension?.let { ".$it" }.orEmpty()
                                token
                            }
                        }
                    }
                    mapChatAttachments(urls) { part ->
                        val url = when (part) {
                            is UIMessagePart.Image -> part.url
                            is UIMessagePart.Document -> part.url
                            is UIMessagePart.Audio -> part.url
                            is UIMessagePart.Video -> part.url
                            is UIMessagePart.VoiceMessage -> part.url
                            else -> null
                        }
                        if (url == null || url !in unavailable) part else {
                            val name = if (part is UIMessagePart.Document) part.fileName else
                                Uri.parse(url).lastPathSegment.orEmpty().ifBlank { "未知附件" }
                            val detail = if (part is UIMessagePart.VoiceMessage) {
                                "\n语音时长：${part.duration} 毫秒\n语音转写：${part.transcript.ifBlank { "无" }}"
                            } else ""
                            UIMessagePart.Text("[备份时附件不可用：$name；文件缺失、为空或无法读取]$detail",
                                metadata = part.metadata)
                        }
                    }
                }
                val byToken = sources.entries.associate { (source, token) -> token to source }
                val archive = ChatHistoryArchive(conversations = mapped, attachments = attachments,
                    favoriteNodeIds = conversations.flatMap { it.messageNodes }.filter { it.isFavorite }.map { it.id }.toSet())
                writeChatArchive(output, archive) { token ->
                    val source = byToken.getValue(token)
                    snapshots.getValue(source).inputStream()
                }
                ChatHistoryExportResult(unavailable.size)
            } finally { directory.deleteRecursively() }
        }
    }

    /** Staging never mutates the live DB. Only a fully validated, durable archive becomes pending. */
    suspend fun stage(input: InputStream, mode: ChatHistoryRestoreMode): Int = withContext(Dispatchers.IO) {
        BackupArchiveRestorer.restoreMutex.withLock {
            mutex.withLock {
                check(!hasPendingRestore()) { "已有待恢复的聊天备份，请先重启或取消" }
                check(!File(context.filesDir, ".backup_restore/.applying").exists() &&
                    !File(context.filesDir, "pending_database_restore/.ready").exists()) { "请先重启，完成之前的完整备份恢复" }
                check(root.mkdirs() || root.isDirectory)
                // Previous failed/cancelled staging holds no live data. Keep at most the current staged archive.
                root.listFiles()?.filter { it.isDirectory && runCatching { Uuid.parse(it.name) }.isSuccess }
                    ?.forEach { it.deleteRecursively() }
                val plan = PendingChatHistory(Uuid.random(), mode, settingsStore.settingsFlowRaw.first().assistantId,
                    allowAutomaticStart = true)
                val directory = File(root, plan.id.toString())
                try {
                    val archive = readChatArchive(input, directory)
                    // Flush extracted files before publishing the small atomic pointer.
                    directory.walkTopDown().filter { it.isFile }.forEach { file ->
                        FileOutputStream(file, true).use { it.fd.sync() }
                    }
                    writeAtomic(pending, JsonInstant.encodeToString(plan).toByteArray())
                    archive.conversations.size
                } catch (error: Throwable) {
                    if (!hasPendingRestore()) directory.deleteRecursively()
                    throw error
                }
            }
        }
    }

    suspend fun cancelPending() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val plan = if (hasPendingRestore()) JsonInstant.decodeFromString<PendingChatHistory>(
                pending.openRead().bufferedReader().use { it.readText() }) else null
            pending.delete()
            check(!hasPendingRestore()) { "无法取消待恢复任务" }
            plan?.let { File(root, it.id.toString()).deleteRecursively() }
        }
    }

    /** Called before WorkManager/ChatService startup. Replay after process death is ID-based and idempotent. */
    suspend fun applyPendingBeforeServices(onProgress: (ChatRestoreProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        attachmentReferenceGuard.beginWrite().use {
            mutex.withLock {
                if (!hasPendingRestore()) return@withLock
                val plan = try {
                    JsonInstant.decodeFromString<PendingChatHistory>(pending.openRead().bufferedReader().use { it.readText() })
                } catch (error: Exception) {
                    status.edit().putString("result", "待恢复任务损坏，聊天记录未修改：${error.message}").commit()
                    pending.delete()
                    check(!hasPendingRestore()) { "无法清除损坏的待恢复任务" }
                    return@withLock
                }
                val directory = File(root, plan.id.toString())
                var completed = false
                val result = try {
                    onProgress(ChatRestoreProgress("正在读取聊天备份…"))
                    val archive = JsonInstant.decodeFromString<ChatHistoryArchive>(File(directory, "chats.json").readText())
                    coroutineContext.ensureActive()
                    onProgress(ChatRestoreProgress("正在校验 ${archive.conversations.size} 个会话…", ChatRestoreStep.VALIDATING))
                    validateChatHistory(archive.conversations)
                    val selected = archive.conversations.filter { conversation ->
                        plan.mode != ChatHistoryRestoreMode.INCREMENTAL || !repository.existsConversationById(conversation.id)
                    }
                    val neededAttachments = hashSetOf<String>()
                    selected.forEach { conversation ->
                        mapChatAttachmentUrls(conversation) { url ->
                            neededAttachments.add(url)
                            url
                        }
                    }
                    coroutineContext.ensureActive()
                    val settings = settingsStore.settingsFlowRaw.first()
                    val assistants = settings.assistants.map { it.id }.toSet()
                    val fallback = plan.assistantId.takeIf { it in assistants }
                        ?: settings.assistantId.takeIf { it in assistants } ?: settings.assistants.first().id
                    val selectedAttachments = archive.attachments.filterKeys { it in neededAttachments }
                    var copied = 0
                    val uploadDir = File(context.filesDir, "upload")
                    check(uploadDir.mkdirs() || uploadDir.isDirectory)
                    val dedup = ChatAttachmentDedup(uploadDir)
                    val urls = selectedAttachments.mapValues { (_, name) ->
                        coroutineContext.ensureActive()
                        onProgress(ChatRestoreProgress("正在恢复附件 $copied/${selectedAttachments.size}…",
                            ChatRestoreStep.WRITING, copied, selectedAttachments.size))
                        val source = File(directory, name)
                        check(source.isFile) { "待恢复的附件缺失" }
                        val target = File(uploadDir, "chat-${plan.id}-${source.name}")
                        // Replay after process death keeps using the copy it already made. Otherwise
                        // reuse a byte-identical file already on this device instead of duplicating it.
                        val existing = if (target.exists()) null else dedup.findExisting(source)
                        if (existing != null) {
                            copied++
                            onProgress(ChatRestoreProgress("已恢复附件 $copied/${selectedAttachments.size}",
                                ChatRestoreStep.WRITING, copied, selectedAttachments.size))
                            return@mapValues existing.toUri().toString()
                        }
                        if (!target.exists()) {
                            val temp = File(target.parentFile, ".${target.name}.tmp")
                            try {
                                FileOutputStream(temp).use { out ->
                                    source.inputStream().use { input ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            coroutineContext.ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            out.write(buffer, 0, count)
                                        }
                                    }
                                    out.fd.sync()
                                }
                                check(temp.renameTo(target)) { "无法保存聊天附件" }
                                dedup.register(target)
                            } finally { temp.delete() }
                        }
                        copied++
                        onProgress(ChatRestoreProgress("已恢复附件 $copied/${selectedAttachments.size}",
                            ChatRestoreStep.WRITING, copied, selectedAttachments.size))
                        target.toUri().toString()
                    }
                    val conversations = selected.map { conversation ->
                        mapChatAttachmentUrls(conversation) { urls[it] ?: it }.copy(
                            assistantId = conversation.assistantId.takeIf { it in assistants } ?: fallback,
                            workspaceCwd = null,
                        )
                    }
                    val restoredCount = repository.restoreChatHistory(conversations, plan.mode, archive.favoriteNodeIds) { done, total ->
                        onProgress(ChatRestoreProgress("已处理会话 $done/$total（含搜索索引）",
                            ChatRestoreStep.WRITING, done, total))
                    }
                    completed = true
                    if (plan.mode == ChatHistoryRestoreMode.INCREMENTAL) {
                        "已新增 $restoredCount 个会话，跳过 ${archive.conversations.size - restoredCount} 个同 ID 会话（本机记录原样保留）"
                    } else "已恢复 $restoredCount 个会话（新增缺少的会话，覆盖同 ID 会话）"
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Repository transaction rolls back in full. Retain staged bytes for diagnosis; never retry silently.
                    "聊天恢复失败，原有记录保留：${error.message}"
                }
                status.edit().putString("result", result).commit()
                pending.delete()
                // Fail closed if the pointer cannot be cleared: do not allow sessions to write before replay.
                check(!hasPendingRestore()) { "无法清除待恢复任务，请重新启动" }
                if (completed) onProgress(ChatRestoreProgress("正在完成恢复…", ChatRestoreStep.FINISHING))
                if (completed) directory.deleteRecursively()
            }
        }
    }

    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }
}
