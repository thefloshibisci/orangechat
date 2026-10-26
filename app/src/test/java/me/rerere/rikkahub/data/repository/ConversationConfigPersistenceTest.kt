package me.rerere.rikkahub.data.repository

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.MessageNode
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
class ConversationConfigPersistenceTest {
    @Test
    fun `insert and reload retain request controls and chat backup serialization`() = runBlocking {
        withRepository { _, repository ->
            val original = conversation().copy(config = config())
            repository.insertConversation(original)
            val reloaded = repository.getConversationById(original.id)!!
            assertEquals(original.config, reloaded.config)
            assertEquals(original.messageNodes, reloaded.messageNodes)
            val exported = repository.exportChatHistory()
            val decoded = JsonInstant.decodeFromString<List<Conversation>>(JsonInstant.encodeToString(exported))
            assertEquals(original.config, decoded.single().config)
            assertEquals(original.messageNodes, decoded.single().messageNodes)
        }
    }

    @Test
    fun `legacy null config stays unbound across message saves`() = runBlocking {
        withRepository { db, repository ->
            val original = conversation()
            repository.insertConversation(original)
            assertEquals("", db.conversationDao().getConfig(original.id.toString()))
            val saved = repository.updateConversation(original.copy(title = "new title"))
            assertNull(saved.config)
            assertNull(repository.getConversationById(original.id)!!.config)
            assertEquals("", db.conversationDao().getConfig(original.id.toString()))
        }
    }

    @Test
    fun `config update touches only its column and does not recreate deleted chats`() = runBlocking {
        withRepository { db, repository ->
            val original = conversation().copy(
                compressionSummaries = listOf(ConversationSummary(text = "keep summary", sendToModel = false)),
                compressedMessageNodeIds = emptySet(), compressionRevision = 7, sendCompressedSummary = false,
                chatSuggestions = listOf("keep suggestion"), isPinned = true,
            )
            repository.insertConversation(original)
            val oldEntity = db.conversationDao().getConversationById(original.id.toString())!!
            val oldNodes = db.messageNodeDao().getNodesOfConversation(original.id.toString())
            val selected = config()
            assertTrue(repository.updateConfig(original.id, selected))
            val newEntity = db.conversationDao().getConversationById(original.id.toString())!!
            assertEquals(oldEntity, newEntity.copy(config = oldEntity.config))
            assertEquals(oldNodes, db.messageNodeDao().getNodesOfConversation(original.id.toString()))
            assertEquals(selected, repository.getConversationById(original.id)!!.config)
            assertTrue(repository.updateConfig(original.id, null))
            assertEquals(oldEntity, db.conversationDao().getConversationById(original.id.toString()))
            assertEquals(oldNodes, db.messageNodeDao().getNodesOfConversation(original.id.toString()))
            assertNull(repository.getConversationById(original.id)!!.config)
            db.conversationDao().deleteById(original.id.toString())
            assertFalse(repository.updateConfig(original.id, selected))
            assertFalse(repository.updateConfig(original.id, null))
            assertFalse(db.conversationDao().existsById(original.id.toString()))
        }
    }

    @Test
    fun `stale full and optimized message saves retain latest config including unknown fields`() = runBlocking {
        withRepository { db, repository ->
            for (optimized in listOf(false, true)) {
                val original = conversation().copy(config = config())
                repository.insertConversation(original)
                val selected = config().copy(maxTokens = 321, enableWebSearch = false)
                val raw = " {\"future_request_option\":{\"keep\":[1,2]}," +
                    JsonInstant.encodeToString(selected).drop(1)
                db.conversationDao().updateConfig(original.id.toString(), raw)
                val replacement = original.messageNodes.single().copy(messages = listOf(UIMessage.assistant("edited")))
                val after = original.copy(title = "saved title", messageNodes = listOf(replacement))
                val saved = repository.updateConversation(after, original.takeIf { optimized })
                assertEquals(selected, saved.config)
                assertEquals(raw, db.conversationDao().getConfig(original.id.toString()))
                assertEquals(selected, repository.getConversationById(original.id)!!.config)
                assertEquals(after.messageNodes, repository.getConversationById(original.id)!!.messageNodes)
            }
        }
    }

