package me.rerere.rikkahub.data.sync

import android.app.Application
import androidx.core.net.toUri
import androidx.room.Room
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.testing.HostFileUriShadow
import me.rerere.rikkahub.testing.TestAndroidKeyStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.security.Security
import io.pebbletemplates.pebble.PebbleEngine
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class, shadows = [HostFileUriShadow::class])
class ChatHistoryRestoreIntegrationTest {
    @Test fun `new staged plan permits one automatic attempt and interrupted plan requires confirmation`() = runBlocking {
        fixture { _, _, _, _, backup ->
            val context = RuntimeEnvironment.getApplication()
            val output = ByteArrayOutputStream()
            writeChatArchive(output, ChatHistoryArchive(conversations = listOf(conversation("backup")))) { error("no attachment") }
            backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.OVERWRITE_MATCHING)
            assertTrue(ChatHistoryBackup.shouldStartAutomatically(context))
            ChatHistoryBackup.markAttemptStarted(context)
            assertFalse(ChatHistoryBackup.shouldStartAutomatically(context))
            // New reader after simulated process death sees the durable consumed attempt.
            assertTrue(ChatHistoryBackup.hasPending(context))
            backup.applyPendingBeforeServices()
            assertFalse(ChatHistoryBackup.hasPending(context))
            backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.INCREMENTAL)
            assertTrue(ChatHistoryBackup.shouldStartAutomatically(context))
            backup.cancelPending()
            assertFalse(ChatHistoryBackup.shouldStartAutomatically(context))
        }
    }

    private fun conversation(text: String) = Conversation(assistantId = Uuid.random(), title = text,
        messageNodes = listOf(UIMessage.user(text).toMessageNode(), UIMessage.assistant("reply").toMessageNode()),
        createAt = Instant.ofEpochMilli(1000), updateAt = Instant.ofEpochMilli(2000))

    @Test fun `persisted repeated message IDs export and restore losslessly without mutating live history`() = runBlocking {
        fixture { db, repository, _, settings, backup ->
            val shared = UIMessage.assistant("legacy original")
            val variant = shared.copy(parts = listOf(UIMessagePart.Text("legacy variant")))
            val first = shared.toMessageNode().copy(messages = listOf(shared, variant, shared), selectIndex = 1)
            val second = variant.toMessageNode()
            val original = conversation("legacy").copy(assistantId = settings.settingsFlow.value.assistantId,
                messageNodes = listOf(first, second), compressionSummaries = listOf(ConversationSummary(text = "legacy summary")),
                compressedMessageNodeIds = setOf(first.id))
            repository.insertConversation(original)
            val snapshot = repository.exportChatHistory().single()
            val output = ByteArrayOutputStream()
            backup.export(output)
            assertEquals(snapshot, repository.exportChatHistory().single())
            val changed = snapshot.copy(title = "keep current in incremental")
            repository.updateConversation(changed)
            backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.INCREMENTAL)
            backup.applyPendingBeforeServices()
            assertEquals(changed, repository.exportChatHistory().single())
            assertTrue(backup.lastRestoreResult()!!.contains("已新增 0 个会话，跳过 1 个同 ID 会话"))
            backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.OVERWRITE_MATCHING)
            backup.applyPendingBeforeServices()
            assertEquals(snapshot, repository.exportChatHistory().single())
            assertTrue(backup.lastRestoreResult()!!.contains("已恢复 1 个会话"))
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM message_fts WHERE conversation_id = '${original.id}'").use {
                assertTrue(it.moveToFirst()); assertEquals(4, it.getInt(0))
            }
        }
    }

    @Test fun `overwrite replaces matching chats only and preserves compression branches favorites and unrelated settings`() = runBlocking {
        fixture { db, repository, _, settings, _ ->
            val original = conversation("local")
            val unrelated = conversation("unrelated")
            repository.insertConversation(original)
            repository.insertConversation(unrelated)
            val branch = original.messageNodes.last().copy(messages = listOf(UIMessage.assistant("branch0"), UIMessage.assistant("branch1")), selectIndex = 1)
            val incoming = original.copy(title = "backup", messageNodes = listOf(original.messageNodes.first(), branch),
                compressionSummaries = listOf(ConversationSummary(text = "structured summary")),
                compressedMessageNodeIds = setOf(original.messageNodes.first().id), compressionRevision = 4)
            val beforeSettings = settings.settingsFlow.value
            val added = conversation("new conversation")
            assertEquals(2, repository.restoreChatHistory(listOf(incoming, added),
                ChatHistoryRestoreMode.OVERWRITE_MATCHING, setOf(branch.id)))
            val result = repository.getConversationById(original.id)!!
            assertEquals(incoming.compressionSummaries, result.compressionSummaries)
            assertEquals(incoming.compressedMessageNodeIds, result.compressedMessageNodeIds)
            assertEquals(incoming.messageNodes.map { it.messages }, result.messageNodes.map { it.messages })
            assertEquals(1, result.messageNodes.last().selectIndex)
            assertTrue(result.messageNodes.last().isFavorite)
            assertEquals(added, repository.getConversationById(added.id))
            assertEquals(unrelated, repository.getConversationById(unrelated.id))
            assertEquals(beforeSettings, settings.settingsFlow.value)
            assertEquals(3, db.conversationDao().getAllIds().size)
        }
    }

    @Test fun `failure in second conversation rolls back first update and search index`() = runBlocking {
        fixture { db, repository, _, _, _ ->
            val first = conversation("first")
            val second = conversation("second")
            repository.insertConversation(first)
            repository.insertConversation(second)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_chat_restore BEFORE INSERT ON message_node WHEN NEW.conversation_id = '${second.id}' BEGIN SELECT RAISE(ABORT, 'reject'); END")
            assertTrue(runCatching {
                repository.restoreChatHistory(listOf(first.copy(title = "changed"), second.copy(title = "changed2")), ChatHistoryRestoreMode.OVERWRITE_MATCHING)
            }.isFailure)
            assertEquals(first, repository.getConversationById(first.id))
            assertEquals(second, repository.getConversationById(second.id))
            db.openHelper.writableDatabase.query("SELECT title FROM message_fts WHERE conversation_id = '${first.id}'").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("first", cursor.getString(0))
            }
        }
    }

    @Test fun `search index failure rolls back chat replacement`() = runBlocking {
        fixture { db, repository, _, _, _ ->
            val original = conversation("original")
            repository.insertConversation(original)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_chat_search BEFORE INSERT ON message_fts BEGIN SELECT RAISE(ABORT, 'reject index'); END")
            assertTrue(runCatching {
                repository.restoreChatHistory(listOf(original.copy(title = "changed")), ChatHistoryRestoreMode.OVERWRITE_MATCHING)
            }.isFailure)
            assertEquals(original, repository.getConversationById(original.id))
        }
    }

    @Test fun `global node collision cannot steal another conversation history`() = runBlocking {
        fixture { _, repository, _, _, _ ->
            val original = conversation("original")
            repository.insertConversation(original)
            val incoming = conversation("incoming").copy(messageNodes = original.messageNodes)
            assertTrue(runCatching { repository.restoreChatHistory(listOf(incoming), ChatHistoryRestoreMode.INCREMENTAL) }.isFailure)
            assertEquals(original, repository.getConversationById(original.id))
            assertNull(repository.getConversationById(incoming.id))
        }
    }

    @Test fun `incremental restore skips matching conversations entirely and only inserts new IDs idempotently`() = runBlocking {
        fixture { db, repository, _, _, _ ->
            val base = conversation("original")
            val selected = base.messageNodes.last().copy(messages = listOf(UIMessage.assistant("local0"),
                UIMessage.assistant("local1")), selectIndex = 1)
            val original = base.copy(messageNodes = listOf(base.messageNodes.first(), selected),
                compressionSummaries = listOf(ConversationSummary(text = "local summary")),
                compressedMessageNodeIds = setOf(base.messageNodes.first().id), compressionRevision = 3)
            repository.restoreChatHistory(listOf(original), ChatHistoryRestoreMode.OVERWRITE_MATCHING, setOf(selected.id))
            val before = repository.exportChatHistory().single()
            val unrelated = conversation("unrelated")
            repository.insertConversation(unrelated)
            val branch = original.messageNodes.first().copy(messages = listOf(
                original.messageNodes.first().currentMessage.copy(parts = listOf(UIMessagePart.Text("backup conflict"))),
                UIMessage.user("new branch")), selectIndex = 1)
            val extra = UIMessage.user("new last").toMessageNode()
            val incoming = original.copy(title = "backup title", messageNodes = listOf(branch, original.messageNodes.last(), extra),
                compressionSummaries = emptyList(), compressedMessageNodeIds = emptySet())
            val added = conversation("added")
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER reject_skipped_update BEFORE UPDATE ON conversationentity WHEN OLD.id = '${original.id}' BEGIN SELECT RAISE(ABORT, 'must skip'); END")
            sql.execSQL("CREATE TRIGGER reject_skipped_nodes BEFORE DELETE ON message_node WHEN OLD.conversation_id = '${original.id}' BEGIN SELECT RAISE(ABORT, 'must skip'); END")
            sql.execSQL("CREATE TRIGGER reject_skipped_search BEFORE DELETE ON message_fts WHEN OLD.conversation_id = '${original.id}' BEGIN SELECT RAISE(ABORT, 'must skip'); END")
            assertEquals(1, repository.restoreChatHistory(listOf(incoming, added), ChatHistoryRestoreMode.INCREMENTAL,
                setOf(branch.id, added.messageNodes.first().id)))
            assertEquals(0, repository.restoreChatHistory(listOf(incoming, added), ChatHistoryRestoreMode.INCREMENTAL))
            assertEquals(before, repository.exportChatHistory().single { it.id == original.id })
            assertEquals(unrelated, repository.getConversationById(unrelated.id))
            val restoredNew = repository.getConversationById(added.id)!!
            assertEquals(added.messageNodes.map { it.messages }, restoredNew.messageNodes.map { it.messages })
            assertTrue(restoredNew.messageNodes.first().isFavorite)
            assertEquals(3, db.conversationDao().getAllIds().size)
        }
    }

    @Test fun `incremental startup skips existing chat attachments and reports actual added and skipped counts`() = runBlocking {
        fixture { _, repository, _, settings, backup ->
            val context = RuntimeEnvironment.getApplication()
            val original = conversation("keep local").copy(assistantId = settings.settingsFlow.value.assistantId)
            repository.insertConversation(original)
            val skippedToken = CHAT_ATTACHMENT_PREFIX + "0"
            val addedToken = CHAT_ATTACHMENT_PREFIX + "1"
            val incoming = original.copy(title = "backup title", messageNodes = listOf(
                UIMessage.user("backup").copy(parts = listOf(UIMessagePart.VoiceMessage(skippedToken, 10, "skip"))).toMessageNode()))
            val added = conversation("added").copy(assistantId = original.assistantId, messageNodes = listOf(
                UIMessage.user("new").copy(parts = listOf(UIMessagePart.VoiceMessage(addedToken, 20, "new"))).toMessageNode()))
            val output = ByteArrayOutputStream()
            writeChatArchive(output, ChatHistoryArchive(conversations = listOf(incoming, added),
                attachments = mapOf(skippedToken to "attachments/0.bin", addedToken to "attachments/1.bin"))) {
                if (it == skippedToken) byteArrayOf(1).inputStream() else byteArrayOf(2, 3).inputStream()
            }
            val uploads = File(context.filesDir, "upload")
            val beforeFiles = uploads.listFiles().orEmpty().mapTo(hashSetOf()) { it.name }
            assertEquals(2, backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.INCREMENTAL))
            backup.applyPendingBeforeServices()
            assertEquals(original, repository.getConversationById(original.id))
            val voice = repository.getConversationById(added.id)!!.messageNodes.single().currentMessage.parts.single() as UIMessagePart.VoiceMessage
            assertArrayEquals(byteArrayOf(2, 3), File(java.net.URI(voice.url)).readBytes())
            val published = uploads.listFiles().orEmpty().filter { it.name !in beforeFiles }
            assertEquals(listOf(File(java.net.URI(voice.url)).name), published.map { it.name })
            assertTrue(backup.lastRestoreResult()!!.contains("已新增 1 个会话，跳过 1 个同 ID 会话"))
            backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.INCREMENTAL)
            backup.applyPendingBeforeServices()
            assertTrue(backup.lastRestoreResult()!!.contains("已新增 0 个会话，跳过 2 个同 ID 会话"))
            assertEquals(beforeFiles + published.map { it.name }, uploads.listFiles().orEmpty().mapTo(hashSetOf()) { it.name })
        }
    }

    @Test fun `stage does not mutate live chat and startup restores local voice and tool attachment bytes`() = runBlocking {
        fixture { _, repository, files, _, backup ->
            val file = files.saveManagedFromBytes(FileFolders.UPLOAD, byteArrayOf(0, 1, 2, -1), "sound.bin")
            val url = files.getFile(file).toUri().toString()
            val original = conversation("original").let { chat -> chat.copy(messageNodes = listOf(chat.messageNodes.first().copy(
                messages = listOf(UIMessage.user("original").copy(parts = listOf(UIMessagePart.VoiceMessage(url, 10, "voice"),
                    UIMessagePart.Tool("call", "tool", "{}", listOf(UIMessagePart.Document(url, "sound.bin"))))))))) }
            repository.insertConversation(original)
            val output = ByteArrayOutputStream()
            backup.export(output)
            val uploads = files.getFile(file).parentFile!!
            val beforeFiles = uploads.listFiles().orEmpty().mapTo(hashSetOf()) { it.name }
            val changed = original.copy(title = "current title")
            repository.updateConversation(changed)
            assertEquals(1, backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.OVERWRITE_MATCHING))
            assertEquals("current title", repository.getConversationById(original.id)!!.title)
            assertTrue(backup.hasPendingRestore())
            backup.applyPendingBeforeServices()
            assertFalse(backup.hasPendingRestore())
            val result = repository.getConversationById(original.id)!!
            assertEquals("original", result.title)
            val voice = result.messageNodes.single().currentMessage.parts.first() as UIMessagePart.VoiceMessage
            // Same device: the byte-identical upload is reused instead of written a second time.
            assertEquals(url, voice.url)
            assertArrayEquals(byteArrayOf(0, 1, 2, -1), File(java.net.URI(voice.url)).readBytes())
            assertEquals(beforeFiles, uploads.listFiles().orEmpty().mapTo(hashSetOf()) { it.name })
            backup.applyPendingBeforeServices()
            assertEquals(result, repository.getConversationById(original.id))
        }
    }

    @Test fun `cancel and invalid archive leave database unchanged and no pending restore`() = runBlocking {
        fixture { _, repository, _, _, backup ->
            val original = conversation("original")
            repository.insertConversation(original)
            val output = ByteArrayOutputStream()
            backup.export(output)
            backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.INCREMENTAL)
            assertTrue(runCatching { backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.INCREMENTAL) }.isFailure)
            backup.cancelPending()
            assertFalse(backup.hasPendingRestore())
            assertTrue(runCatching { backup.stage(byteArrayOf(1, 2, 3).inputStream(), ChatHistoryRestoreMode.INCREMENTAL) }.isFailure)
            assertFalse(backup.hasPendingRestore())
            assertEquals(original, repository.getConversationById(original.id))
        }
    }

    @Test fun `cleared compression model persists as absent without disturbing selected assistant`() = runBlocking {
        fixture { _, _, _, settings, _ ->
            val selected = settings.settingsFlowRaw.first().assistantId
            val model = Uuid.random()
            settings.update { it.copy(compressModelId = model) }
            assertEquals(model, settings.settingsFlowRaw.first().compressModelId)
            settings.update { it.copy(compressModelId = null) }
            val stored = settings.settingsFlowRaw.first()
            assertNull(stored.compressModelId)
            assertEquals(selected, stored.assistantId)
        }
    }

    @Test fun `startup restore failure reports rollback and does not silently retry`() = runBlocking {
        fixture { db, repository, _, _, backup ->
            val original = conversation("original")
            repository.insertConversation(original)
            val output = ByteArrayOutputStream()
            backup.export(output)
            val changed = original.copy(title = "keep current")
            repository.updateConversation(changed)
            backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.OVERWRITE_MATCHING)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON message_node BEGIN SELECT RAISE(ABORT, 'reject'); END")
            backup.applyPendingBeforeServices()
            assertFalse(backup.hasPendingRestore())
            assertTrue(backup.lastRestoreResult()!!.contains("失败"))
            assertEquals(changed, repository.getConversationById(original.id))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_restore")
            backup.applyPendingBeforeServices()
            assertEquals(changed, repository.getConversationById(original.id))
        }
    }

    @Test fun `chat staging rejects a full database restore awaiting restart`() = runBlocking {
        fixture { _, repository, _, _, backup ->
            repository.insertConversation(conversation("original"))
            val output = ByteArrayOutputStream()
            backup.export(output)
            val context = RuntimeEnvironment.getApplication()
            val marker = File(context.filesDir, "pending_database_restore/.ready")
            marker.parentFile!!.mkdirs()
            marker.writeText("ready")
            try {
                assertTrue(runCatching { backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.INCREMENTAL) }.isFailure)
                assertFalse(backup.hasPendingRestore())
            } finally { marker.delete() }
        }
    }

    @Test fun `corrupt pending pointer is retired without touching chats`() = runBlocking {
        fixture { _, repository, _, _, backup ->
            val original = conversation("original")
            repository.insertConversation(original)
            val pointer = ChatHistoryBackup.pendingFile(RuntimeEnvironment.getApplication())
            pointer.parentFile!!.mkdirs()
            pointer.writeText("broken pointer")
            backup.applyPendingBeforeServices()
            assertFalse(backup.hasPendingRestore())
            assertTrue(backup.lastRestoreResult()!!.contains("损坏"))
            assertEquals(original, repository.getConversationById(original.id))
        }
    }

    @Test fun `full restore cannot replace files while a logical chat restore is pending`() = runBlocking {
        fixture { db, repository, _, settings, backup ->
            repository.insertConversation(conversation("original"))
            val output = ByteArrayOutputStream()
            backup.export(output)
            backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.INCREMENTAL)
            val context = RuntimeEnvironment.getApplication()
            val full = BackupArchiveRestorer(context, settings, me.rerere.rikkahub.utils.JsonInstant,
                DatabaseBackupCoordinator(context, db))
            val error = runCatching { full.restore(File(context.cacheDir, "not-opened.zip"), true, true) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("请先取消"))
            assertTrue(backup.hasPendingRestore())
            backup.cancelPending()
        }
    }

    @Test fun `missing empty and revoked attachments export as text without changing local history and restore correctly`() = runBlocking {
        fixture { _, repository, files, settings, backup ->
            val context = RuntimeEnvironment.getApplication()
            suspend fun managed(name: String): File {
                return files.getFile(files.saveManagedFromBytes(FileFolders.UPLOAD, byteArrayOf(1, 2, 3), name))
            }
            val missing = managed("voice_missing.wav")
            val empty = managed("empty_document.bin")
            val valid = managed("valid_sound.wav")
            val missingUrl = missing.toUri().toString()
            val emptyUrl = empty.toUri().toString()
            val validUrl = valid.toUri().toString()
            val deniedUri = android.net.Uri.parse("content://backup-test/revoked.wav")
            org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(deniedUri,
                object : java.io.InputStream() {
                    override fun read(): Int = throw SecurityException("grant revoked")
                })
            val parts = listOf(
                UIMessagePart.VoiceMessage(missingUrl, 1200, "保留这段语音转写"),
                UIMessagePart.Document(emptyUrl, "空文件.bin"),
                UIMessagePart.Tool("call", "media", "{}", listOf(UIMessagePart.Audio(missingUrl),
                    UIMessagePart.Video(missingUrl))),
                UIMessagePart.Audio(deniedUri.toString()),
                UIMessagePart.VoiceMessage(validUrl, 300, "valid"),
                UIMessagePart.Image("https://example.com/remote.png"),
            )
            val base = conversation("attachments").copy(assistantId = settings.settingsFlow.value.assistantId)
            val branch = base.messageNodes.last().copy(messages = listOf(
                UIMessage.assistant("branch 0").copy(parts = parts), UIMessage.assistant("selected branch")),
                selectIndex = 1)
            val original = base.copy(messageNodes = listOf(base.messageNodes.first(), branch),
                compressionSummaries = listOf(ConversationSummary(text = "preserved summary")),
                compressedMessageNodeIds = setOf(base.messageNodes.first().id))
            repository.restoreChatHistory(listOf(original), ChatHistoryRestoreMode.OVERWRITE_MATCHING, setOf(branch.id))
            assertTrue(missing.delete())
            empty.writeBytes(byteArrayOf())
            val before = repository.exportChatHistory().single()
            val output = ByteArrayOutputStream()
            assertEquals(3, backup.export(output).unavailableAttachments)
            assertEquals(before, repository.exportChatHistory().single())
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("chat-export-") })
            val extraction = File(context.cacheDir, "missing-attachment-inspection")
            try {
                val archive = readChatArchive(output.toByteArray().inputStream(), extraction)
                assertEquals(1, archive.attachments.size)
                assertArrayEquals(byteArrayOf(1, 2, 3), File(extraction, archive.attachments.values.single()).readBytes())
                val archived = archive.conversations.single()
                assertEquals(before.messageNodes.map { it.id }, archived.messageNodes.map { it.id })
                assertEquals(before.messageNodes.map { it.messages.map { msg -> msg.id } },
                    archived.messageNodes.map { it.messages.map { msg -> msg.id } })
                assertEquals(1, archived.messageNodes.last().selectIndex)
                assertEquals(before.compressionSummaries, archived.compressionSummaries)
                assertEquals(before.compressedMessageNodeIds, archived.compressedMessageNodeIds)
                assertEquals(setOf(branch.id), archive.favoriteNodeIds)
                val savedParts = archived.messageNodes.last().messages.first().parts
                assertTrue((savedParts[0] as UIMessagePart.Text).text.contains("保留这段语音转写"))
                assertTrue((savedParts[0] as UIMessagePart.Text).text.contains(missing.name))
                assertTrue((savedParts[1] as UIMessagePart.Text).text.contains("空文件.bin"))
                val tool = savedParts[2] as UIMessagePart.Tool
                assertTrue(tool.isExecuted)
                assertEquals(2, tool.output.size)
                assertTrue(tool.output.all { it is UIMessagePart.Text })
                assertTrue(savedParts[3] is UIMessagePart.Text)
                assertEquals(parts.last(), savedParts.last())
            } finally { extraction.deleteRecursively() }
            backup.stage(output.toByteArray().inputStream(), ChatHistoryRestoreMode.OVERWRITE_MATCHING)
            backup.applyPendingBeforeServices()
            assertTrue(backup.lastRestoreResult()!!.contains("已恢复 1 个会话"))
            val restored = repository.exportChatHistory().single()
            val restoredParts = restored.messageNodes.last().messages.first().parts
            assertTrue((restoredParts.first() as UIMessagePart.Text).text.contains("保留这段语音转写"))
            val restoredVoice = restoredParts[4] as UIMessagePart.VoiceMessage
            assertArrayEquals(byteArrayOf(1, 2, 3), File(java.net.URI(restoredVoice.url)).readBytes())
            assertEquals(before.compressionSummaries, restored.compressionSummaries)
            assertTrue(restored.messageNodes.last().isFavorite)
        }
    }

    @Test fun `provider mid read and close failures never archive partial bytes and valid provider is read only once`() = runBlocking {
        fixture { _, repository, _, _, backup ->
            val context = RuntimeEnvironment.getApplication()
            val resolver = org.robolectric.Shadows.shadowOf(context.contentResolver)
            val failedRead = android.net.Uri.parse("content://backup-test/read-failure.wav")
            val failedClose = android.net.Uri.parse("content://backup-test/close-failure.wav")
            val good = android.net.Uri.parse("content://backup-test/good.wav")
            resolver.registerInputStream(failedRead, object : java.io.InputStream() {
                var first = true
                override fun read(): Int = error("bulk read expected")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (!first) throw java.io.IOException("source disappeared")
                    first = false
                    bytes[offset] = 42
                    return 1
                }
            })
            resolver.registerInputStream(failedClose, object : java.io.ByteArrayInputStream(byteArrayOf(42)) {
                override fun close() { throw java.io.IOException("provider disconnected") }
            })
            val goodStream = byteArrayOf(3, 4, 5).inputStream()
            resolver.registerInputStream(good, goodStream)
            val original = conversation("provider").copy(messageNodes = listOf(UIMessage.user("media").copy(parts = listOf(
                UIMessagePart.Audio(failedRead.toString()), UIMessagePart.Video(failedClose.toString()),
                UIMessagePart.Audio(good.toString()), UIMessagePart.Document(good.toString(), "good.wav"),
            )).toMessageNode()))
            repository.insertConversation(original)
            val before = repository.exportChatHistory()
            val output = ByteArrayOutputStream()
            assertEquals(2, backup.export(output).unavailableAttachments)
            assertEquals(before, repository.exportChatHistory())
            val extraction = File(context.cacheDir, "provider-inspection")
            try {
                val archive = readChatArchive(output.toByteArray().inputStream(), extraction)
                assertEquals(1, archive.attachments.size)
                assertArrayEquals(byteArrayOf(3, 4, 5), File(extraction, archive.attachments.values.single()).readBytes())
                val parts = archive.conversations.single().messageNodes.single().currentMessage.parts
                assertTrue(parts[0] is UIMessagePart.Text)
                assertTrue(parts[1] is UIMessagePart.Text)
                assertEquals((parts[2] as UIMessagePart.Audio).url, (parts[3] as UIMessagePart.Document).url)
                assertEquals(0, goodStream.available())
            } finally { extraction.deleteRecursively() }
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("chat-export-") })
        }
    }

    @Test fun `destination write failure aborts export and removes attachment staging without changing history`() = runBlocking {
        fixture { _, repository, files, _, backup ->
            val context = RuntimeEnvironment.getApplication()
            val managed = files.saveManagedFromBytes(FileFolders.UPLOAD, byteArrayOf(1, 2, 3), "output_failure.wav")
            val voice = UIMessagePart.VoiceMessage(files.getFile(managed).toUri().toString(), 20, "transcript")
            val original = conversation("original").copy(messageNodes = listOf(
                UIMessage.user("voice").copy(parts = listOf(voice)).toMessageNode()))
            repository.insertConversation(original)
            val before = repository.exportChatHistory()
            val failure = runCatching { backup.export(object : java.io.OutputStream() {
                override fun write(value: Int) { throw java.io.IOException("destination full") }
            }) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(before, repository.exportChatHistory())
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("chat-export-") })
        }
    }

    @Test fun `large overwrite clears search once and never decodes overwritten old node JSON`() = runBlocking {
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        fixture(queries) { db, repository, _, _, _ ->
            val old = conversation("old")
            val unrelated = conversation("unrelated")
            repository.insertConversation(old)
            repository.insertConversation(unrelated)
            // Replacement must not depend on decoding the discarded local content.
            db.openHelper.writableDatabase.execSQL("UPDATE message_node SET messages = 'legacy-invalid-json' WHERE conversation_id = ?",
                arrayOf(old.id.toString()))
            val nodes = (0 until 600).map { index ->
                UIMessage.user("restored text $index").toMessageNode()
            }
            val incoming = old.copy(title = "restored", messageNodes = nodes)
            queries.clear()
            assertEquals(1, repository.restoreChatHistory(listOf(incoming), ChatHistoryRestoreMode.OVERWRITE_MATCHING))
            val restoreQueries = queries.toList()
            assertEquals(1, restoreQueries.count { it.startsWith("DELETE FROM message_fts WHERE rowid IN") })
            assertEquals(0, restoreQueries.count { it.startsWith("DELETE FROM message_fts WHERE rowid IN") && it.contains("AND node_id") })
            assertEquals(incoming, repository.exportChatHistory().single { it.id == old.id })
            assertEquals(unrelated, repository.getConversationById(unrelated.id))
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM message_fts WHERE conversation_id = '${old.id}'").use {
                assertTrue(it.moveToFirst()); assertEquals(600, it.getInt(0))
            }
        }
    }

    @Test fun `cancellation in second conversation rolls back first replacement and search index`() = runBlocking {
        fixture { db, repository, _, _, _ ->
            val first = conversation("first")
            val second = conversation("second")
            repository.insertConversation(first)
            repository.insertConversation(second)
            val failure = runCatching {
                repository.restoreChatHistory(listOf(first.copy(title = "changed"), second.copy(title = "changed2")),
                    ChatHistoryRestoreMode.OVERWRITE_MATCHING) { done, _ ->
                    if (done == 2) throw kotlinx.coroutines.CancellationException("cancel during restore")
                }
            }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.CancellationException)
            assertEquals(first, repository.getConversationById(first.id))
            assertEquals(second, repository.getConversationById(second.id))
            db.openHelper.writableDatabase.query("SELECT title FROM message_fts WHERE conversation_id = '${first.id}'").use {
                assertTrue(it.moveToFirst()); assertEquals("first", it.getString(0))
            }
        }
    }

    private suspend fun fixture(queries: MutableList<String>? = null, block: suspend (AppDatabase, ConversationRepository, FilesManager, SettingsStore, ChatHistoryBackup) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val keyProvider = TestAndroidKeyStore.TestProvider()
        Security.addProvider(keyProvider)
        File(context.noBackupFilesDir, "chat-history-restore").deleteRecursively()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> queries?.add(sql) }, java.util.concurrent.Executor { it.run() }).build()
        val scope = AppScope()
        val settings = SettingsStore(context, AppScope().also { it.cancel() }).also { it.settingsFlow.value = Settings() }
        try {
            db.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
            val files = FilesManager(context, FilesRepository(db.managedFileDao()), scope)
            val repository = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db, files, MessageFtsManager(db), settings)
            block(db, repository, files, settings, ChatHistoryBackup(context, repository, settings))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
            stopKoin()
            Security.removeProvider(keyProvider.name)
        }
    }
}
