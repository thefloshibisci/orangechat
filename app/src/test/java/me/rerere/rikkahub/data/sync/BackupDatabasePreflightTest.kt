package me.rerere.rikkahub.data.sync

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class BackupDatabasePreflightTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val factory = FrameworkSQLiteOpenHelperFactory()

    private fun fixture(file: File, missingTitle: Boolean = false) {
        file.parentFile!!.mkdirs()
        val schema = javaClass.getResourceAsStream("/db/uncompressed-30.json")!!.bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
        }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                var sql = entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                if (missingTitle && table == "ConversationEntity") sql = sql.replace("`title` TEXT NOT NULL, ", "")
                if (table == "workflow_runs") sql = sql.replace(", `outputSummary` TEXT", "").replace(", `stepsJson` TEXT", "")
                db.execSQL(sql)
                entity["indices"]?.jsonArray?.forEach {
                    db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = 32
            if (!missingTitle) db.execSQL(
                "INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at) VALUES ('backup','original title','[]',1,2)"
            )
        }
    }

    @Test fun acceptsFork32() {
        val file = File(context.cacheDir, "p-${Uuid.random().toString().take(6)}")
        fixture(file)
        preflightBackupDatabase(context, file, factory)
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath).build()
        try {
            val db = reopened.openHelper.writableDatabase
            assertEquals(43, db.version)
            db.query("SELECT COUNT(*) FROM usage_record").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            db.query("SELECT title,compression_history,compression_summaries,conversation_config FROM ConversationEntity WHERE id='backup'").use {
                assertTrue(it.moveToFirst())
                assertEquals("original title", it.getString(0))
                assertEquals("[]", it.getString(1))
                assertEquals("[]", it.getString(2))
                assertEquals("", it.getString(3))
            }
        } finally { reopened.close(); file.delete() }
    }

    @Test fun rejectsUnknownSchemaBeforeCommit() {
        val root = File(context.cacheDir, "r-${Uuid.random().toString().take(6)}").apply { mkdirs() }
        val live = File(root, "databases/rikka_hub").apply { parentFile!!.mkdirs(); writeText("keep original db") }
        val prefs = File(root, "files/settings").apply { parentFile!!.mkdirs(); writeText("keep original settings") }
        val transaction = BackupRestoreTransaction(root)
        transaction.begin()
        val staged = transaction.stagePath("databases/rikka_hub")
        fixture(staged, missingTitle = true)
        val error = runCatching { preflightBackupDatabase(context, staged, factory) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("未替换当前数据"))
        assertFalse(transaction.isPrepared)
        assertEquals("keep original db", live.readText())
        assertEquals("keep original settings", prefs.readText())
        SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(32, it.version) }
        transaction.discardUnprepared()
        root.deleteRecursively()
    }

    @Test fun refusesLiveDatabase() {
        val live = context.getDatabasePath("rikka_hub")
        fixture(live)
        try {
            val error = runCatching { preflightBackupDatabase(context, live, factory) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(32, it.version) }
        } finally { context.deleteDatabase("rikka_hub") }
    }
}
