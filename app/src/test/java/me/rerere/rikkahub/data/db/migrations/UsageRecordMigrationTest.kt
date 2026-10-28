package me.rerere.rikkahub.data.db.migrations

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.UsageTotals
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
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
class UsageRecordMigrationTest {
    @Test
    fun `native v39 migration preserves chat config compression originals and pending outbox`() = runBlocking {
        verify(precreated = false)
    }

    @Test
    fun `a precreated current usage table retains rows indexes and auto increment sequence`() = runBlocking {
        verify(precreated = true)
    }

    @Test
    fun `a malformed preexisting usage table fails Room validation without clearing data`() {
        val context = RuntimeEnvironment.getApplication()
        val name = "bad-${Uuid.random().toString().take(8)}"
        try {
            seed39(name, listOf("""CREATE TABLE usage_record (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, created_at INTEGER NOT NULL,
                source TEXT NOT NULL, provider_id TEXT, model_id TEXT, model_name TEXT,
                assistant_id TEXT, conversation_id TEXT, prompt_tokens INTEGER NOT NULL,
                completion_tokens INTEGER NOT NULL, cached_tokens INTEGER NOT NULL, total_tokens INTEGER NOT NULL,
                usage_reported INTEGER NOT NULL, outcome TEXT NOT NULL, payload TEXT)""".trimIndent(),
                "INSERT INTO usage_record(id,created_at,source,prompt_tokens,completion_tokens,cached_tokens,total_tokens,usage_reported,outcome,payload) VALUES(42,1,'CHAT',0,0,0,0,0,'ERROR','preserve original')"))
            val room = open(name)
            try {
                val error = runCatching { room.openHelper.writableDatabase }.exceptionOrNull()
                assertNotNull(error)
                assertTrue(error!!.message.orEmpty().contains("Migration didn't properly handle"))
            } finally { room.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
                assertEquals(39, it.version)
                it.rawQuery("SELECT payload FROM usage_record WHERE id=42", null).use { row ->
                    assertTrue(row.moveToFirst()); assertEquals("preserve original", row.getString(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    private suspend fun verify(precreated: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val name = "use-${Uuid.random().toString().take(8)}"
        val template = if (precreated) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build() else null
        try {
            val ddl = template?.openHelper?.writableDatabase?.query(
                "SELECT sql FROM sqlite_master WHERE tbl_name='usage_record' AND sql IS NOT NULL ORDER BY type DESC",
            )?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }.orEmpty()
            seed39(name, ddl + if (precreated) listOf(
                "INSERT INTO usage_record VALUES (42, 1234, 'CHAT', 'provider', 'model', 'snapshot', 'assistant', 'chat', 20, 5, 10, 30, 1, 'SUCCESS')",
                "UPDATE sqlite_sequence SET seq=99 WHERE name='usage_record'",
            ) else emptyList())
            repeat(2) {
                val room = open(name)
                try {
                    val db = room.openHelper.writableDatabase
                    assertEquals(43, db.version)
                    db.query("SELECT title,conversation_config,compression_summaries,compression_revision,compression_history FROM ConversationEntity").use { row ->
                        assertTrue(row.moveToFirst())
                        assertEquals("keep title", row.getString(0))
                        assertEquals(" {\"future\":true,\"maxTokens\":321} ", row.getString(1))
                        assertEquals("keep summary", row.getString(2))
                        assertEquals(7L, row.getLong(3)); assertEquals("keep compression history", row.getString(4))
                    }
                    db.query("SELECT messages,select_index FROM message_node").use { row ->
                        assertTrue(row.moveToFirst())
                        assertEquals("keep message variants", row.getString(0)); assertEquals(1, row.getInt(1))
                    }
                    db.query("SELECT text FROM serein_raw_revision").use { row ->
                        assertTrue(row.moveToFirst()); assertEquals("keep original", row.getString(0))
                    }
                    db.query("SELECT payload,status FROM serein_upload").use { row ->
                        assertTrue(row.moveToFirst()); assertEquals("keep payload", row.getString(0))
                        assertEquals("pending", row.getString(1))
                    }
                    for (table in listOf("favorites", "serein_raw_head", "message_fts")) {
                        db.query("SELECT COUNT(*) FROM `$table`").use { row ->
                            assertTrue(row.moveToFirst()); assertEquals(1, row.getInt(0))
                        }
                    }
                    assertEquals(if (precreated) UsageTotals(1, 20, 5, 10, 30) else UsageTotals(), room.usageRecordDao().getTotals())
                    assertEquals(if (precreated) 42L else 0L, room.usageRecordDao().observeLatestId().first())
                    assertEquals(0, db.query("PRAGMA foreign_key_list(usage_record)").use { row -> row.count })
                    db.query("PRAGMA foreign_key_check").use { row -> assertFalse(row.moveToFirst()) }
                    db.query("PRAGMA integrity_check").use { row ->
                        assertTrue(row.moveToFirst()); assertEquals("ok", row.getString(0))
                    }
                } finally { room.close() }
            }
            val room = open(name)
            try {
                room.usageRecordDao().insert(UsageRecordEntity(createdAt = 2000, source = "TITLE"))
                assertEquals(if (precreated) 100L else 1L, room.usageRecordDao().observeLatestId().first())
            } finally { room.close() }
        } finally { template?.close(); context.deleteDatabase(name) }
    }

    private fun open(name: String) = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*APP_DATABASE_MIGRATIONS)
        .allowMainThreadQueries().build()

    private fun seed39(name: String, extraSql: List<String>) {
        val context = RuntimeEnvironment.getApplication()
        val schemaPath = "schemas/me.rerere.rikkahub.data.db.AppDatabase/39.json"
        val file = checkNotNull(listOf(File(schemaPath), File("app/$schemaPath")).firstOrNull { it.isFile })
        val schema = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
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
            db.execSQL("INSERT INTO favorites VALUES('favorite','message','ref','{}','{}',NULL,1,2)")
            db.execSQL("INSERT INTO serein_raw_revision VALUES('revision','assistant','chat','message',1,'assistant','keep original','2026-10-25T00:00:00Z',NULL,1,'live','recorded')")
            db.execSQL("INSERT INTO serein_raw_head VALUES('assistant','chat','node','message','revision',0,1,1)")
            db.execSQL("INSERT INTO serein_upload VALUES('upload','assistant','chat','node','message','destination','hash','keep payload','pending','',1)")
            db.execSQL("CREATE TABLE message_fts (text TEXT)")
            db.execSQL("INSERT INTO message_fts VALUES('keep search')")
            extraSql.forEach(db::execSQL)
            db.version = 39
        }
    }
}
