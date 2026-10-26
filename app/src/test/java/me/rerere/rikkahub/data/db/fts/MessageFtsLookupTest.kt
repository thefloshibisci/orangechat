package me.rerere.rikkahub.data.db.fts

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class MessageFtsLookupTest {
    private fun conversation(text: String) = Conversation(
        assistantId = Uuid.random(), title = text,
        messageNodes = listOf(UIMessage.user(text).toMessageNode(), UIMessage.assistant("reply").toMessageNode()),
    )

    @Test fun legacyRowsAreMappedOnceWithoutRewritingOriginalRows() = runBlocking {
        fixture { database, manager ->
            val db = database.openHelper.writableDatabase
            db.execSQL("INSERT INTO message_fts(rowid,text,node_id,message_id,conversation_id,title,update_at) VALUES (91,'中文原话','n','m','c','title','123')")
            ensureMessageFtsLookup(db)
            assertEquals("91|c|n", db.query("SELECT fts_rowid,conversation_id,node_id FROM message_fts_lookup").use {
                assertTrue(it.moveToFirst()); "${it.getLong(0)}|${it.getString(1)}|${it.getString(2)}"
            })
            db.execSQL("CREATE TRIGGER reject_rewrite BEFORE UPDATE ON message_fts BEGIN SELECT RAISE(ABORT,'rewrite'); END")
            ensureMessageFtsLookup(db)
            assertEquals(1, count(database, "message_fts"))
            assertEquals(1, count(database, "message_fts_lookup"))
            val text = db.query("SELECT text FROM message_fts WHERE rowid=91").use { it.moveToFirst(); it.getString(0) }
            assertEquals("中文原话", text)
            manager.deleteConversation("c")
            assertEquals(0, count(database, "message_fts"))
            assertEquals(0, count(database, "message_fts_lookup"))
        }
    }

    @Test fun nodeEditsAndRemovalPreserveOtherConversationsAndUseIndexedLookup() = runBlocking {
        fixture { database, manager ->
            val first = conversation("first")
            val second = conversation("other")
            manager.indexConversation(first)
            manager.indexConversation(second)
            val changed = first.messageNodes.first().copy(messages = listOf(UIMessage.user("changed")))
            val removed = first.messageNodes.last()
            manager.reindexNodes(first.id.toString(), "new title", Instant.ofEpochMilli(2000),
                setOf(changed.id.toString(), removed.id.toString()), listOf(changed), failOnError = true)
            assertEquals(3, count(database, "message_fts"))
            assertEquals(3, count(database, "message_fts_lookup"))
            val db = database.openHelper.writableDatabase
            db.query("SELECT text,title FROM message_fts WHERE conversation_id=?", arrayOf(first.id.toString())).use {
                assertTrue(it.moveToFirst()); assertEquals("changed", it.getString(0)); assertEquals("new title", it.getString(1))
                assertFalse(it.moveToNext())
            }
            db.query("EXPLAIN QUERY PLAN SELECT fts_rowid FROM message_fts_lookup WHERE conversation_id=? AND node_id=?",
                arrayOf(first.id.toString(), changed.id.toString())).use {
                assertTrue(it.moveToFirst()); assertTrue(it.getString(3).contains("index_message_fts_lookup_node"))
            }
            manager.deleteConversation(first.id.toString())
            assertEquals(2, count(database, "message_fts"))
            assertEquals(2, count(database, "message_fts_lookup"))
            manager.deleteAll()
            assertEquals(0, count(database, "message_fts"))
            assertEquals(0, count(database, "message_fts_lookup"))
        }
    }

    @Test fun replacementAndLookupBothRollbackOnInsertFailureAndCallerCancellation() = runBlocking {
        fixture { database, manager ->
            val original = conversation("keep")
            manager.indexConversation(original)
            val db = database.openHelper.writableDatabase
            db.execSQL("CREATE TRIGGER reject_search BEFORE INSERT ON message_fts WHEN NEW.text='fail' BEGIN SELECT RAISE(ABORT,'reject'); END")
            val failing = original.copy(messageNodes = listOf(UIMessage.user("fail").toMessageNode()))
            assertNotNull(runCatching { manager.replaceConversationIndex(failing) }.exceptionOrNull())
            assertEquals(2, count(database, "message_fts"))
            assertEquals(2, count(database, "message_fts_lookup"))
            val error = runCatching {
                database.withTransaction {
                    manager.replaceConversationIndex(original.copy(messageNodes = emptyList()))
                    throw kotlinx.coroutines.CancellationException("cancel restore")
                }
            }.exceptionOrNull()
            assertTrue(error is kotlinx.coroutines.CancellationException)
            assertEquals(2, count(database, "message_fts"))
            assertEquals(2, count(database, "message_fts_lookup"))
            db.query("SELECT text FROM message_fts ORDER BY rowid").use {
                assertTrue(it.moveToFirst()); assertEquals("keep", it.getString(0))
            }
        }
    }

    @Test fun virtualFtsRowsKeepCorrectRowidsAcrossReindexAndDeletion() = runBlocking {
        fixture(virtualFts = true) { database, manager ->
            val first = conversation("searchable original")
            val second = conversation("unrelated original")
            manager.indexConversation(first)
            manager.indexConversation(second)
            val db = database.openHelper.writableDatabase
            db.query("SELECT COUNT(*) FROM message_fts_lookup AS lookup JOIN message_fts AS fts ON fts.rowid=lookup.fts_rowid WHERE fts.conversation_id=lookup.conversation_id AND fts.node_id=lookup.node_id").use {
                assertTrue(it.moveToFirst()); assertEquals(4, it.getInt(0))
            }
            val edited = first.messageNodes.first().copy(messages = listOf(UIMessage.user("replacement")))
            manager.reindexNodes(first.id.toString(), first.title, first.updateAt,
                setOf(edited.id.toString()), listOf(edited), failOnError = true)
            db.query("SELECT COUNT(*) FROM message_fts WHERE text MATCH 'replacement'").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM message_fts WHERE text MATCH 'searchable'").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            manager.deleteConversation(first.id.toString())
            db.query("SELECT COUNT(*) FROM message_fts WHERE text MATCH 'unrelated'").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            assertEquals(2, count(database, "message_fts"))
            assertEquals(2, count(database, "message_fts_lookup"))
        }
    }

    private fun count(database: AppDatabase, table: String): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun fixture(virtualFts: Boolean = false, block: suspend (AppDatabase, MessageFtsManager) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.execSQL(if (virtualFts) {
                "CREATE VIRTUAL TABLE message_fts USING fts4(text,node_id,message_id,conversation_id,title,update_at)"
            } else {
                "CREATE TABLE message_fts(text TEXT,node_id TEXT,message_id TEXT,conversation_id TEXT,title TEXT,update_at TEXT)"
            })
            block(database, MessageFtsManager(database))
        } finally {
            database.close()
        }
    }
}
