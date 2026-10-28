package me.rerere.rikkahub.data.serein

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.sync.preflightBackupDatabase
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class RawArchiveMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `v34 upgrade adds empty archive and preserves existing messages`() = runBlocking {
        val name = "raw-m-${Uuid.random().toString().take(8)}"
        val path = context.getDatabasePath(name)
        try {
            room(name).useDatabase { db -> seedChat(db) }
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE serein_raw_head")
                db.execSQL("DROP TABLE serein_raw_revision")
                db.version = 34
            }
            room(name).useDatabase { db ->
                assertEquals(43, db.openHelper.writableDatabase.version)
                assertEquals("原话要保留", JsonInstant.decodeFromString<List<UIMessage>>(db.messageNodeDao().getNodesOfConversation("chat").single().messages).single().toText())
                assertTrue(db.rawArchiveDao().search("assistant", "%", 50).isEmpty())
                assertTrue(db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `closed database backup and preflight reopen preserve raw revisions and selected branch`() = runBlocking {
        val name = "raw-b-${Uuid.random().toString().take(8)}"
        val file = File(context.cacheDir, "raw-${Uuid.random().toString().take(8)}.db")
        try {
            var originalId = ""
            var messageId = ""
            room(name).useDatabase { db ->
                val node = seedChat(db)
                messageId = node.currentMessage.id.toString()
                db.withTransaction {
                    RawArchive(db.rawArchiveDao()).sync("assistant", "chat", listOf(0 to node))
                }
                originalId = db.rawArchiveDao().search("assistant", "%", 50).single().id
                val edit = node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("修改后的原话")))))
                db.withTransaction {
                    db.messageNodeDao().insert(MessageNodeEntity(edit.id.toString(), "chat", 0, JsonInstant.encodeToString(edit.messages), 0))
                    RawArchive(db.rawArchiveDao()).sync("assistant", "chat", listOf(0 to edit))
                }
            }
            context.getDatabasePath(name).copyTo(file)
            preflightBackupDatabase(context, file, FrameworkSQLiteOpenHelperFactory())
            room(file.absolutePath).useDatabase { db ->
                assertEquals("修改后的原话", db.rawArchiveDao().search("assistant", "%", 50).single().text)
                val history = db.rawArchiveDao().history("assistant", "chat", messageId, 100)
                assertEquals(listOf("修改后的原话", "原话要保留"), history.map { it.text })
                assertEquals(originalId, history.last().id)
                assertTrue(db.rawArchiveDao().search("other-assistant", "%", 50).isEmpty())
                assertTrue(db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
            }
        } finally {
            context.deleteDatabase(name)
            context.deleteDatabase(file.absolutePath)
        }
    }

    private suspend fun seedChat(db: AppDatabase): me.rerere.rikkahub.data.model.MessageNode {
        db.openHelper.writableDatabase.execSQL("INSERT INTO ConversationEntity (id,assistant_id,title,nodes,create_at,update_at) VALUES ('chat','assistant','test','[]',1,2)")
        val message = UIMessage.user("原话要保留")
        val node = message.toMessageNode()
        db.messageNodeDao().insert(MessageNodeEntity(node.id.toString(), "chat", 0, JsonInstant.encodeToString(node.messages), 0))
        return node
    }

    private fun room(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .addMigrations(*APP_DATABASE_MIGRATIONS)
        .allowMainThreadQueries().build()

    private suspend fun <T> AppDatabase.useDatabase(block: suspend (AppDatabase) -> T): T =
        try { block(this) } finally { close() }
}
