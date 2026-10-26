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
class LocalSceneMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun room(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .addMigrations(*APP_DATABASE_MIGRATIONS).allowMainThreadQueries().build()

    @Test fun `v35 backup upgrades without losing chats or original archive then roundtrips scenes`() = runBlocking {
        val name = "sc-${Uuid.random().toString().take(8)}"
        val file = File(context.cacheDir, "$name.db")
        try {
            var db = room(name)
            try {
                db.openHelper.writableDatabase.execSQL("INSERT INTO ConversationEntity (id,assistant_id,title,nodes,create_at,update_at) VALUES ('chat','assistant','test','[]',1,2)")
                val node = UIMessage.user("保留原话").toMessageNode()
                db.messageNodeDao().insert(MessageNodeEntity(node.id.toString(), "chat", 0, JsonInstant.encodeToString(node.messages), 0))
                db.withTransaction { RawArchive(db.rawArchiveDao()).sync("assistant", "chat", listOf(0 to node)) }
            } finally { db.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE serein_scene_revision")
                it.execSQL("DROP TABLE serein_scene")
                it.version = 35
            }
            context.getDatabasePath(name).copyTo(file)
            preflightBackupDatabase(context, file, FrameworkSQLiteOpenHelperFactory())
            var sceneId = ""
            db = room(file.absolutePath)
            try {
                assertEquals(42, db.openHelper.writableDatabase.version)
                assertEquals(1, db.messageNodeDao().getNodesOfConversation("chat").size)
                assertEquals("保留原话", db.rawArchiveDao().search("assistant", "%", 50).single().text)
                val repo = LocalSceneRepository(db)
                assertTrue(repo.search("assistant").isEmpty())
                val first = repo.create("assistant", "op", "Title", "Before")
                sceneId = first.id
                repo.edit("assistant", first.id, 1, "Title", "After")
                val erased = repo.create("assistant", "erase", "Erased", "Do not restore")
                repo.delete("assistant", erased.id, 1)
            } finally { db.close() }
            // A closed backup is validated by the same preflight used by the app restore path.
            preflightBackupDatabase(context, file, FrameworkSQLiteOpenHelperFactory())
            db = room(file.absolutePath)
            try {
                val repo = LocalSceneRepository(db)
                assertEquals("After", repo.read("assistant", sceneId)!!.body)
                assertEquals(listOf("After", "Before"), repo.history("assistant", sceneId).map { it.body })
                assertTrue(repo.search("other").isEmpty())
                assertTrue(repo.search("assistant", "Erased").isEmpty())
                assertTrue(runCatching { repo.create("assistant", "erase", "Erased", "Do not restore") }.exceptionOrNull() is SceneConflict)
                assertTrue(db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
            } finally { db.close() }
        } finally {
            context.deleteDatabase(name)
            context.deleteDatabase(file.absolutePath)
        }
    }
}
