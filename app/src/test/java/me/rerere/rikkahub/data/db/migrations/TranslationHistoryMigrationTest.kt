package me.rerere.rikkahub.data.db.migrations

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.UsageTotals
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
// Robolectric places the class and method names in the temporary path. Native Windows SQLite
// needs short test/database names so journal files stay within its path limit.
class TranslationHistoryMigrationTest {
    @Test
    fun native40PreservesLocalData() = runBlocking {
        verify(precreated = false)
    }

    @Test
    fun precreatedHistoryIsPreserved() = runBlocking {
        verify(precreated = true)
    }

    @Test
    fun malformedHistoryFailsSafely() {
        val context = RuntimeEnvironment.getApplication()
        val name = "tb-${Uuid.random().toString().take(8)}"
        try {
            seed40(name, listOf(
                """CREATE TABLE translation_history (id TEXT NOT NULL PRIMARY KEY, source_text TEXT NOT NULL,
                    translated_text TEXT NOT NULL, target_language TEXT NOT NULL, model_id TEXT NOT NULL,
                    create_at INTEGER NOT NULL, payload TEXT)""".trimIndent(),
                "INSERT INTO translation_history VALUES('foreign','source','result','en','model',1,'preserve me')",
            ))
            val room = open(name)
            try {
                val error = runCatching { room.openHelper.writableDatabase }.exceptionOrNull()
                assertNotNull(error)
                assertTrue(error!!.message.orEmpty().contains("Migration didn't properly handle"))
            } finally { room.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(40, db.version)
                db.rawQuery("SELECT payload FROM translation_history WHERE id='foreign'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("preserve me", it.getString(0))
                }
                db.rawQuery("SELECT title FROM ConversationEntity WHERE id='chat'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("keep title", it.getString(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    private suspend fun verify(precreated: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val name = "tm-${Uuid.random().toString().take(8)}"
        val extra = if (precreated) listOf(
            """CREATE TABLE translation_history (id TEXT NOT NULL PRIMARY KEY, source_text TEXT NOT NULL,
                translated_text TEXT NOT NULL, target_language TEXT NOT NULL, model_id TEXT NOT NULL,
                create_at INTEGER NOT NULL)""".trimIndent(),
            "CREATE INDEX index_translation_history_create_at ON translation_history(create_at)",
            "INSERT INTO translation_history VALUES('foreign','原文 👩🏽‍💻','译文','zh-Hant-TW','missing-model',1234)",
        ) else emptyList()
        try {
            seed40(name, extra)
            repeat(2) {
                val room = open(name)
                try {
                    val db = room.openHelper.writableDatabase
                    assertEquals(43, db.version)
                    db.query("SELECT title,conversation_config,compression_summaries,compression_revision,compression_history FROM ConversationEntity").use { row ->
                        assertTrue(row.moveToFirst()); assertEquals("keep title", row.getString(0))
                        assertEquals(" {\"future\":true,\"maxTokens\":321} ", row.getString(1))
                        assertEquals("keep summary", row.getString(2)); assertEquals(7L, row.getLong(3))
                        assertEquals("keep compression history", row.getString(4))
                    }
                    db.query("SELECT messages,select_index FROM message_node").use {
                        assertTrue(it.moveToFirst()); assertEquals("keep message variants", it.getString(0)); assertEquals(1, it.getInt(1))
                    }
                    db.query("SELECT text FROM serein_raw_revision").use {
                        assertTrue(it.moveToFirst()); assertEquals("keep original", it.getString(0))
                    }
                    db.query("SELECT payload,status FROM serein_upload").use {
                        assertTrue(it.moveToFirst()); assertEquals("keep payload", it.getString(0)); assertEquals("pending", it.getString(1))
                    }
                    assertEquals(UsageTotals(1, 20, 5, 10, 30), room.usageRecordDao().getTotals())
                    db.query("SELECT * FROM translation_history").use { row ->
                        assertEquals(if (precreated) 1 else 0, row.count)
                        if (precreated) {
                            assertTrue(row.moveToFirst()); assertEquals("foreign", row.getString(0))
                            assertEquals("原文 👩🏽‍💻", row.getString(1)); assertEquals("译文", row.getString(2))
                            assertEquals("zh-Hant-TW", row.getString(3)); assertEquals("missing-model", row.getString(4))
                            assertEquals(1234L, row.getLong(5))
                        }
                    }
                    assertEquals(0, db.query("PRAGMA foreign_key_list(translation_history)").use { it.count })
                    db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    db.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
                } finally { room.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun open(name: String) = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*APP_DATABASE_MIGRATIONS)
        .allowMainThreadQueries().build()

    private fun seed40(name: String, extraSql: List<String>) {
        val context = RuntimeEnvironment.getApplication()
        val schemaPath = "schemas/me.rerere.rikkahub.data.db.AppDatabase/40.json"
        val file = checkNotNull(listOf(File(schemaPath), File("app/$schemaPath")).firstOrNull { it.isFile })
        val schema = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        println("translation migration SQLite path length: ${path.absolutePath.length}")
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
            db.execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at,conversation_config,compression_summaries,compression_revision,compression_history) VALUES('chat','keep title','[]',1,2,?,'keep summary',7,'keep compression history')", arrayOf(" {\"future\":true,\"maxTokens\":321} "))
            db.execSQL("INSERT INTO message_node VALUES('node','chat',0,'keep message variants',1)")
            db.execSQL("INSERT INTO serein_raw_revision VALUES('revision','assistant','chat','message',1,'assistant','keep original','2026-10-25T00:00:00Z',NULL,1,'live','recorded')")
            db.execSQL("INSERT INTO serein_upload VALUES('upload','assistant','chat','node','message','destination','hash','keep payload','pending','',1)")
            db.execSQL("INSERT INTO usage_record VALUES(42,1234,'CHAT','provider','model','snapshot','assistant','chat',20,5,10,30,1,'SUCCESS')")
            extraSql.forEach(db::execSQL)
            db.version = 40
        }
    }
}
