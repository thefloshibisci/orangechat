package me.rerere.rikkahub.data.sync

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import me.rerere.rikkahub.data.db.AppDatabase

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ForeignBackupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val factory = FrameworkSQLiteOpenHelperFactory()

    @Test fun fork38() {
        val file = File(context.cacheDir, "f38")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val schema = javaClass.getResourceAsStream("/db/foreign-v38-schema.json")!!.bufferedReader().use { it.readText() }
            Json.parseToJsonElement(schema).jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES (42,'11d8129bf72f997e35de727852c7d17d')")
            db.execSQL("INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at,context_compaction) VALUES ('c','title','[]',1,2,'foreign summary')")
            db.execSQL("INSERT INTO message_node VALUES ('n','c',0,'[]',0)")
            db.execSQL("INSERT INTO MemoryEntity(id,assistant_id,content,revision) VALUES (4,'a','memory',7)")
            db.execSQL("INSERT INTO workflow_runs(rowId,workflowId,firedAtMs,status,durationMs) VALUES (8,'w',1,'ok',5)")
            db.execSQL("UPDATE sqlite_sequence SET seq=99 WHERE name='MemoryEntity'")
            db.version = 38
        }
        preflightBackupDatabase(context, file, factory)
        open(file) { db ->
            assertEquals(43, db.version)
            assertEquals("", value(db,"SELECT conversation_config FROM ConversationEntity WHERE id='c'"))
            assertEquals("0", value(db,"SELECT COUNT(*) FROM usage_record"))
            assertEquals("foreign summary", value(db,"SELECT context_compaction FROM orangechat_import_v38_ConversationEntity"))
            assertEquals("7", value(db,"SELECT revision FROM orangechat_import_v38_MemoryEntity"))
            assertEquals("1", value(db,"SELECT count(*) FROM message_node"))
            assertEquals("memory", value(db,"SELECT content FROM MemoryEntity"))
            assertEquals("99", value(db,"SELECT seq FROM sqlite_sequence WHERE name='MemoryEntity'"))
            assertEquals("1", value(db,"SELECT count(*) FROM workflow_runs WHERE outputSummary IS NULL AND stepsJson IS NULL"))
        }
        preflightBackupDatabase(context, file, factory) // repeat import/open is safe
    }

    /** Opt-in, local only. No personal backup is bundled with the tests or uploaded. */
    @Test fun actualBackup() {
        val source = System.getenv("ORANGECHAT_FOREIGN_ZIP")
        assumeTrue("No local private fixture supplied", !source.isNullOrEmpty())
        val file = File(context.cacheDir,"real")
        java.util.zip.ZipFile(source!!).use { zip ->
            for ((entry, destination) in listOf("rikka_hub.db" to file, "rikka_hub-wal" to File(file.path + "-wal"))) {
                zip.getEntry(entry)?.let { e -> zip.getInputStream(e).use { input -> destination.outputStream().use { input.copyTo(it) } } }
            }
            // Never include private JSON in failure messages/reports.
            val settingsValid = runCatching {
                val settings = zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().use { it.readText() }
                val restored = me.rerere.rikkahub.utils.JsonInstant.decodeFromString<me.rerere.rikkahub.data.datastore.Settings>(
                    me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator.migrate(settings))
                !restored.init
            }.getOrDefault(false)
            assertTrue("Private settings cannot be decoded", settingsValid)
        }
        val originals = linkedMapOf<String, Pair<List<String>, String>>()
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'message_fts' AND name NOT IN ('room_master_table','android_metadata')",null).use { c ->
                while(c.moveToNext()) {
                    val table = c.getString(0)
                    val cols = db.rawQuery("PRAGMA table_info(`$table`)",null).use { q -> buildList { while(q.moveToNext()) add(q.getString(1)) } }
                    val order = cols.joinToString(",") { "`$it`" }
                    val digest = db.rawQuery("SELECT * FROM `$table` ORDER BY $order",null).use(::digest)
                    originals[table] = cols to digest
                }
            }
        }
        preflightBackupDatabase(context,file,factory)
        repeat(2) {
            open(file) { db ->
                assertEquals(43, db.version)
                originals.forEach { (table,pair) ->
                    val archived = table in setOf("ConversationEntity","MemoryEntity")
                    val checkTable = if(archived) "orangechat_import_v38_$table" else table
                    val select = pair.first.joinToString(",") { "`$it`" }
                    assertEquals("Original data changed in $table",pair.second,db.query("SELECT $select FROM `$checkTable` ORDER BY $select").use(::digest))
                    if(archived) {
                        val activeColumns = db.query("PRAGMA table_info(`$table`)").use { c -> buildSet { while(c.moveToNext()) add(c.getString(1)) } }
                        val common = pair.first.filter { it in activeColumns }.joinToString(",") { "`$it`" }
                        assertEquals(db.query("SELECT $common FROM `$checkTable` ORDER BY $common").use(::digest),db.query("SELECT $common FROM `$table` ORDER BY $common").use(::digest))
                    }
                }
                db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                db.query("SELECT messages,select_index FROM message_node").use { cursor ->
                    while(cursor.moveToNext()) {
                        val result = runCatching {
                            val messages = me.rerere.rikkahub.utils.JsonInstant.decodeFromString<List<me.rerere.ai.ui.UIMessage>>(cursor.getString(0))
                            messages.isNotEmpty() && cursor.getInt(1) in messages.indices
                        }
                        val error = result.exceptionOrNull()
                        if (error != null) {
                            val fields = (error as? kotlinx.serialization.MissingFieldException)?.missingFields.orEmpty()
                            val path = Regex("at path: [^\\r\\n]+").find(error.message.orEmpty())?.value.orEmpty()
                            println("Private decode failure class=${error.javaClass.simpleName}, fields=$fields, $path")
                        }
                        assertTrue("A private message node cannot be decoded", result.getOrDefault(false))
                    }
                }
            }
        }
        println("Actual foreign backup: all original tables/values preserved; Room validated and reopened twice")
    }

    private fun open(file:File, block:(SupportSQLiteDatabase)->Unit) {
        val room=Room.databaseBuilder(context,AppDatabase::class.java,file.absolutePath).openHelperFactory(factory).build()
        try { block(room.openHelper.writableDatabase) } finally { room.close() }
    }
    private fun value(db:SupportSQLiteDatabase,sql:String)=db.query(sql).use { assertTrue(it.moveToFirst());it.getString(0) }
    private fun digest(cursor:android.database.Cursor):String {
        val digest=MessageDigest.getInstance("SHA-256")
        while(cursor.moveToNext()) for(i in 0 until cursor.columnCount) {
            digest.update(cursor.getType(i).toByte())
            val bytes=when(cursor.getType(i)) { android.database.Cursor.FIELD_TYPE_NULL -> byteArrayOf();android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i);else -> cursor.getString(i).toByteArray() }
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
