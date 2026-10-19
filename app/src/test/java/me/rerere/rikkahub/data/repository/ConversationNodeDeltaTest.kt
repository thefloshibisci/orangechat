package me.rerere.rikkahub.data.repository

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
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
class ConversationNodeDeltaTest {
    @Test
    fun `a known replacement and append leave unrelated rows and their index untouched`() = runBlocking {
        withRepository { db, repository ->
            val untouched = node("untouched")
            val branch = node("first").copy(messages = listOf(UIMessage.assistant("first"), UIMessage.assistant("second")))
            val before = conversation(untouched, branch)
            repository.insertConversation(before)
            val originalRow = db.messageNodeDao().getNodesByIds(listOf(untouched.id.toString())).single()
            installWriteLog(db)
            val updatedBranch = branch.copy(selectIndex = 1)
            val appended = node("new response")
            val after = before.copy(title = "latest title", isPinned = true,
                messageNodes = listOf(untouched, updatedBranch, appended))
            val delta = ConversationNodeDelta(before, after, changes(1 to updatedBranch, 2 to appended))

            repository.updateConversationWithDelta(after, before, delta)

            val stored = db.messageNodeDao().getNodesOfConversation(before.id.toString())
            assertEquals(originalRow, stored.first())
            assertEquals(listOf(updatedBranch, appended), stored.drop(1).map { entity ->
                MessageNode(Uuid.parse(entity.id), JsonInstant.decodeFromString<List<UIMessage>>(entity.messages), entity.selectIndex)
            })
            assertEquals(listOf(updatedBranch.id.toString(), appended.id.toString()), strings(db, "SELECT id FROM node_write_log"))
            assertEquals(listOf(before.title), strings(db,
                "SELECT title FROM message_fts WHERE node_id = '${untouched.id}'"))
            assertEquals(listOf(after.title, after.title), strings(db,
                "SELECT title FROM message_fts WHERE node_id = '${branch.id}'"))
            assertEquals(after.title, db.conversationDao().getConversationById(before.id.toString())!!.title)
            assertTrue(db.conversationDao().getConversationById(before.id.toString())!!.isPinned)
        }
    }

    @Test
    fun `a reference mismatch or incomplete snapshot cannot use a known delta`() = runBlocking {
        withRepository { db, repository ->
            val before = conversation(node("old"))
            repository.insertConversation(before)
            val replacement = before.messageNodes.single().copy(messages = listOf(UIMessage.assistant("changed")))
            val after = before.copy(messageNodes = listOf(replacement))
            val delta = ConversationNodeDelta(before, after, changes(0 to replacement))
            val mismatches = listOf(
                before.copy(messageNodes = ArrayList(before.messageNodes)) to after,
                before to after.copy(messageNodes = ArrayList(after.messageNodes)),
                before.copy(id = Uuid.random()) to after,
                before to after.copy(id = Uuid.random()),
                before.copy(messageNodesLoaded = false) to after,
                before to after.copy(messageNodesLoaded = false),
            )
            for ((baseline, current) in mismatches) {
                assertFalse(delta.appliesTo(baseline, current))
                expectFailure<IllegalArgumentException> {
                    repository.updateConversationWithDelta(current, baseline, delta)
                }
            }
            assertTrue(delta.appliesTo(before.copy(title = "metadata edit"), after.copy(isPinned = true)))
            assertEquals(JsonInstant.encodeToString(before.messageNodes.single().messages),
                db.messageNodeDao().getNodesOfConversation(before.id.toString()).single().messages)
        }
    }

    @Test
    fun `new base64 content is rejected before metadata or node writes`() = runBlocking {
        withRepository { db, repository ->
            val before = conversation(node("old"))
            repository.insertConversation(before)
            val replacement = before.messageNodes.single().copy(messages = listOf(UIMessage.assistant("").copy(
                parts = listOf(UIMessagePart.Image("data:image/png;base64,AAAA")),
            )))
            val after = before.copy(title = "must not save", messageNodes = listOf(replacement))
            val delta = ConversationNodeDelta(before, after, changes(0 to replacement))

            expectFailure<IllegalArgumentException> { repository.updateConversationWithDelta(after, before, delta) }
            expectFailure<IllegalArgumentException> { repository.conversationToConversationEntity(after) }

            assertEquals(before.title, db.conversationDao().getConversationById(before.id.toString())!!.title)
            assertEquals(JsonInstant.encodeToString(before.messageNodes.single().messages),
                db.messageNodeDao().getNodesOfConversation(before.id.toString()).single().messages)
        }
    }

