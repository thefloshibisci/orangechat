package me.rerere.rikkahub.data.db.migrations

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

/** Opens the frozen, full native v38 schema through Room's real migration and validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ConversationConfigMigrationTest {
    @Test
    fun `native v38 upgrades without losing messages compression originals or queued uploads`() = verify(false)

    @Test
    fun `precreated config column keeps its bytes during migration and reopen`() = verify(true)

    private fun verify(precreated: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val name = "cfg-${Uuid.random().toString().take(8)}"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schemaPath = "schemas/me.rerere.rikkahub.data.db.AppDatabase/38.json"
        val schemaFile = listOf(File(schemaPath), File("app/$schemaPath")).firstOrNull { it.isFile }
        checkNotNull(schemaFile) { "Missing frozen native v38 schema" }
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        val rawConfig = " {\"maxTokens\":321,\"future_setting\":{\"keep\":true}} "
        val messages = "[{\"text\":\"preserve message and branch\"}]"
        val compression = "[{\"text\":\"preserve compression\"}]"
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.setForeignKeyConstraintsEnabled(true)
                schema.getValue("entities").jsonArray.forEach { entity ->
                    val definition = entity.jsonObject
                    val table = definition.getValue("tableName").jsonPrimitive.content
                    db.execSQL(definition.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    definition["indices"]?.jsonArray?.forEach { index ->
                        db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL("INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at,compression_summaries,compression_revision,send_compressed_summary,compression_history) VALUES ('chat','keep title','[]',1,2,?,7,0,'[1]')", arrayOf(compression))
                db.execSQL("INSERT INTO message_node VALUES ('node','chat',0,?,1)", arrayOf(messages))
                db.execSQL("INSERT INTO favorites VALUES ('favorite','message','ref','{}','{}',NULL,1,2)")
                db.execSQL("INSERT INTO serein_raw_revision VALUES ('revision','assistant','chat','message',1,'assistant','keep original','2026-10-25T00:00:00Z',NULL,1,'live','recorded')")
                db.execSQL("INSERT INTO serein_raw_head VALUES ('assistant','chat','node','message','revision',0,1,1)")
                db.execSQL("INSERT INTO serein_upload VALUES ('upload','assistant','chat','node','message','destination','hash','keep payload','pending','',1)")
                db.execSQL("CREATE TABLE message_fts (text TEXT)")
                db.execSQL("INSERT INTO message_fts VALUES ('keep search')")
                if (precreated) {
                    db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN conversation_config TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE ConversationEntity SET conversation_config=? WHERE id='chat'", arrayOf(rawConfig))
                }
                db.version = 38
            }
            repeat(2) {
                val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .addMigrations(*APP_DATABASE_MIGRATIONS).allowMainThreadQueries().build()
                try {
                    val db = room.openHelper.writableDatabase
                    assertEquals(42, db.version)
                    db.query("SELECT title,compression_summaries,compression_revision,send_compressed_summary,compression_history,conversation_config FROM ConversationEntity WHERE id='chat'").use {
                        assertTrue(it.moveToFirst())
                        assertEquals("keep title", it.getString(0))
                        assertEquals(compression, it.getString(1))
                        assertEquals(7L, it.getLong(2))
                        assertEquals(0, it.getInt(3))
                        assertEquals("[1]", it.getString(4))
                        assertEquals(if (precreated) rawConfig else "", it.getString(5))
                    }
                    db.query("SELECT messages,select_index FROM message_node").use {
                        assertTrue(it.moveToFirst())
                        assertEquals(messages, it.getString(0)); assertEquals(1, it.getInt(1))
                        assertFalse(it.moveToNext())
                    }
                    for (table in listOf("favorites", "serein_raw_revision", "serein_raw_head", "serein_upload", "message_fts")) {
                        db.query("SELECT COUNT(*) FROM `$table`").use {
                            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                        }
                    }
                    db.query("SELECT text FROM serein_raw_revision").use {
                        assertTrue(it.moveToFirst()); assertEquals("keep original", it.getString(0))
                    }
                    db.query("SELECT payload,status FROM serein_upload").use {
                        assertTrue(it.moveToFirst())
                        assertEquals("keep payload", it.getString(0)); assertEquals("pending", it.getString(1))
                    }
                    db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    db.query("PRAGMA integrity_check").use {
                        assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
                    }
                } finally { room.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
