package me.rerere.rikkahub.data.db.migrations

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class MessageReactionMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `existing fork reaction survives v33 to v34`() {
        verify(existingTable = true)
    }

    @Test
    fun `missing reaction table is added without touching chats`() {
        verify(existingTable = false)
    }

    private fun verify(existingTable: Boolean) {
        val name = "reaction-${Uuid.random().toString().take(8)}"
        val path = context.getDatabasePath(name)
        val room = room(name)
        try {
            room.openHelper.writableDatabase.execSQL(
                "INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at) VALUES ('chat','title','[]',1,2)"
            )
            if (existingTable) {
                room.openHelper.writableDatabase.execSQL(
                    "INSERT INTO message_reaction VALUES ('existing','chat','message','user','❤️','喜欢牛奶',9)"
                )
            }
        } finally {
            room.close()
        }

        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.version = 33
            if (!existingTable) {
                db.execSQL("DROP INDEX index_message_reaction_conversationId")
                db.execSQL("DROP TABLE message_reaction")
            }
        }

        val migrated = room(name)
        try {
            val db = migrated.openHelper.writableDatabase
            assertEquals(42, db.version)
            db.query("SELECT COUNT(*) FROM ConversationEntity").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            db.query("SELECT id,emoji,quote FROM message_reaction").use {
                if (existingTable) {
                    assertTrue(it.moveToFirst())
                    assertEquals("existing", it.getString(0))
                    assertEquals("❤️", it.getString(1))
                    assertEquals("喜欢牛奶", it.getString(2))
                } else {
                    assertFalse(it.moveToFirst())
                }
            }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private fun room(name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*APP_DATABASE_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
}
