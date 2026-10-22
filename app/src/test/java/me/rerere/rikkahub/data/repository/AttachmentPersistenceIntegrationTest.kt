package me.rerere.rikkahub.data.repository

import android.app.Application
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.core.net.toUri
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.executionCheckpointState
import me.rerere.rikkahub.data.ai.withExecutionCheckpoint
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.attachmentUrls
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.ConversationMessageJournal
import me.rerere.rikkahub.service.generationMessageMutation
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.testing.HostFileUriShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class, shadows = [HostFileUriShadow::class])
class AttachmentPersistenceIntegrationTest {
    @Test fun `slow attachment validation leaves the chat database writer available`() = runBlocking {
        fixture { db, repository, files, _ ->
            val original = conversation(UIMessage.user("keep text").copy(parts = listOf(
                UIMessagePart.Text("keep text"), UIMessagePart.Image("file:///missing-${Uuid.random()}.png"),
            )))
            repository.insertConversation(original)
            val scanStarted = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()
            coroutineScope {
                val cleanup = async(Dispatchers.IO) {
                    repository.repairInvalidAttachments { messages ->
                        scanStarted.complete(Unit)
                        finishScan.await()
                        files.validAttachments(messages)
                    }
                }
                try {
                    withTimeout(5_000) { scanStarted.await() }
                    val otherChat = conversation(UIMessage.user("new chat remains writable"))
                    withTimeout(5_000) { repository.insertConversation(otherChat) }
                    assertEquals("new chat remains writable", stored(db, otherChat).single().toText())
                    assertFalse("The attachment scan is still paused", cleanup.isCompleted)
                } finally {
                    finishScan.complete(Unit)
                }
                assertEquals(1, withTimeout(5_000) { cleanup.await() })
            }
            assertEquals("keep text", stored(db, original).single().toText())
            assertTrue(attachmentUrls(stored(db, original)).isEmpty())
        }
    }

    @Test fun `attachment validation never overwrites a checkpoint saved during its scan`() = runBlocking {
        fixture(forceSlices = true) { db, repository, files, _ ->
            val missing = UIMessagePart.Image("file:///missing-${Uuid.random()}.png")
            val oldMessage = UIMessage.assistant("old").copy(parts = listOf(UIMessagePart.Text("old"), missing))
            val original = conversation(oldMessage)
            repository.insertConversation(original)
            val scanStarted = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()
            val freshMessage = oldMessage.copy(parts = listOf(UIMessagePart.Text("new streamed checkpoint"), missing))
            coroutineScope {
                val cleanup = async(Dispatchers.IO) {
                    repository.repairInvalidAttachments { messages ->
                        scanStarted.complete(Unit)
                        finishScan.await()
                        files.validAttachments(messages)
                    }
                }
                try {
                    withTimeout(5_000) { scanStarted.await() }
                    val node = db.messageNodeDao().getNodesByIds(listOf(original.messageNodes.single().id.toString())).single()
                    withTimeout(5_000) {
                        db.messageNodeDao().update(node.copy(messages = JsonInstant.encodeToString(listOf(freshMessage))))
                    }
                } finally {
                    finishScan.complete(Unit)
                }
                assertEquals("The stale scan must lose its compare-and-set", 0, withTimeout(5_000) { cleanup.await() })
            }
            assertEquals(listOf(freshMessage), stored(db, original))
            assertEquals(1, repository.repairInvalidAttachments())
            assertEquals("new streamed checkpoint", stored(db, original).single().toText())
            assertTrue(attachmentUrls(stored(db, original)).isEmpty())
        }
    }

    @Test fun `manual deletion repairs only selected references without scanning unrelated corrupt images`() = runBlocking {
        fixture(forceSlices = true) { db, repository, files, _ ->
            val selected = files.saveManagedFromBytes(FileFolders.UPLOAD, "data".toByteArray(), "delete.txt", "text/plain")
            val document = UIMessagePart.Document(files.getFile(selected).toUri().toString(), "delete.txt")
            val affected = conversation(UIMessage.user("keep my words").copy(parts = listOf(
                UIMessagePart.Text("keep my words"), document)))
            val unrelated = conversation(UIMessage.user("unrelated").copy(parts = listOf(
                UIMessagePart.Text("unrelated"), UIMessagePart.Image("file:///missing/other-image.png"))))
            repository.insertConversation(affected)
            repository.insertConversation(unrelated)
            assertTrue(files.delete(selected.id))
            assertEquals(1, repository.repairDeletedAttachmentReferences(setOf(files.getFile(selected).name)))
            assertEquals("keep my words", stored(db, affected).single().toText())
            assertTrue(attachmentUrls(stored(db, affected)).isEmpty())
            assertEquals(setOf("file:///missing/other-image.png"), attachmentUrls(stored(db, unrelated)))
            assertEquals(0, repository.repairDeletedAttachmentReferences(setOf(files.getFile(selected).name)))
        }
    }