    @Test
    fun `a late delta cannot recreate a deleted parent or its nodes`() = runBlocking {
        withRepository { db, repository ->
            val before = conversation(node("old"))
            repository.insertConversation(before)
            db.conversationDao().deleteById(before.id.toString())
            val appended = node("late")
            val after = before.copy(messageNodes = before.messageNodes + appended)
            val delta = ConversationNodeDelta(before, after, changes(1 to appended))

            expectFailure<IllegalStateException> { repository.updateConversationWithDelta(after, before, delta) }

            assertNull(db.conversationDao().getConversationById(before.id.toString()))
            assertEquals(0, db.messageNodeDao().getNodeCountOfConversation(before.id.toString()))
            assertTrue(strings(db, "SELECT node_id FROM message_fts WHERE node_id = '${appended.id}'").isEmpty())
        }
    }

    @Test
    fun `a node write failure rolls back metadata and earlier writes and permits retry`() = runBlocking {
        withRepository { db, repository ->
            val before = conversation(node("old"))
            repository.insertConversation(before)
            val replacement = before.messageNodes.single().copy(messages = listOf(UIMessage.assistant("changed")))
            val appended = node("new")
            val after = before.copy(title = "changed title", messageNodes = listOf(replacement, appended))
            val delta = ConversationNodeDelta(before, after, changes(0 to replacement, 1 to appended))
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_node BEFORE INSERT ON message_node
                WHEN NEW.id = '${appended.id}' BEGIN SELECT RAISE(ABORT, 'node write failed'); END
            """.trimIndent())

            expectFailure<android.database.SQLException> { repository.updateConversationWithDelta(after, before, delta) }

            assertEquals(before.title, db.conversationDao().getConversationById(before.id.toString())!!.title)
            assertEquals(JsonInstant.encodeToString(before.messageNodes.single().messages),
                db.messageNodeDao().getNodesOfConversation(before.id.toString()).single().messages)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_node")
            repository.updateConversationWithDelta(after, before, delta)
            assertEquals(2, db.messageNodeDao().getNodeCountOfConversation(before.id.toString()))
            assertEquals(after.title, db.conversationDao().getConversationById(before.id.toString())!!.title)
        }
    }

    @Test
    fun `an index failure propagates and rolls back the whole checkpoint`() = runBlocking {
        withRepository { db, repository ->
            val before = conversation(node("old"))
            repository.insertConversation(before)
            val replacement = before.messageNodes.single().copy(messages = listOf(UIMessage.assistant("changed")))
            val after = before.copy(title = "changed title", messageNodes = listOf(replacement))
            val delta = ConversationNodeDelta(before, after, changes(0 to replacement))
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_index BEFORE INSERT ON message_fts
                BEGIN SELECT RAISE(ABORT, 'index write failed'); END
            """.trimIndent())

            expectFailure<android.database.SQLException> { repository.updateConversationWithDelta(after, before, delta) }

            assertEquals(before.title, db.conversationDao().getConversationById(before.id.toString())!!.title)
            assertEquals(JsonInstant.encodeToString(before.messageNodes.single().messages),
                db.messageNodeDao().getNodesOfConversation(before.id.toString()).single().messages)
            assertEquals(listOf("old"), strings(db, "SELECT text FROM message_fts"))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_index")
            repository.updateConversationWithDelta(after, before, delta)
            assertEquals(listOf("changed"), strings(db, "SELECT text FROM message_fts"))
        }
    }

    @Test
    fun `a metadata-only delta does not visit untouched message nodes`() = runBlocking {
        withRepository { db, repository ->
            val seed = conversation(node("untouched"))
            repository.insertConversation(seed)
            val unreadableNodes = object : AbstractList<MessageNode>() {
                override val size = 1
                override fun get(index: Int): MessageNode = error("Untouched history must not be traversed")
            }
            val before = seed.copy(messageNodes = unreadableNodes)
            val after = before.copy(title = "metadata only")
            val delta = ConversationNodeDelta(before, after, changes())

            repository.updateConversationWithDelta(after, before, delta)

            assertEquals(after.title, db.conversationDao().getConversationById(before.id.toString())!!.title)
            assertEquals(1, db.messageNodeDao().getNodeCountOfConversation(before.id.toString()))
            assertEquals(listOf(seed.title), strings(db, "SELECT title FROM message_fts"))
        }
    }

    @Test
    fun `ambiguous destructive and incomplete append journals are rejected`() = runBlocking {
        val first = node("one")
        val second = node("two")
        val before = conversation(first, second)
        expectFailure<IllegalArgumentException> {
            ConversationNodeDelta(before, before, ConversationNodeChanges(emptyList(), setOf(first.id.toString())))
        }
        expectFailure<IllegalArgumentException> {
            ConversationNodeDelta(before, before.copy(messageNodes = listOf(first)), changes())
        }
        val reordered = before.copy(messageNodes = listOf(second, first))
        expectFailure<IllegalArgumentException> { ConversationNodeDelta(before, reordered, changes(0 to second, 1 to first)) }
        val appended = node("new")
        val after = before.copy(messageNodes = before.messageNodes + appended)
        expectFailure<IllegalArgumentException> { ConversationNodeDelta(before, after, changes()) }
        expectFailure<IllegalArgumentException> { ConversationNodeDelta(before, after, changes(2 to appended.copy())) }
        expectFailure<IllegalArgumentException> { ConversationNodeDelta(before, after, changes(2 to appended, 2 to appended)) }
        expectFailure<IllegalStateException> {
            ConversationNodeDelta(before.copy(messageNodesLoaded = false), after, changes(2 to appended))
        }
    }

    private fun changes(vararg nodes: Pair<Int, MessageNode>) = ConversationNodeChanges(nodes.toList(), emptySet())
    private fun node(text: String) = MessageNode.of(UIMessage.assistant(text))
    private fun conversation(vararg nodes: MessageNode) = Conversation(
        assistantId = Uuid.random(), title = "original title", messageNodes = nodes.toList(),
    )

    private suspend inline fun <reified T : Throwable> expectFailure(crossinline block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }

    private fun installWriteLog(db: AppDatabase) {
        db.openHelper.writableDatabase.execSQL("CREATE TABLE node_write_log(id TEXT)")
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER record_node_write AFTER INSERT ON message_node
            BEGIN INSERT INTO node_write_log(id) VALUES (NEW.id); END
        """.trimIndent())
    }

    private fun strings(db: AppDatabase, query: String): List<String> =
        db.openHelper.writableDatabase.query(query).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private suspend fun withRepository(block: suspend (AppDatabase, ConversationRepository) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = AppScope()
        try {
            // The production index writer uses ordinary INSERT/DELETE statements. This table
            // exercises those real statements without depending on the device tokenizer extension.
            db.openHelper.writableDatabase.execSQL("""
                CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT,
                    conversation_id TEXT, title TEXT, update_at TEXT)
            """.trimIndent())
            val dao = db.messageNodeDao()
            val noBodyReads = object : MessageNodeDAO by dao {
                override suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity> =
                    error("A known delta must not read old message bodies")
                override suspend fun getNodeIdsOfConversation(conversationId: String): List<String> =
                    error("A known delta must not scan old node ids")
                override suspend fun getNodesByIds(ids: List<String>): List<MessageNodeEntity> =
                    error("A known delta must not compare old message bodies")
            }
            val repository = ConversationRepository(db.conversationDao(), noBodyReads, db.favoriteDao(), db,
                FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db))
            block(db, repository)
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
