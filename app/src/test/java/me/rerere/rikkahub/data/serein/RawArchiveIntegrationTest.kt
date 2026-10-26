package me.rerere.rikkahub.data.serein

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.transformers.extraInfoMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationNodeDelta
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.conversationNodeChanges
import me.rerere.rikkahub.data.sync.ChatHistoryRestoreMode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class RawArchiveIntegrationTest {
    private class Fixture(val db: AppDatabase, val repo: ConversationRepository, val settings: SettingsStore) {
        val assistant = Assistant(saveLocalRawMessages = true)
        val other = Assistant(saveLocalRawMessages = true)
        fun configure(enabled: Boolean = true) {
            settings.settingsFlow.value = Settings(assistants = listOf(assistant.copy(saveLocalRawMessages = enabled), other))
        }
        fun chat(vararg messages: UIMessage, owner: Uuid = assistant.id) =
            Conversation(assistantId = owner, messageNodes = messages.map { it.toMessageNode() })
        suspend fun count(table: String): Int = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst(); it.getInt(0)
        }
    }

    @Test fun `opt in backfill is repeatable and does not change stored chat`() = runBlocking {
        fixture {
            configure(false)
            val chat = chat(UIMessage.user("历史原话"))
            repo.insertConversation(chat)
            assertEquals(0, count("serein_raw_revision"))
            configure()
            assertEquals(1, repo.backfillRawOriginals(assistant.id))
            val raw = repo.searchRawOriginals(assistant.id).single()
            assertEquals("backfill", raw.origin)
            assertEquals("历史原话", raw.text)
            assertEquals(1, repo.backfillRawOriginals(assistant.id))
            assertEquals(raw.id, repo.searchRawOriginals(assistant.id).single().id)
            assertEquals(1, count("serein_raw_revision"))
            assertEquals(chat.currentMessages, repo.getConversationById(chat.id)!!.currentMessages)
            val encoded = JsonInstant.encodeToString(assistant)
            assertTrue(JsonInstant.decodeFromString<Assistant>(encoded).saveLocalRawMessages)
            assertFalse(JsonInstant.decodeFromString<Assistant>("{}").saveLocalRawMessages)
        }
    }

    @Test fun `same id edits preserve original revision and full snapshot updates pointers`() = runBlocking {
        fixture {
            val original = UIMessage.user("第一版原文")
            val chat = chat(original)
            repo.insertConversation(chat)
            val id = repo.searchRawOriginals(assistant.id).single().id
            val edited = chat.copy(messageNodes = listOf(chat.messageNodes.single().copy(messages = listOf(original.copy(parts = listOf(UIMessagePart.Text("第二版原文")))))))
            repo.updateConversation(edited)
            val current = repo.searchRawOriginals(assistant.id).single()
            assertNotEquals(id, current.id)
            assertEquals("第二版原文", current.text)
            assertEquals(listOf("第二版原文", "第一版原文"), repo.rawOriginalHistory(assistant.id, chat.id.toString(), original.id.toString()).map { it.text })
            repo.backfillRawOriginals(assistant.id)
            assertEquals(2, count("serein_raw_revision"))
        }
    }

    @Test fun `branch selection and cloned message ids are isolated by conversation and assistant`() = runBlocking {
        fixture {
            val a = UIMessage.user("分支甲")
            val b = UIMessage.user("分支乙")
            val chat = chat(a).copy(messageNodes = listOf(MessageNode(messages = listOf(a, b))))
            repo.insertConversation(chat)
            val clone = chat(a, owner = other.id)
            repo.insertConversation(clone)
            assertEquals(listOf("分支甲"), repo.searchRawOriginals(assistant.id).map { it.text })
            val switched = chat.copy(messageNodes = listOf(chat.messageNodes.single().copy(selectIndex = 1)))
            repo.updateConversation(switched, chat)
            assertEquals(listOf("分支乙"), repo.searchRawOriginals(assistant.id).map { it.text })
            assertEquals(listOf("分支甲"), repo.searchRawOriginals(other.id).map { it.text })
            val raw = repo.searchRawOriginals(other.id).single()
            assertNull(db.rawArchiveDao().revision(assistant.id.toString(), clone.id.toString(), raw.id))
            assertTrue(repo.rawOriginalHistory(assistant.id, clone.id.toString(), a.id.toString()).isEmpty())
            assertEquals(1, repo.rawOriginalHistory(assistant.id, chat.id.toString(), a.id.toString()).size)
            assertEquals(3, count("serein_raw_revision"))
        }
    }

    @Test fun `stream checkpoints update draft in place and final edit keeps completed text`() = runBlocking {
        fixture {
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("回")))
            var chat = chat(UIMessage.user("继续"), message)
            repo.insertConversation(chat)
            val id = repo.searchRawOriginals(assistant.id, "回").single().id
            repeat(8) { index ->
                val node = chat.messageNodes.last().copy(messages = listOf(message.copy(parts = listOf(UIMessagePart.Text("回复$index")))))
                val next = chat.copy(messageNodes = chat.messageNodes.dropLast(1) + node)
                repo.updateConversationWithDelta(next, chat, ConversationNodeDelta(chat, next, conversationNodeChanges(chat, next)))
                chat = next
            }
            assertEquals(2, count("serein_raw_revision"))
            assertEquals(id, repo.searchRawOriginals(assistant.id, "回复").single().id)
            assertEquals("pending", repo.searchRawOriginals(assistant.id, "回复").single().state)
            val finished = chat.copy(messageNodes = chat.messageNodes.dropLast(1) + chat.messageNodes.last().copy(messages = listOf(
                message.copy(parts = listOf(UIMessagePart.Text("回复完成")), finishedAt = LocalDateTime(2026, 10, 23, 12, 0)),
            )))
            repo.updateConversation(finished, chat)
            assertEquals(id, repo.searchRawOriginals(assistant.id, "回复").single().id)
            assertEquals("complete", repo.searchRawOriginals(assistant.id, "回复").single().state)
            val edit = finished.copy(messageNodes = finished.messageNodes.dropLast(1) + finished.messageNodes.last().copy(messages = listOf(
                finished.currentMessages.last().copy(parts = listOf(UIMessagePart.Text("修改后的回复"))),
            )))
            repo.updateConversation(edit, finished)
            assertEquals(listOf("修改后的回复", "回复完成"), repo.rawOriginalHistory(assistant.id, chat.id.toString(), message.id.toString()).map { it.text })
        }
    }

    @Test fun `failed turn keeps user original and interruption freezes only owned reply`() = runBlocking {
        fixture {
            val user = UIMessage.user("即使失败也要保存")
            val first = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("部分回复一")))
            val second = first.copy(id = Uuid.random(), parts = listOf(UIMessagePart.Text("部分回复二")))
            val chat = chat(user, first, second)
            repo.insertConversation(chat)
            repo.interruptPendingRawOriginals(chat.id, setOf(first.id.toString()))
            assertEquals("complete", repo.searchRawOriginals(assistant.id, "即使").single().state)
            assertEquals("interrupted", repo.searchRawOriginals(assistant.id, "回复一").single().state)
            assertEquals("pending", repo.searchRawOriginals(assistant.id, "回复二").single().state)
            val changed = chat.copy(messageNodes = chat.messageNodes.map { node ->
                if (node.currentMessage.id == first.id) node.copy(messages = listOf(first.copy(parts = listOf(UIMessagePart.Text("重新作答"))))) else node
            })
            repo.updateConversation(changed, chat)
            assertEquals(listOf("重新作答", "部分回复一"), repo.rawOriginalHistory(assistant.id, chat.id.toString(), first.id.toString()).map { it.text })
        }
    }

    @Test fun `only authored text is archived and literal tags and wildcard characters survive`() = runBlocking {
        fixture {
            val literal = "  <serein_live_context>100%_原话</serein_live_context>  "
            val message = UIMessage.user(literal).copy(parts = listOf(
                UIMessagePart.Text(literal), extraInfoMessagePart("不许入库的屏幕内容"),
                UIMessagePart.Text("不许入库的记忆", metadata = JsonObject(mapOf(SEREIN_RECALL_METADATA_KEY to JsonPrimitive(true)))),
            ))
            val synthetic = UIMessage.user("伪造用户轮").copy(parts = listOf(UIMessagePart.Text("伪造用户轮", metadata = JsonObject(mapOf(SYNTHETIC_METADATA_KEY to JsonPrimitive(true))))))
            val malformed = UIMessage.user("元数据不影响原话").copy(parts = listOf(UIMessagePart.Text("元数据不影响原话", metadata = JsonObject(mapOf(SEREIN_RECALL_METADATA_KEY to JsonObject(emptyMap()))))))
            val chat = chat(message, synthetic, malformed, UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("系统提示"))))
            repo.insertConversation(chat)
            assertEquals(2, count("serein_raw_revision"))
            assertEquals(literal, repo.searchRawOriginals(assistant.id, "100%_").single().text)
            assertTrue(repo.searchRawOriginals(assistant.id, "不许").isEmpty())
            assertTrue(repo.searchRawOriginals(assistant.id, "伪造").isEmpty())
            assertEquals(chat.currentMessages, repo.getConversationById(chat.id)!!.currentMessages)
        }
    }

    @Test fun `empty edited source and removed node stop appearing without erasing revisions`() = runBlocking {
        fixture {
            val original = UIMessage.user("不该再浮现")
            val chat = chat(original)
            repo.insertConversation(chat)
            val empty = chat.copy(messageNodes = listOf(chat.messageNodes.single().copy(messages = listOf(original.copy(parts = emptyList())))))
            repo.updateConversation(empty, chat)
            assertTrue(repo.searchRawOriginals(assistant.id).isEmpty())
            assertEquals(1, count("serein_raw_revision"))
            repo.updateConversation(chat, empty)
            assertEquals(1, count("serein_raw_revision"))
            repo.updateConversation(chat.copy(messageNodes = emptyList()), chat)
            assertTrue(repo.searchRawOriginals(assistant.id).isEmpty())
            assertTrue(repo.rawOriginalHistory(assistant.id, chat.id.toString(), original.id.toString()).isEmpty())
            assertEquals(1, count("serein_raw_revision"))
            repo.deleteConversation(chat)
            assertEquals(0, count("serein_raw_revision"))
            assertEquals(0, count("serein_raw_head"))
        }
    }

    @Test fun `editing while disabled cannot expose stale originals and reimport repairs pointers`() = runBlocking {
        fixture {
            val chat = chat(UIMessage.user("旧的记录"))
            repo.insertConversation(chat)
            configure(false)
            val next = chat.copy(messageNodes = listOf(chat.messageNodes.single().copy(messages = listOf(chat.currentMessages.single().copy(parts = listOf(UIMessagePart.Text("新的记录")))))))
            repo.updateConversation(next, chat)
            assertTrue(repo.searchRawOriginals(assistant.id).isEmpty())
            assertEquals(1, count("serein_raw_revision"))
            configure()
            repo.backfillRawOriginals(assistant.id)
            assertEquals("新的记录", repo.searchRawOriginals(assistant.id).single().text)
            assertEquals(2, count("serein_raw_revision"))
        }
    }

    @Test fun `conversation ownership change revokes old assistant access`() = runBlocking {
        fixture {
            val chat = chat(UIMessage.user("明确移交的聊天"))
            repo.insertConversation(chat)
            repo.updateConversation(chat.copy(assistantId = other.id), chat)
            assertTrue(repo.searchRawOriginals(assistant.id).isEmpty())
            assertTrue(repo.rawOriginalHistory(assistant.id, chat.id.toString(), chat.currentMessages.single().id.toString()).isEmpty())
            assertEquals("明确移交的聊天", repo.searchRawOriginals(other.id).single().text)
            assertEquals(1, count("serein_raw_revision"))
        }
    }

    @Test fun `chat restore replaces pointers and backfills originals transactionally`() = runBlocking {
        fixture {
            val chat = chat(UIMessage.user("恢复前"))
            repo.insertConversation(chat)
            val replaced = chat.copy(messageNodes = listOf(chat.messageNodes.single().copy(messages = listOf(chat.currentMessages.single().copy(parts = listOf(UIMessagePart.Text("恢复后")))))))
            repo.restoreChatHistory(listOf(replaced), ChatHistoryRestoreMode.OVERWRITE_MATCHING)
            assertEquals("恢复后", repo.searchRawOriginals(assistant.id).single().text)
            assertEquals("backfill", repo.searchRawOriginals(assistant.id).single().origin)
            assertEquals(2, count("serein_raw_revision"))
            repo.restoreChatHistory(listOf(replaced), ChatHistoryRestoreMode.OVERWRITE_MATCHING)
            assertEquals(2, count("serein_raw_revision"))
        }
    }

    @Test fun `archive failure rolls back chat and source together instead of publishing a half update`() = runBlocking {
        fixture {
            val chat = chat(UIMessage.user("旧的完整状态"))
            repo.insertConversation(chat)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_raw_revision BEFORE INSERT ON serein_raw_revision BEGIN SELECT RAISE(ABORT, 'synthetic archive fault'); END")
            val changed = chat.copy(messageNodes = listOf(chat.messageNodes.single().copy(messages = listOf(chat.currentMessages.single().copy(parts = listOf(UIMessagePart.Text("不能部分提交")))))))
            assertTrue(runCatching { repo.updateConversation(changed, chat) }.isFailure)
            assertEquals("旧的完整状态", repo.getConversationById(chat.id)!!.currentMessages.single().toText())
            assertEquals("旧的完整状态", repo.searchRawOriginals(assistant.id).single().text)
            assertEquals(1, count("serein_raw_revision"))
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = AppScope()
        val settingsScope = AppScope().also { it.cancel() }
        val settings = SettingsStore(context, settingsScope)
        try {
            db.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
            val files = FilesManager(context, FilesRepository(db.managedFileDao()), scope)
            val repo = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db, files, MessageFtsManager(db), settings)
            Fixture(db, repo, settings).apply { configure() }.block()
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
        }
    }

    @Test fun `undo restores raw revision history while expired deletion releases it`() = runBlocking {
        fixture {
            val chat = chat(UIMessage.user("修改前"))
            repo.insertConversation(chat)
            val edited = chat.copy(messageNodes = listOf(chat.messageNodes.single().copy(messages = listOf(chat.currentMessages.single().copy(parts = listOf(UIMessagePart.Text("修改后")))))))
            repo.updateConversation(edited, chat)
            val originalIds = repo.rawOriginalHistory(assistant.id, chat.id.toString(), chat.currentMessages.single().id.toString()).map { it.id }
            configure(false)
            val removed = repo.deleteConversation(edited, retainAttachments = true)
            assertEquals(0, count("serein_raw_revision"))
            repo.insertConversation(removed)
            assertEquals(originalIds, repo.rawOriginalHistory(assistant.id, chat.id.toString(), chat.currentMessages.single().id.toString()).map { it.id })
            assertEquals("修改后", repo.searchRawOriginals(assistant.id).single().text)
            val removedAgain = repo.deleteConversation(edited, retainAttachments = true)
            val changedDuringUndo = removedAgain.copy(messageNodes = listOf(removedAgain.messageNodes.single().copy(
                messages = listOf(removedAgain.currentMessages.single().copy(parts = listOf(UIMessagePart.Text("撤销时已变更")))),
            )))
            repo.insertConversation(changedDuringUndo)
            assertTrue(repo.searchRawOriginals(assistant.id).isEmpty())
            assertEquals(2, count("serein_raw_revision"))
            repo.deleteConversation(edited, retainAttachments = true)
            repo.releaseDeletedConversationAttachments(removed)
            assertEquals(0, count("serein_raw_revision"))
            assertTrue(repo.searchRawOriginals(assistant.id).isEmpty())
        }
    }
}