    @Test fun `a deleted attachment cannot return through a delta and its canonical baseline remains usable`() = runBlocking {
        fixture { db, repository, files, _ ->
            val file = files.saveManagedFromBytes(FileFolders.UPLOAD, "data".toByteArray(), "note.txt", "text/plain")
            val document = UIMessagePart.Document(files.getFile(file).toUri().toString(), "note.txt")
            val original = UIMessage.assistant("old").copy(parts = listOf(UIMessagePart.Text("old"), document))
            val before = conversation(original)
            repository.insertConversation(before)
            assertTrue(files.delete(file.id))
            val mutation = before.generationMessageMutation(listOf(original.copy(parts = listOf(UIMessagePart.Text("stream"), document))))
            val journal = ConversationMessageJournal()
            journal.record(mutation, before)
            val saved = repository.updateConversationWithDelta(mutation.conversation, before,
                checkNotNull(journal.deltaFor(before, mutation.conversation)))
            assertTrue(attachmentUrls(saved.currentMessages).isEmpty())
            assertEquals("stream", saved.currentMessages.single().toText())
            assertTrue(attachmentUrls(stored(db, before)).isEmpty())
            journal.committed()

            val next = saved.generationMessageMutation(listOf(saved.currentMessages.single().copy(
                parts = listOf(UIMessagePart.Text("finished")),
            )))
            journal.record(next, saved)
            val nextDelta = checkNotNull(journal.deltaFor(saved, next.conversation))
            assertTrue(nextDelta.appliesTo(saved, next.conversation))
            val final = repository.updateConversationWithDelta(next.conversation, saved, nextDelta)
            assertEquals("finished", stored(db, final).single().toText())
            assertTrue(attachmentUrls(stored(db, final)).isEmpty())
        }
    }

    @Test fun `unchanged filtering preserves history identity after unrelated file deletion`() = runBlocking {
        fixture { _, repository, files, _ ->
            val current = conversation(UIMessage.user("text"))
            assertSame(current, repository.withoutDeletedAttachments(current))
            val file = files.saveManagedFromBytes(FileFolders.UPLOAD, "keep".toByteArray(), "keep.txt", "text/plain")
            files.deleteChatFiles(listOf(files.getFile(file).toUri()))
            assertSame(current, repository.withoutDeletedAttachments(current))
            assertSame(current.messageNodes, repository.withoutDeletedAttachments(current).messageNodes)
        }
    }

    @Test fun `garbage collection retains another conversation alternative and settings until the last reference is removed`() = runBlocking {
        fixture { _, repository, files, settings ->
            val file = files.saveManagedFromBytes(FileFolders.UPLOAD, "shared".toByteArray(), "shared.txt", "text/plain")
            val disk = files.getFile(file)
            val document = UIMessagePart.Document(disk.toUri().toString(), "shared.txt")
            val first = conversation(UIMessage.user("first").copy(parts = listOf(document)))
            val secondMessage = UIMessage.assistant("second").copy(parts = listOf(document))
            val branch = secondMessage.toMessageNode().copy(messages = listOf(UIMessage.assistant("selected"), secondMessage))
            val second = Conversation(assistantId = Uuid.random(), messageNodes = listOf(branch))
            repository.insertConversation(first)
            repository.insertConversation(second)
            repository.updateConversation(first.copy(messageNodes = emptyList()), first)
            assertTrue("An unselected branch still references the file", disk.exists())

            settings.settingsFlow.value = Settings(displaySetting = DisplaySetting(userAvatar = Avatar.Image(disk.toUri().toString())))
            val noFile = second.copy(messageNodes = listOf(branch.copy(messages = listOf(branch.messages.first()))))
            repository.updateConversation(noFile, second)
            assertTrue("The avatar still references the file", disk.exists())

            settings.settingsFlow.value = Settings()
            // Reattach and remove once more, producing a new durable lifecycle removal.
            repository.updateConversation(second, noFile)
            repository.updateConversation(noFile, second)
            assertFalse("No message branch or settings reference remains", disk.exists())
        }
    }

    @Test fun `failed checkpoint never collects its previous attachment`() = runBlocking {
        fixture { db, repository, files, _ ->
            val file = files.saveManagedFromBytes(FileFolders.UPLOAD, "keep".toByteArray(), "keep.txt", "text/plain")
            val disk = files.getFile(file)
            val original = UIMessage.assistant("old").copy(parts = listOf(UIMessagePart.Document(disk.toUri().toString(), "keep.txt")))
            val before = conversation(original)
            repository.insertConversation(before)
            val mutation = before.generationMessageMutation(listOf(original.copy(parts = listOf(UIMessagePart.Text("new")))))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_attachment_checkpoint BEFORE INSERT ON message_node BEGIN SELECT RAISE(ABORT, 'reject'); END")
            try {
                repository.updateConversationWithDelta(mutation.conversation, before,
                    ConversationNodeDelta(before, mutation.conversation, mutation.changes))
                fail("Expected transaction failure")
            } catch (_: android.database.SQLException) {
                assertTrue(disk.exists())
                assertEquals(setOf(disk.toUri().toString()), attachmentUrls(stored(db, before)))
            }
        }
    }