    @Test
    fun `stale delta checkpoint retains latest raw config and returns current controls`() = runBlocking {
        withRepository { db, repository ->
            val original = conversation().copy(config = config())
            repository.insertConversation(original)
            val selected = config().copy(maxTokens = 456)
            val raw = "{\"unknown_field\":true," + JsonInstant.encodeToString(selected).drop(1)
            db.conversationDao().updateConfig(original.id.toString(), raw)
            val appended = MessageNode.of(UIMessage.assistant("appended"))
            val after = original.copy(messageNodes = original.messageNodes + appended)
            val delta = ConversationNodeDelta(original, after,
                ConversationNodeChanges(listOf(1 to appended), emptySet()))
            val saved = repository.updateConversationWithDelta(after, original, delta)
            assertEquals(selected, saved.config)
            assertEquals(raw, db.conversationDao().getConfig(original.id.toString()))
            assertEquals(after.messageNodes, repository.getConversationById(original.id)!!.messageNodes)
        }
    }

    @Test
    fun `explicit overwrite restores incoming config while incremental restore keeps current`() = runBlocking {
        withRepository { _, repository ->
            val original = conversation().copy(config = config())
            repository.insertConversation(original)
            val imported = original.copy(config = config().copy(maxTokens = 999))
            assertEquals(0, repository.restoreChatHistory(listOf(imported), ChatHistoryRestoreMode.INCREMENTAL))
            assertEquals(original.config, repository.getConversationById(original.id)!!.config)
            assertEquals(1, repository.restoreChatHistory(listOf(imported), ChatHistoryRestoreMode.OVERWRITE_MATCHING))
            assertEquals(imported.config, repository.getConversationById(original.id)!!.config)
            // An old logical backup has no config and must restore its inherited-default semantics.
            assertEquals(1, repository.restoreChatHistory(listOf(original.copy(config = null)), ChatHistoryRestoreMode.OVERWRITE_MATCHING))
            assertNull(repository.getConversationById(original.id)!!.config)
        }
    }

    @Test
    fun `a late ordinary save cannot revive a deleted conversation`() = runBlocking {
        withRepository { db, repository ->
            val original = conversation()
            repository.insertConversation(original)
            db.conversationDao().deleteById(original.id.toString())
            val failure = runCatching { repository.updateConversation(original) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(0, db.messageNodeDao().getNodeCountOfConversation(original.id.toString()))
        }
    }

    private fun config() = ConversationConfig(
        chatModelId = Uuid.random(), enableWebSearch = true, builtInSearch = true,
        temperature = 0.5f, topP = 0.8f, maxTokens = 1024, contextMessageSize = 20,
        reasoningLevel = ReasoningLevel.entries.last(), searchServiceSelected = 2,
        maxContextImages = 4, maxContextVoices = 3, streamOutput = false,
        customHeaders = listOf(CustomHeader("X-Test", "preserve-header")),
        customBodies = listOf(CustomBody("request_test", JsonPrimitive("保留请求参数"))),
        mcpServers = setOf(Uuid.random()), enabledSkills = setOf("skill-one", "技能二"),
        workspaceId = Uuid.random(), modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()),
    )

    private fun conversation() = Conversation(
        assistantId = Uuid.random(), title = "keep title",
        messageNodes = listOf(MessageNode.of(UIMessage.assistant("keep message"))),
    )

    private suspend fun withRepository(block: suspend (AppDatabase, ConversationRepository) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = AppScope()
        try {
            db.openHelper.writableDatabase.execSQL("""
                CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT,
                    conversation_id TEXT, title TEXT, update_at TEXT)
            """.trimIndent())
            val repository = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
                FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db))
            block(db, repository)
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
