package me.rerere.rikkahub.data.repository

import android.app.Application
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeHeader
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class MessageNodeStorageTest {
    @Test
    fun `normal pages return every node in stored order`() = runBlocking {
        withDatabase { db ->
            val dao = db.messageNodeDao()
            val nodes = (0 until 130).map { node(it, "message-$it") }
            dao.insertAll(nodes.reversed())
            var pages = 0
            val checked = object : MessageNodeDAO by dao {
                override suspend fun getNodesOfConversationPaged(
                    conversationId: String, limit: Int, offset: Int,
                ): List<MessageNodeEntity> {
                    pages++
                    return dao.getNodesOfConversationPaged(conversationId, limit, offset)
                }
            }

            assertEquals(nodes, db.withTransaction { checked.loadCompleteNodes(CONVERSATION) })
            assertEquals(3, pages)
        }
    }

    @Test
    fun `oversized Unicode JSON is reconstructed using real SQLite code point slices`() = runBlocking {
        withDatabase { db ->
            val dao = db.messageNodeDao()
            val text = "[{\"text\":\"" + "橘😀".repeat(400_000) + "\"}]"
            assertTrue(text.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024)
            val large = node(1, text)
            val initial = listOf(node(0, "before"), large, node(2, "after"))
            dao.insertAll(initial)
            var sliceCalls = 0
            val fallback = object : MessageNodeDAO by dao {
                override suspend fun getNodesOfConversationPaged(
                    conversationId: String, limit: Int, offset: Int,
                ): List<MessageNodeEntity> = throw SQLiteBlobTooBigException("forced CursorWindow overflow")

                override suspend fun getMessageSlice(id: String, start: Int, length: Int): String? {
                    sliceCalls++
                    return dao.getMessageSlice(id, start, length)
                }
            }

            val headers = dao.getNodeHeaders(CONVERSATION, 64, 0)
            assertEquals(text.codePointCount(0, text.length), headers[1].messageLength)
            assertEquals(initial, db.withTransaction { fallback.loadCompleteNodes(CONVERSATION) })
            assertTrue(sliceCalls > 10)
            assertEquals(3, dao.getNodeCountOfConversation(CONVERSATION))
        }
    }

    @Test
    fun `a partial normal page fails instead of returning a deletable partial history`() = runBlocking {
        withDatabase { db ->
            val dao = db.messageNodeDao()
            dao.insertAll((0 until 65).map { node(it, "message") })
            val partial = object : MessageNodeDAO by dao {
                override suspend fun getNodesOfConversationPaged(
                    conversationId: String, limit: Int, offset: Int,
                ) = dao.getNodesOfConversationPaged(conversationId, limit, offset).let {
                    if (offset > 0) emptyList() else it
                }
            }

            assertIncomplete { db.withTransaction { partial.loadCompleteNodes(CONVERSATION) } }
            assertEquals(65, dao.getNodeCountOfConversation(CONVERSATION))
        }
    }

    @Test
    fun `missing fallback header fails and leaves all stored rows intact`() = runBlocking {
        withDatabase { db ->
            val dao = db.messageNodeDao()
            val nodes = listOf(node(0, "first"), node(1, "second"))
            dao.insertAll(nodes)
            val partial = object : MessageNodeDAO by forceOverflow(dao) {
                override suspend fun getNodeHeaders(
                    conversationId: String, limit: Int, offset: Int,
                ): List<MessageNodeHeader> = dao.getNodeHeaders(conversationId, limit, offset).dropLast(1)
            }

            assertIncomplete { db.withTransaction { partial.loadCompleteNodes(CONVERSATION) } }
            assertEquals(nodes, dao.getNodesOfConversation(CONVERSATION))
        }
    }

    @Test
    fun `missing empty and truncated fallback slices each fail without modifying the row`() = runBlocking {
        withDatabase { db ->
            val dao = db.messageNodeDao()
            val original = node(0, "😀".repeat(70_000))
            dao.insert(original)
            for (missing in listOf<String?>(null, "", "truncated")) {
                val partial = object : MessageNodeDAO by forceOverflow(dao) {
                    override suspend fun getMessageSlice(id: String, start: Int, length: Int): String? =
                        if (start > 1) missing else dao.getMessageSlice(id, start, length)
                }

                assertIncomplete { db.withTransaction { partial.loadCompleteNodes(CONVERSATION) } }
                assertEquals(1, dao.getNodeCountOfConversation(CONVERSATION))
                assertEquals(original.messages, dao.getMessageSlice(original.id, 1, 100_000))
            }
        }
    }

    @Test
    fun `unexpected read failure propagates without replacing it with empty history`() = runBlocking {
        withDatabase { db ->
            val dao = db.messageNodeDao()
            dao.insert(node(0, "original"))
            val failure = IllegalStateException("database unavailable")
            val broken = object : MessageNodeDAO by dao {
                override suspend fun getNodesOfConversationPaged(
                    conversationId: String, limit: Int, offset: Int,
                ): List<MessageNodeEntity> = throw failure
            }
            try {
                db.withTransaction { broken.loadCompleteNodes(CONVERSATION) }
                fail("Read failure must propagate")
            } catch (actual: IllegalStateException) {
                // Coroutine stacktrace recovery may copy the exception but must preserve its cause.
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
            }
            assertEquals(1, dao.getNodeCountOfConversation(CONVERSATION))
        }
    }

    @Test
    fun `empty stored conversation is a complete empty result`() = runBlocking {
        withDatabase { db ->
            assertEquals(emptyList<MessageNodeEntity>(), db.withTransaction {
                db.messageNodeDao().loadCompleteNodes(CONVERSATION)
            })
        }
    }

    private fun forceOverflow(dao: MessageNodeDAO): MessageNodeDAO = object : MessageNodeDAO by dao {
        override suspend fun getNodesOfConversationPaged(
            conversationId: String, limit: Int, offset: Int,
        ): List<MessageNodeEntity> = throw SQLiteBlobTooBigException("forced CursorWindow overflow")
    }

    private suspend fun assertIncomplete(block: suspend () -> Unit) {
        try {
            block()
            fail("An incomplete read must fail before publishing history")
        } catch (_: IllegalStateException) {
            // No partially loaded conversation is returned.
        }
    }

    private fun node(index: Int, text: String) = MessageNodeEntity("node-$index", CONVERSATION, index, text, 0)

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.conversationDao().insert(ConversationEntity(
                CONVERSATION, "assistant", "test", "[]", 10, 20, "[]", false,
            ))
            block(db)
        } finally {
            db.close()
        }
    }

    private companion object { const val CONVERSATION = "conversation" }
}
