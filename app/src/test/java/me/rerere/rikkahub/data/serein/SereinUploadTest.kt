package me.rerere.rikkahub.data.serein

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.sync.preflightBackupDatabase
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SereinUploadTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val config = SereinHookConfig(assistantId = "a", baseUrl = "https://memory.example",
        gatewayKey = "synthetic-key", uploadEnabled = true, uploadStartedAt = "2026-10-25T00:00:00")
    private val now = LocalDateTime.parse("2026-10-25T01:00:00")
    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*APP_DATABASE_MIGRATIONS)
        .allowMainThreadQueries().build()
    private fun seed(db: AppDatabase) = db.openHelper.writableDatabase.execSQL(
        "INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES ('chat','a','test','[]',1,2)")
    private fun node(text: String = "new original") = UIMessage.user(text).copy(createdAt = now).toMessageNode()
    private suspend fun save(db: AppDatabase, node: MessageNode, cfg: SereinHookConfig = config, origin: String = "live") {
        db.withTransaction {
            db.messageNodeDao().insert(MessageNodeEntity(node.id.toString(), "chat", 0,
                JsonInstant.encodeToString(node.messages), node.selectIndex))
            db.sereinUploadDao().capture(cfg, "a", "chat", listOf(0 to node), origin)
        }
    }
    private suspend fun counts(db: AppDatabase) = db.sereinUploadDao().counts(config.uploadDestination()).first()

    @Test fun `queue survives reopen and lost response retries deduplicate without retaining sent body`() = runBlocking {
        val name = "up-${Uuid.random().toString().take(8)}"
        try {
            var db = open(name)
            val node = node()
            seed(db); save(db, node); save(db, node)
            val original = db.sereinUploadDao().next(config.uploadDestination())!!
            assertEquals(1, counts(db).pending)
            db.close()
            // Restoring the current native OrangeChat database must keep its outbox. Importing
            // a supported foreign v38 database still uses the separate normalization path.
            preflightBackupDatabase(context, context.getDatabasePath(name), FrameworkSQLiteOpenHelperFactory())
            db = open(name)
            try {
                assertEquals(original.payload, db.sereinUploadDao().read(original.id)!!.payload)
                val queue = SereinUploadQueue(db.sereinUploadDao())
                assertTrue(queue.drain(config, { true }) { UploadResult("retry", "network_error") })
                assertEquals(original.payload, db.sereinUploadDao().read(original.id)!!.payload)
                assertEquals(1, counts(db).retrying)
                assertEquals("network_error", db.sereinUploadDao().latestError(config.uploadDestination()).first())
                assertNull(db.sereinUploadDao().latestError("other-instance").first())
                db.sereinUploadDao().retry(config.uploadDestination())
                assertEquals(0, counts(db).retrying)
                assertNull(db.sereinUploadDao().latestError(config.uploadDestination()).first())
                val source = Json.parseToJsonElement(original.payload).jsonObject["events"]!!.jsonArray.single().jsonObject["source_event_id"]!!
                val receipt = """{"ok":true,"items":[{"status":"duplicate","id":9,"source":"orangechat","source_event_id":$source}]}"""
                assertFalse(queue.drain(config, { true }) { parseUploadResult(receipt, it.payload) })
                assertEquals("", db.sereinUploadDao().read(original.id)!!.payload)
                save(db, node)
                assertEquals(SereinUploadCounts(sent = 1), counts(db))
                assertNull(db.sereinUploadDao().latestError(config.uploadDestination()).first())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `capture excludes history backfill wrong assistant disabled drafts and injected text`() = runBlocking {
        val name = "up-${Uuid.random().toString().take(8)}"
        val db = open(name)
        try {
            seed(db)
            save(db, node(), config.copy(uploadEnabled = false))
            save(db, node(), config.copy(assistantId = "other"))
            save(db, node(), origin = "backfill")
            save(db, UIMessage.user("old").copy(createdAt = LocalDateTime.parse("2026-10-24T00:00:00")).toMessageNode())
            val assistant = UIMessage.assistant("partial").copy(createdAt = now)
            save(db, assistant.toMessageNode())
            assertEquals(0, counts(db).pending)
            val finished = assistant.copy(finishedAt = now, parts = listOf(UIMessagePart.Text("answer"),
                UIMessagePart.Text("secret recalled context", metadata = JsonObject(mapOf(SEREIN_RECALL_METADATA_KEY to JsonPrimitive(true))))))
            save(db, finished.toMessageNode())
            val payload = db.sereinUploadDao().next(config.uploadDestination())!!.payload
            assertTrue(payload.contains("answer")); assertFalse(payload.contains("secret recalled context"))
            assertNull(UIMessage.user("synthetic").copy(parts = listOf(UIMessagePart.Text("synthetic",
                metadata = JsonObject(mapOf(SYNTHETIC_METADATA_KEY to JsonPrimitive(true)))))).uploadText())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `changed branch source or owner is skipped and destination never reroutes`() = runBlocking {
        val name = "up-${Uuid.random().toString().take(8)}"
        val db = open(name)
        try {
            seed(db)
            val node = node(); save(db, node)
            val row = db.sereinUploadDao().next(config.uploadDestination())!!
            assertEquals(config.uploadDestination(), config.copy(gatewayKey = "rotated", timeoutMs = 5000).uploadDestination())
            var sent = 0
            val queue = SereinUploadQueue(db.sereinUploadDao())
            assertFalse(queue.drain(config.copy(baseUrl = "https://other.example"), { true }) { sent++; UploadResult("sent") })
            assertFalse(queue.drain(config, { false }) { sent++; UploadResult("sent") })
            assertEquals(0, sent); assertEquals(1, counts(db).pending)
            val branch = node.copy(messages = node.messages + UIMessage.user("alternative").copy(createdAt = now), selectIndex = 1)
            save(db, branch, config.copy(uploadEnabled = false))
            assertFalse(queue.drain(config, { true }) { sent++; UploadResult("sent") })
            assertEquals("skipped", db.sereinUploadDao().read(row.id)!!.status)
            assertEquals("", db.sereinUploadDao().read(row.id)!!.payload)
            val changed = node("before edit"); save(db, changed)
            val edited = changed.copy(messages = listOf(changed.currentMessage.copy(parts = listOf(UIMessagePart.Text("after edit")))))
            save(db, edited, config.copy(uploadEnabled = false))
            queue.drain(config, { true }) { sent++; UploadResult("sent") }
            val owner = node("owner change"); save(db, owner)
            db.openHelper.writableDatabase.execSQL("UPDATE ConversationEntity SET assistant_id='other' WHERE id='chat'")
            queue.drain(config, { true }) { sent++; UploadResult("sent") }
            assertEquals(0, sent)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `cancelled upload remains pending and transaction rollback leaves no queue entry`() = runBlocking {
        val name = "up-${Uuid.random().toString().take(8)}"
        val db = open(name)
        try {
            seed(db)
            try { db.withTransaction { save(db, node()); error("synthetic rollback") } } catch (_: IllegalStateException) { }
            assertEquals(0, counts(db).pending)
            save(db, node())
            val entered = CompletableDeferred<Unit>()
            val sending = launch { SereinUploadQueue(db.sereinUploadDao()).drain(config, { true }) {
                entered.complete(Unit); awaitCancellation()
            } }
            entered.await(); sending.cancelAndJoin()
            assertEquals(1, counts(db).pending)
            assertTrue(db.sereinUploadDao().next(config.uploadDestination())!!.payload.isNotBlank())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `only exact successful item acknowledgment clears an upload`() {
        val payload = """{"events":[{"source_event_id":"original"}]}"""
        for (status in listOf("inserted", "duplicate")) {
            val ack = """{"ok":true,"items":[{"source_event_id":"original","source":"orangechat","status":"$status","id":1}]}"""
            assertEquals("sent", parseUploadResult(ack, payload).status)
            assertEquals("retry", parseUploadResult(ack.replace("original", "wrong"), payload).status)
            assertEquals("retry", parseUploadResult(ack.replace("\"id\":1", "\"id\":0"), payload).status)
        }
        assertEquals("retry", parseUploadResult("{}", payload).status)
        assertEquals("blocked", parseUploadResult("""{"ok":true,"items":[{"source_event_id":"original","status":"rejected"}]}""", payload).status)
    }

    @Test fun `HTTP sends frozen payload with bearer and retains recoverable errors`() = runBlocking {
        var code = 200
        var body = """{"ok":true,"items":[{"source_event_id":"original","source":"orangechat","status":"inserted","id":1}]}"""
        val seen = mutableListOf<okhttp3.Request>()
        val client = SereinUploadClient(OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
                .body(body.toResponseBody()).build()
        }.build())
        val payload = """{"events":[{"source_event_id":"original"}]}"""
        val row = SereinUpload("id", "a", "chat", "node", "msg", config.uploadDestination(), "hash", payload)
        assertEquals("sent", client.send(config, row).status)
        val request = seen.single()
        assertEquals("https://memory.example/api/ingest-raw", request.url.toString())
        assertEquals("Bearer synthetic-key", request.header("Authorization"))
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        assertEquals(payload, buffer.readUtf8())
        for (error in listOf(401, 403, 404, 405, 408, 429, 500, 302)) {
            code = error; assertEquals("retry", client.send(config, row).status)
        }
        code = 413; assertEquals("blocked", client.send(config, row).status)
        code = 200; body = "x".repeat(65537)
        assertEquals("invalid_response", client.send(config, row).error)
        assertEquals(11, seen.size)
    }

    @Test fun `v37 staged backup upgrades preserving originals and queue cascades with chat`() = runBlocking {
        val name = "up-${Uuid.random().toString().take(8)}"
        try {
            var db = open(name)
            seed(db)
            val node = node(); save(db, node)
            db.withTransaction { RawArchive(db.rawArchiveDao()).sync("a", "chat", listOf(0 to node)) }
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE serein_upload"); it.version = 37
            }
            preflightBackupDatabase(context, context.getDatabasePath(name), FrameworkSQLiteOpenHelperFactory())
            db = open(name)
            try {
                assertEquals(43, db.openHelper.writableDatabase.version)
                assertEquals("new original", db.rawArchiveDao().search("a", "%", 5).single().text)
                assertEquals(0, counts(db).pending)
                save(db, node)
                assertEquals(1, counts(db).pending)
                db.openHelper.writableDatabase.execSQL("DELETE FROM ConversationEntity WHERE id='chat'")
                assertEquals(0, counts(db).pending)
                assertTrue(db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
