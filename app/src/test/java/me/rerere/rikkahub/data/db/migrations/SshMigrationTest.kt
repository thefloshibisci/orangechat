package me.rerere.rikkahub.data.db.migrations

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.sync.preflightBackupDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SshMigrationTest {
    @Test fun local41KeepsData() = verify(precreated = false, backup = false)
    @Test fun importedSshIsKept() = verify(precreated = true, backup = false)
    @Test fun backupMigratesCopy() = verify(precreated = true, backup = true)

    @Test fun badColumnRollsBack() {
        val context = RuntimeEnvironment.getApplication()
        val name = "sb-${Uuid.random().toString().take(6)}"
        try {
            seed(name, listOf("ALTER TABLE workspaces ADD COLUMN type INTEGER NOT NULL DEFAULT 0"))
            val room = open(name)
            try {
                val failure = runCatching { room.openHelper.writableDatabase }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(failure!!.message.orEmpty().contains("Migration didn't properly handle"))
            } finally { room.close() }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(41, db.version)
                db.rawQuery("SELECT name FROM workspaces", null).use { assertTrue(it.moveToFirst()); assertEquals("keep local", it.getString(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun verify(precreated: Boolean, backup: Boolean) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val suffix = Uuid.random().toString().take(6)
        val originalName = "ss-$suffix"
        val targetName = if (backup) "sr-$suffix" else originalName
        val config = " {\"host\":\"synthetic.invalid\",\"encryptedPassword\":\"enc:v1:opaque\",\"future\":true} "
        try {
            seed(originalName, if (!precreated) emptyList() else listOf(
                "ALTER TABLE workspaces ADD COLUMN type TEXT NOT NULL DEFAULT 'LOCAL'",
                "ALTER TABLE workspaces ADD COLUMN ssh_config TEXT",
                "INSERT INTO workspaces(id,name,root,shell_status,created_at,updated_at,tool_approvals,type,ssh_config) VALUES('remote','keep remote','remote','READY',3,4,'{}','SSH',?)",
            ), config)
            val original = context.getDatabasePath(originalName)
            val oldBytes = original.readBytes()
            if (backup) Files.copy(original.toPath(), context.getDatabasePath(targetName).toPath())
            repeat(2) {
                if (backup) preflightBackupDatabase(context, context.getDatabasePath(targetName), FrameworkSQLiteOpenHelperFactory())
                val room = open(targetName)
                try {
                    val db = room.openHelper.writableDatabase
                    assertEquals(43, db.version)
                    val local = room.workspaceDao().getById("local")!!
                    assertEquals("LOCAL", local.type); assertNull(local.sshConfig)
                    assertEquals("keep local", local.name); assertEquals("local-root", local.root)
                    assertEquals("READY", local.shellStatus); assertEquals("{\"workspace_shell\":false}", local.toolApprovals)
                    assertEquals(2L, local.lastAccessAt)
                    if (precreated) {
                        val remote = room.workspaceDao().getById("remote")!!
                        assertTrue(remote.isSsh()); assertEquals(config, remote.sshConfig)
                    }
                    db.query("SELECT conversation_config,compression_summaries FROM ConversationEntity").use {
                        assertTrue(it.moveToFirst()); assertEquals("keep config", it.getString(0)); assertEquals("keep summary", it.getString(1))
                    }
                    db.query("SELECT messages FROM message_node").use { assertTrue(it.moveToFirst()); assertEquals("keep variants", it.getString(0)) }
                    db.query("SELECT translated_text FROM translation_history").use { assertTrue(it.moveToFirst()); assertEquals("keep translation", it.getString(0)) }
                    assertEquals(1L, room.usageRecordDao().getTotals().requestCount)
                    db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    db.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
                } finally { room.close() }
            }
            if (backup) assertArrayEquals(oldBytes, original.readBytes())
        } finally { context.deleteDatabase(originalName); if (backup) context.deleteDatabase(targetName) }
    }

    private fun open(name: String) = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*APP_DATABASE_MIGRATIONS)
        .allowMainThreadQueries().build()

    private fun seed(name: String, extra: List<String>, config: String = "") {
        val context = RuntimeEnvironment.getApplication()
        val schemaPath = "schemas/me.rerere.rikkahub.data.db.AppDatabase/41.json"
        val file = checkNotNull(listOf(File(schemaPath), File("app/$schemaPath")).firstOrNull { it.isFile })
        val schema = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index -> db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO workspaces VALUES('local','keep local','local-root','READY',1,2,2,'{\"workspace_shell\":false}')")
            db.execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at,conversation_config,compression_summaries) VALUES('chat','title','[]',1,2,'keep config','keep summary')")
            db.execSQL("INSERT INTO message_node VALUES('node','chat',0,'keep variants',0)")
            db.execSQL("INSERT INTO translation_history VALUES('translation','source','keep translation','zh','model',1234)")
            db.execSQL("INSERT INTO usage_record VALUES(42,1234,'CHAT','provider','model','snapshot','assistant','chat',20,5,10,30,1,'SUCCESS')")
            extra.forEach { if ('?' in it) db.execSQL(it, arrayOf(config)) else db.execSQL(it) }
            db.version = 41
        }
    }
}
