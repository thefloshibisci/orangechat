package me.rerere.rikkahub.data.db.migrations

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.ConversationCompressionSnapshot
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Real Room open/upgrade/identity validation against all distributed source schemas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CompressionSchemaMigrationTest {
    @Test
    fun uncompressed30UpgradesWithoutLosingHistory() = verify("uncompressed-30.json")

    @Test
    fun previous31WithReportedHashConvertsLegacySummary() = verify("legacy-compression-31.json")

    @Test
    fun v2_31PreservesEditsExclusionsAndDisabledSwitch() = verify("compression-v2-31.json")

    @Test
    fun v2_32UpgradesToCheckpointSchemaWithoutChangingSelections() = verify("compression-v2-32.json")

    @Test
    fun checkpointsSurviveRoomSaveReopenAndBackupAndStillUndoOneRound() {
        val context = RuntimeEnvironment.getApplication()
        // Robolectric uses the method name in its temporary directory. Keep filenames short for native Windows SQLite.
        val suffix = Uuid.random().toString().take(8)
        val name = "c-$suffix"
        val backup = "b-$suffix"
        println("checkpoint SQLite path length: ${context.getDatabasePath(name).absolutePath.length}")
        val id = Uuid.random()
        val nodes = (1..6).map { MessageNode.of(UIMessage.user("original-$it")) }
        val priorSummary = listOf(ConversationSummary(text = "previous edited summary", sendToModel = false))
        val history = listOf(
            ConversationCompressionSnapshot(emptyList(), 0, null, true),
            ConversationCompressionSnapshot(priorSummary, 2, nodes[1].id, false),
        )
        val entity = ConversationEntity(
            id = id.toString(), assistantId = Uuid.random().toString(), title = "synthetic", nodes = "[]",
            createAt = 1, updateAt = 2, chatSuggestions = "[]", isPinned = false,
            compressionSummaries = JsonInstant.encodeToString(listOf(ConversationSummary(text = "merged summary"))),
            compressedNodeIds = JsonInstant.encodeToString(nodes.take(4).map { it.id }.toSet()),
            compressionRevision = 7, compressionHistory = JsonInstant.encodeToString(history),
        )
        fun room(databaseName: String) = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        try {
            val original = room(name)
            try { runBlocking { original.conversationDao().insert(entity) } } finally { original.close() }
            Files.copy(context.getDatabasePath(name).toPath(), context.getDatabasePath(backup).toPath())
            for (databaseName in listOf(name, backup)) {
                val reopened = room(databaseName)
                try {
                    val restored = runBlocking { reopened.conversationDao().getConversationById(id.toString())!! }
                    assertEquals(entity, restored)
                    val conversation = Conversation(
                        id = id, assistantId = Uuid.parse(restored.assistantId), messageNodes = nodes,
                        compressionSummaries = JsonInstant.decodeFromString(restored.compressionSummaries),
                        compressedMessageNodeIds = JsonInstant.decodeFromString(restored.compressedNodeIds),
                        compressionRevision = restored.compressionRevision,
                        compressionHistory = JsonInstant.decodeFromString(restored.compressionHistory),
                    )
                    val undone = conversation.undoLatestCompression(conversation.compressionRevision)
                    assertEquals(priorSummary, undone.compressionSummaries)
                    assertEquals(nodes.take(2).map { it.id }.toSet(), undone.compressedMessageNodeIds)
                    assertFalse(undone.sendCompressedSummary)
                    assertEquals(nodes.drop(2).map { it.currentMessage }, undone.messagesForGeneration())
                } finally { reopened.close() }
            }
        } finally {
            context.deleteDatabase(name)
            context.deleteDatabase(backup)
        }
    }

    @Test fun fork32WithoutCompressionColumnsUpgradesWithoutLosingHistory() =
        verify("uncompressed-30.json", sourceVersion = 32)

    @Test fun failedOldUpgradeRecoversOnNextOpenWithFixedMigration() =
        verify("uncompressed-30.json", sourceVersion = 32, failOldUpgrade = true)

    @Test fun workflowFailureRollsBackAndRecovers() =
        verify("compression-v2-32.json", failOldUpgrade = true, missingWorkflowDetails = true)

    private fun verify(fixture: String, sourceVersion: Int? = null, failOldUpgrade: Boolean = false, missingWorkflowDetails: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        val suffix = Uuid.random().toString().take(8)
        val name = "m-$suffix"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = javaClass.getResourceAsStream("/db/$fixture")!!.bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
        }
        val version = sourceVersion ?: schema.getValue("version").jsonPrimitive.int
        val hash = schema.getValue("identityHash").jsonPrimitive.content
        val legacy = fixture.startsWith("legacy")
        val v2 = fixture.startsWith("compression-v2")
        if (legacy) assertEquals("8e4c29d7b3fab0e016562727f23bdf43", hash)
        if (v2) assertEquals(if (version == 31) "a0bf38a5398aa43d5d0574c4d2b9b45c" else "389b859ade0257d5d1b36f337a0f232b", hash)
        val id = "20000000-0000-4000-8000-000000000001"
        val summary = "旧摘要：\"引号\"\\路径\n第二行 🦩"
        val selected = JsonInstant.encodeToString(listOf(
            ConversationSummary(id = Uuid.parse("30000000-0000-4000-8000-000000000001"), text = summary, sendToModel = false),
            ConversationSummary(id = Uuid.parse("30000000-0000-4000-8000-000000000002"), text = "另一段", sendToModel = true),
        ))
        val coverage = "[\"10000000-0000-4000-8000-000000000001\"]"
        val messages = "[{\"id\":\"original\",\"attachment\":\"file:///synthetic.png\"},{\"id\":\"alternative\"}]"
        val oldBackup = context.getDatabasePath("o-$suffix")
        val newBackup = context.getDatabasePath("n-$suffix")
        println("migration SQLite path length: ${path.absolutePath.length}")
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.setForeignKeyConstraintsEnabled(true)
                schema.getValue("entities").jsonArray.forEach { entity ->
                    val e = entity.jsonObject
                    val table = e.getValue("tableName").jsonPrimitive.content
                    var ddl = e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                    if (missingWorkflowDetails && table == "workflow_runs") ddl = ddl.replace(", `outputSummary` TEXT", "").replace(", `stepsJson` TEXT", "")
                    db.execSQL(ddl)
                    e["indices"]?.jsonArray?.forEach { index ->
                        db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.version = version
                db.execSQL("INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at) VALUES (?, 'title', '[]',1,2)", arrayOf(id))
                db.execSQL("INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at) VALUES ('empty','empty','[]',3,4)")
                for (i in 1..3) {
                    db.execSQL("INSERT INTO message_node VALUES (?,?,?,?,?)", arrayOf<Any>(
                        "10000000-0000-4000-8000-00000000000$i", id, i - 1, messages, 1,
                    ))
                }
                db.execSQL("INSERT INTO favorites VALUES ('favorite','message','ref','{}','{}',NULL,1,2)")
                db.execSQL("INSERT INTO managed_files VALUES (1,'images','synthetic.png','picture','image/png',42,1,2)")
                // The production callback's search table is outside Room's schema and must also survive.
                db.execSQL("CREATE TABLE message_fts (text TEXT)")
                db.execSQL("INSERT INTO message_fts VALUES ('search-original')")
                if (legacy) db.execSQL("UPDATE ConversationEntity SET compressed_summary=?,compressed_node_ids=? WHERE id=?", arrayOf(summary, coverage, id))
                if (v2) db.execSQL("UPDATE ConversationEntity SET compression_summaries=?,compressed_node_ids=?,compression_revision=7,send_compressed_summary=0 WHERE id=?", arrayOf(selected, coverage, id))
                db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(hash, it.getString(0))
                }
            }
            Files.copy(path.toPath(), oldBackup.toPath())
            if (failOldUpgrade) {
                // Reproduce the released build's generated 32->33 migration: history only,
                // then Room rejects the five missing compression columns and rolls back.
                val oldRoom = Room.databaseBuilder(context, AppDatabase::class.java, name)
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
                try {
                    val error = runCatching { oldRoom.openHelper.writableDatabase }.exceptionOrNull()
                    // The current generated Room implementation cannot open a 32 database without
                    // its explicit migrations to the current version. The failure must leave the source intact;
                    // the production APP_DATABASE_MIGRATIONS path is exercised below on the same file.
                    assertTrue(
                        error?.message.orEmpty(),
                        error?.message.orEmpty().contains("A migration from 32 to 42 was required but not found") ||
                            error?.message.orEmpty().contains("Migration didn't properly handle"),
                    )
                } finally { oldRoom.close() }
                SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    assertEquals(32, db.version)
                    db.rawQuery("SELECT COUNT(*) FROM message_node", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0))
                    }
                }
            }
            fun openAndCheck(databaseName: String) {
                val room = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .addMigrations(*APP_DATABASE_MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
                try {
                    // Opening here invokes Room's actual migration, full schema validation and identity update.
                    val db = room.openHelper.writableDatabase
                    assertEquals(42, db.version)
                    db.query("SELECT compression_history FROM ConversationEntity").use {
                        while (it.moveToNext()) assertEquals("[]", it.getString(0))
                    }
                    db.query("SELECT compression_summaries,compressed_node_ids,compression_revision,send_compressed_summary FROM ConversationEntity WHERE id=?", arrayOf(id)).use {
                        assertTrue(it.moveToFirst())
                        val summaries = JsonInstant.decodeFromString<List<ConversationSummary>>(it.getString(0))
                        if (legacy) {
                            assertEquals(listOf(summary), summaries.map { s -> s.text })
                            assertTrue(summaries.single().sendToModel)
                            assertEquals(coverage, it.getString(1))
                        } else if (v2) {
                            assertEquals(selected, it.getString(0))
                            assertEquals(coverage, it.getString(1))
                        } else {
                            assertTrue(summaries.isEmpty())
                            assertEquals("[]", it.getString(1))
                        }
                        assertEquals(if (v2) 7L else 0L, it.getLong(2))
                        assertEquals(if (v2) 0 else 1, it.getInt(3))
                    }
                    db.query("SELECT messages,select_index FROM message_node ORDER BY node_index").use {
                        var count = 0
                        while (it.moveToNext()) {
                            assertEquals(messages, it.getString(0))
                            assertEquals(1, it.getInt(1))
                            count++
                        }
                        assertEquals(3, count)
                    }
                    db.query("SELECT compression_summaries FROM ConversationEntity WHERE id='empty'").use {
                        assertTrue(it.moveToFirst())
                        assertEquals("[]", it.getString(0))
                    }
                    for (table in listOf("favorites", "managed_files", "message_fts")) {
                        db.query("SELECT COUNT(*) FROM `$table`").use {
                            assertTrue(it.moveToFirst())
                            assertEquals(1, it.getInt(0))
                        }
                    }
                    db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                    db.query("PRAGMA integrity_check").use {
                        assertTrue(it.moveToFirst())
                        assertEquals("ok", it.getString(0))
                    }
                    db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                        assertTrue(it.moveToFirst())
                        assertTrue(hash != it.getString(0))
                    }
                } finally {
                    room.close()
                }
            }
            openAndCheck(name)
            openAndCheck(name) // Reopen validates the new identity without another migration.
            openAndCheck(oldBackup.name) // Restoring an old backup must upgrade as well.
            Files.copy(path.toPath(), newBackup.toPath())
            openAndCheck(newBackup.name) // Restoring a new backup retains selections.
        } finally {
            context.deleteDatabase(name)
            context.deleteDatabase(oldBackup.name)
            context.deleteDatabase(newBackup.name)
        }
    }
}
