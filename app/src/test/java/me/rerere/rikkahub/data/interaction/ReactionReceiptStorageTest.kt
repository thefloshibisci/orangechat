package me.rerere.rikkahub.data.interaction

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
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
class ReactionReceiptStorageTest {
    private val assistant = Assistant(enableMessageReactions = true)
    private val target = UIMessage.assistant("喜欢牛奶").copy(
        createdAt = LocalDateTime(2026, 10, 24, 12, 0), finishedAt = LocalDateTime(2026, 10, 24, 12, 1),
    )

    private fun conversation() = Conversation(
        assistantId = assistant.id, title = "receipt test",
        messageNodes = listOf(UIMessage.user("你好"), target, UIMessage.user("继续")).map { MessageNode.of(it) },
    )

    private fun reply() = UIMessage.assistant("收到啦").copy(
        createdAt = LocalDateTime(2026, 10, 26, 12, 0), finishedAt = LocalDateTime(2026, 10, 26, 12, 1),
    )

    @Test fun `request and failed partial persistence do not consume or remove visible database reactions`() = runBlocking {
        withRepository { db, repository, interactions ->
            val original = conversation()
            repository.insertConversation(original)
            interactions.react(original.id, assistant, target.id.toString(), "user", "ᵔ ᵕ ᵔ", add = true)
            val events = db.messageReactionDao().list(original.id.toString())
            val generation = interactions.prepareContext(original.currentMessages, original.id.toString(), assistant)
            interactions.decorate(original.currentMessages, original.id.toString(), assistant, turn = generation)
            // A prior completed tool turn can leave finishedAt set on a later failed text continuation.
            val partial = generation.markPending(listOf(reply())).single()
            repository.updateConversation(original.copy(messageNodes = original.messageNodes + MessageNode.of(partial)))
            val loaded = repository.getConversationById(original.id)!!
            assertEquals(events, db.messageReactionDao().list(original.id.toString()))
            assertEquals(events, interactions.prepareContext(loaded.currentMessages, original.id.toString(), assistant).events)
            assertNotNull(loaded.currentMessages.last().finishedAt)
            assertTrue(loaded.currentMessages.last().reactionReceipts(original.id.toString(), assistant.id.toString()).isEmpty())
        }
    }

    @Test fun `receipt survives real repository reload and chat backup while badge rows remain intact`() = runBlocking {
        withRepository { db, repository, interactions ->
            val original = conversation()
            repository.insertConversation(original)
            interactions.react(original.id, assistant, target.id.toString(), "user", "ᵔ ᵕ ᵔ", add = true)
            val events = db.messageReactionDao().list(original.id.toString())
            val generation = interactions.prepareContext(original.currentMessages, original.id.toString(), assistant)
            generation.apply(original.currentMessages)
            val answer = reply()
            generation.onGenerationSucceeded(answer)
            val completed = generation.finish(answer, original.currentMessages + answer)
            repository.updateConversation(original.copy(messageNodes = original.messageNodes + MessageNode.of(completed)))
            val loaded = repository.getConversationById(original.id)!!
            val backup = JsonInstant.decodeFromString<List<Conversation>>(JsonInstant.encodeToString(repository.exportChatHistory())).single()
            assertEquals(loaded.messageNodes, backup.messageNodes)
            assertTrue(interactions.prepareContext(loaded.currentMessages, original.id.toString(), assistant).events.isEmpty())
            assertEquals(events, db.messageReactionDao().list(original.id.toString()))
            assertEquals(43, db.openHelper.writableDatabase.version)
            assertEquals("收到啦", backup.currentMessages.last().toText())
        }
    }

    @Test fun `late reaction and remove readd retain exact separate event versions in database`() = runBlocking {
        withRepository { db, repository, interactions ->
            val original = conversation()
            repository.insertConversation(original)
            interactions.react(original.id, assistant, target.id.toString(), "user", "ᵔ ᵕ ᵔ", add = true)
            val first = db.messageReactionDao().list(original.id.toString()).single()
            val generation = interactions.prepareContext(original.currentMessages, original.id.toString(), assistant)
            generation.apply(original.currentMessages)
            interactions.react(original.id, assistant, target.id.toString(), "user", "❤️", add = true)
            val late = db.messageReactionDao().list(original.id.toString()).single { it.emoji == "❤️" }
            val answer = reply()
            generation.onGenerationSucceeded(answer)
            val completed = generation.finish(answer, original.currentMessages + answer)
            val saved = original.copy(messageNodes = original.messageNodes + MessageNode.of(completed))
            repository.updateConversation(saved)
            assertEquals(listOf(late), interactions.prepareContext(saved.currentMessages, original.id.toString(), assistant).events)
            interactions.react(original.id, assistant, target.id.toString(), "user", first.emoji, add = false)
            interactions.react(original.id, assistant, target.id.toString(), "user", first.emoji, add = true)
            val repeated = db.messageReactionDao().list(original.id.toString()).single { it.emoji == first.emoji }
            assertEquals(first.id, repeated.id)
            assertTrue(repeated.createdAt > first.createdAt)
            assertEquals(setOf(late, repeated), interactions.prepareContext(saved.currentMessages, original.id.toString(), assistant).events.toSet())
        }
    }

    @Test fun `fork copies badge rows and rebases only the matching scoped receipt`() = runBlocking {
        withRepository { db, repository, interactions ->
            val original = conversation()
            repository.insertConversation(original)
            interactions.react(original.id, assistant, target.id.toString(), "user", "ᵔ ᵕ ᵔ", add = true)
            val generation = interactions.prepareContext(original.currentMessages, original.id.toString(), assistant)
            generation.apply(original.currentMessages)
            val answer = reply()
            generation.onGenerationSucceeded(answer)
            val completed = generation.finish(answer, original.currentMessages + answer)
            val source = original.copy(messageNodes = original.messageNodes + MessageNode.of(completed))
            repository.updateConversation(source)
            val forkId = Uuid.random()
            val fork = source.copy(id = forkId, messageNodes = source.messageNodes.map { node ->
                node.copy(id = Uuid.random(), messages = node.messages.map {
                    it.rebaseReactionReceipts(source.id.toString(), forkId.toString(), assistant.id.toString())
                })
            })
            repository.insertConversation(fork)
            interactions.copyForFork(source.id, fork.id, fork.currentMessages.mapTo(hashSetOf()) { it.id.toString() })
            val loaded = repository.getConversationById(fork.id)!!
            assertTrue(interactions.prepareContext(loaded.currentMessages, fork.id.toString(), assistant).events.isEmpty())
            assertEquals(1, db.messageReactionDao().list(fork.id.toString()).size)
            assertEquals(1, db.messageReactionDao().list(source.id.toString()).size)
            assertEquals(source.currentMessages.map { it.toText() }, loaded.currentMessages.map { it.toText() })
        }
    }

    private suspend fun withRepository(block: suspend (AppDatabase, ConversationRepository, ChatInteractionRepository) -> Unit) {
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
            block(db, repository, ChatInteractionRepository(db, repository))
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