    @Test fun `attachment repair reads complete sliced history and retains all branches and tool evidence`() = runBlocking {
        fixture(forceSlices = true) { db, repository, _, _ ->
            val missing = "file:///missing-${Uuid.random()}.txt"
            val tool = UIMessagePart.Tool("call", "tool", "{}", listOf(UIMessagePart.Document(missing, "missing.txt")))
                .withExecutionCheckpoint("completed")
            val longText = "x".repeat(150_000) + "🌿"
            val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(longText), tool))
            val alternate = UIMessage.user("alternate").copy(parts = listOf(UIMessagePart.Text("alternate"), UIMessagePart.Document(missing, "missing.txt")))
            val branch = original.toMessageNode().copy(messages = listOf(original, alternate), selectIndex = 1)
            val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(branch))
            repository.insertConversation(before)

            assertEquals(1, repository.repairInvalidAttachments())

            val repaired = stored(db, before)
            assertEquals(listOf(original.id, alternate.id), repaired.map { it.id })
            assertEquals(longText, repaired.first().parts.filterIsInstance<UIMessagePart.Text>().single().text)
            val repairedTool = repaired.first().getTools().single()
            assertTrue(repairedTool.isExecuted)
            assertEquals("completed", repairedTool.executionCheckpointState)
            assertTrue(attachmentUrls(repaired).isEmpty())
            assertEquals(1, db.messageNodeDao().getNodeHeaders(before.id.toString(), 1, 0).single().selectIndex)
        }
    }

    @Test fun `undo preserves attachment bytes and restores references before collection`() = runBlocking {
        fixture { _, repository, files, _ ->
            val file = files.saveManagedFromBytes(FileFolders.UPLOAD, "keep".toByteArray(), "undo.txt", "text/plain")
            val disk = files.getFile(file)
            val original = conversation(UIMessage.user("keep text").copy(parts = listOf(
                UIMessagePart.Text("keep text"), UIMessagePart.Document(disk.toUri().toString(), "undo.txt"),
            )))
            repository.insertConversation(original)
            val removed = repository.deleteConversation(original, retainAttachments = true)
            assertFalse(repository.existsConversationById(original.id))
            assertTrue(disk.exists())
            val restored = repository.insertConversation(removed)
            repository.releaseDeletedConversationAttachments(removed)
            assertTrue(disk.exists())
            assertEquals(original.currentMessages, restored.currentMessages)
            assertEquals(setOf(disk.toUri().toString()), attachmentUrls(restored.currentMessages))
        }
    }

    @Test fun `another deletion cannot collect files held by an open undo window`() = runBlocking {
        fixture { _, repository, files, _ ->
            val file = files.saveManagedFromBytes(FileFolders.UPLOAD, "shared".toByteArray(), "undo-shared.txt", "text/plain")
            val disk = files.getFile(file)
            val message = UIMessage.user("keep").copy(parts = listOf(UIMessagePart.Document(disk.toUri().toString(), "file.txt")))
            val first = conversation(message)
            val second = conversation(message)
            repository.insertConversation(first)
            repository.insertConversation(second)
            val removed = repository.deleteConversation(first, retainAttachments = true)
            repository.deleteConversation(second)
            assertTrue("Pending Undo still owns the attachment", disk.exists())
            repository.releaseDeletedConversationAttachments(removed)
            assertFalse("Expired Undo releases the last owner", disk.exists())
        }
    }

    private fun conversation(message: UIMessage) = Conversation(assistantId = Uuid.random(), messageNodes = listOf(message.toMessageNode()))

    private suspend fun stored(db: AppDatabase, conversation: Conversation): List<UIMessage> =
        db.messageNodeDao().loadCompleteNodes(conversation.id.toString()).flatMap {
            JsonInstant.decodeFromString<List<UIMessage>>(it.messages)
        }

    private suspend fun fixture(
        forceSlices: Boolean = false,
        block: suspend (AppDatabase, ConversationRepository, FilesManager, SettingsStore) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = AppScope()
        val settingsScope = AppScope().also { it.cancel() }
        val settings = SettingsStore(context, settingsScope).also { it.settingsFlow.value = Settings() }
        try {
            db.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
            val dao = db.messageNodeDao()
            val reader = if (!forceSlices) dao else object : MessageNodeDAO by dao {
                override suspend fun getNodesOfConversationPaged(conversationId: String, limit: Int, offset: Int): List<MessageNodeEntity> =
                    throw SQLiteBlobTooBigException("Exercise complete slice recovery")
                override suspend fun getNodesByIds(ids: List<String>): List<MessageNodeEntity> =
                    throw SQLiteBlobTooBigException("Exercise complete single-node slice recovery")
            }
            val files = FilesManager(context, FilesRepository(db.managedFileDao()), scope)
            val repository = ConversationRepository(db.conversationDao(), reader, db.favoriteDao(), db, files, MessageFtsManager(db), settings)
            block(db, repository, files, settings)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
        }
    }
}
