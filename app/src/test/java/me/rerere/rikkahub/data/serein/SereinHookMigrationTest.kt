package me.rerere.rikkahub.data.serein

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.sync.preflightBackupDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SereinHookMigrationTest {
    @Test fun `v36 upgrade preserves local scenes and restored hook records cascade with chat`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "hk-${Uuid.random().toString().take(8)}"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*APP_DATABASE_MIGRATIONS)
            .allowMainThreadQueries().build()
        try {
            var db = open()
            val scene = try {
                db.openHelper.writableDatabase.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES ('chat','a','test','[]',1,2)")
                LocalSceneRepository(db).create("a", "operation", "Keep", "Local scene")
            } finally { db.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE serein_hook_snapshot"); it.execSQL("DROP TABLE serein_hook_delivery"); it.version = 36
            }
            preflightBackupDatabase(context, context.getDatabasePath(name), FrameworkSQLiteOpenHelperFactory())
            db = open()
            try {
                assertEquals(43, db.openHelper.writableDatabase.version)
                assertEquals(scene, LocalSceneRepository(db).read("a", scene.id))
                db.sereinHookDao().put(SereinHookSnapshot("chat", "user", "fp", "reference", "[\"scene:x\"]", "ready", 1))
                db.sereinHookDao().record(SereinHookDelivery("chat", "reply", "config", "[]", 2))
            } finally { db.close() }
            db = open()
            try {
                assertEquals("reference", db.sereinHookDao().snapshot("chat", "user")!!.context)
                assertEquals(1, db.sereinHookDao().recent("chat", "config").size)
                db.openHelper.writableDatabase.execSQL("DELETE FROM ConversationEntity WHERE id='chat'")
                assertNull(db.sereinHookDao().snapshot("chat", "user"))
                assertTrue(db.sereinHookDao().recent("chat", "config").isEmpty())
                assertEquals(scene, LocalSceneRepository(db).read("a", scene.id))
                assertTrue(db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
