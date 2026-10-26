package me.rerere.rikkahub.data.serein

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.extraInfoMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SereinHookTest {
    private val config = SereinHookConfig(true, "a", "https://memory.example", "synthetic-key", generation = "one")
    private val recall = HookRecall("虚构记忆", listOf("scene:test"), "ready")
    private fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries().build().also { db ->
            db.openHelper.writableDatabase.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES ('chat','a','synthetic','[]',1,2)")
            db.openHelper.writableDatabase.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES ('other','b','synthetic','[]',1,2)")
        }
    private fun reply() = UIMessage.assistant("synthetic reply").copy(finishedAt = LocalDateTime(2026, 10, 24, 0, 0))

    @Test fun `settings diagnostics filter assistant config and project no recalled body`() = runBlocking {
        val db = database()
        try {
            val dao = db.sereinHookDao()
            assertNull(dao.latestStatus("a", config.fingerprint()).first())
            dao.put(SereinHookSnapshot("chat", "first", config.fingerprint(), "private memory body",
                encodeIds(recall.ids), "ready", 10))
            dao.put(SereinHookSnapshot("other", "second", config.fingerprint(), "other assistant secret",
                "[]", "http_401", 20))
            dao.put(SereinHookSnapshot("chat", "third", "old-config", "old secret", "[]", "timeout", 30))
            val status = dao.latestStatus("a", config.fingerprint()).first()!!
            assertEquals(SereinRecallStatus("ready", encodeIds(recall.ids), 10), status)
            assertFalse(status.toString().contains("private memory body"))
            dao.put(SereinHookSnapshot("chat", "fourth", config.fingerprint(), "", "[]", "http_401", 40))
            assertEquals("http_401", dao.latestStatus("a", config.fingerprint()).first()!!.status)
            dao.clearSnapshots()
            assertNull(dao.latestStatus("a", config.fingerprint()).first())
        } finally { db.close() }
    }

    @Test fun `configuration defaults and strict root endpoint`() {
        assertEquals(3000L, SereinHookConfig().waitMs)
        assertFalse(SereinHookConfig().enabled)
        assertEquals(2000L, config.copy(timeoutMs = 1).waitMs)
        assertEquals(5000L, config.copy(timeoutMs = 9000).waitMs)
        assertTrue(config.usableBy("a")); assertFalse(config.usableBy("b"))
        listOf("http://memory.example", "https://u:p@memory.example", "https://memory.example/v1", "https://memory.example/?key=x").forEach {
            assertNull(config.copy(baseUrl = it).rootUrl())
        }
        assertFalse(config.toString().contains("synthetic-key"))
        assertNotEquals(config.fingerprint(), config.copy(gatewayKey = "rotated").fingerprint())
    }

    @Test fun `HTTP boundary sends only query and cooldown with bearer and rejects errors`() = runBlocking {
        val seen = mutableListOf<okhttp3.Request>()
        var code = 200
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
                .body("""{"ok":true,"injected":false,"additional_context":"虚构记忆","recalled_ids":["scene:test"]}""".toResponseBody())
                .build()
        }.build()
        val client = SereinHookClient(http)
        assertEquals(recall, client.recall(config, "original query", "chat", emptyList()))
        val request = seen.single()
        assertEquals("https://memory.example/api/hook/recall", request.url.toString())
        assertEquals("Bearer synthetic-key", request.header("Authorization"))
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        val json = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals("original query", json.getValue("query").jsonPrimitive.content)
        assertTrue(json.getValue("delivered_ids").jsonArray.isEmpty())
        code = 401
        assertEquals("http_401", client.recall(config, "q", "chat", emptyList()).status)
        code = 302
        assertEquals("http_302", client.recall(config, "q", "chat", emptyList()).status)
        assertEquals(3, seen.size)
    }

    @Test fun `strict response handling never promotes malformed or oversized material`() {
        assertEquals(recall, parseHookRecall("""{"ok":true,"injected":false,"additional_context":"虚构记忆","recalled_ids":["scene:test"]}"""))
        assertEquals("invalid_response", parseHookRecall("""{"ok":true,"injected":true}""").status)
        assertEquals("invalid_response", parseHookRecall("{" ).status)
        val huge = buildJsonObject {
            put("ok", true); put("injected", false); put("additional_context", "a".repeat(12001))
            put("recalled_ids", buildJsonArray { add("scene:test") })
        }
        assertEquals("invalid_response", parseHookRecall(huge.toString()).status)
        assertEquals("empty", parseHookRecall("""{"ok":true,"injected":false,"additional_context":"","recalled_ids":[]}""").status)
    }

    @Test fun `retry resume edit config changes and owner boundaries`() = runBlocking {
        val db = database()
        try {
            val repo = SereinHookRepository(db, SereinHookClient())
            val user = UIMessage.user("hello")
            var calls = 0
            suspend fun fetch(q: String, ids: List<String>): HookRecall { calls++; assertTrue(q.isNotBlank()); return recall }
            assertEquals(recall, repo.prepareWith(config, "a", "chat", user, true, { true }, ::fetch))
            assertEquals(recall, SereinHookRepository(db, SereinHookClient()).prepareWith(config, "a", "chat", user, false, { true }, ::fetch))
            assertEquals(1, calls)
            assertNull(repo.prepareWith(config, "b", "other", user, true, { true }, ::fetch))
            assertNull(repo.prepareWith(config, "a", "other", user, true, { true }, ::fetch))
            assertNull(repo.prepareWith(config, "a", "chat", user, true, { false }, ::fetch))
            val edited = user.copy(parts = listOf(UIMessagePart.Text("edited")))
            assertNull(repo.prepareWith(config, "a", "chat", edited, false, { true }, ::fetch))
            repo.prepareWith(config, "a", "chat", edited, true, { true }, ::fetch)
            repo.prepareWith(config.copy(generation = "new"), "a", "chat", edited, true, { true }, ::fetch)
            assertEquals(3, calls)
        } finally { db.close() }
    }

    @Test fun `concurrent calls share a snapshot and timeout is bounded and cached`() = runBlocking {
        val db = database()
        try {
            val repo = SereinHookRepository(db, SereinHookClient())
            val user = UIMessage.user("hello")
            var calls = 0
            coroutineScope { (1..3).map { async {
                repo.prepareWith(config, "a", "chat", user, true, { true }) { _, _ -> calls++; delay(20); recall }
            } }.awaitAll() }
            assertEquals(1, calls)
            val timeoutUser = UIMessage.user("timeout")
            val start = System.nanoTime()
            val result = repo.prepareWith(config.copy(timeoutMs = 2000), "a", "chat", timeoutUser, true, { true }) { _, _ ->
                delay(10000); error("must be cancelled")
            }
            assertEquals("timeout", result?.status)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
            assertEquals("timeout", repo.prepareWith(config.copy(timeoutMs = 2000), "a", "chat", timeoutUser, true, { true }) { _, _ -> error("must reuse") }?.status)
        } finally { db.close() }
    }

    @Test fun `cancel revocation failure and successful empty cooldown`() = runBlocking {
        val db = database()
        try {
            val repo = SereinHookRepository(db, SereinHookClient())
            val user = UIMessage.user("cancel")
            val started = CompletableDeferred<Unit>()
            val job = launch { repo.prepareWith(config, "a", "chat", user, true, { true }) { _, _ -> started.complete(Unit); awaitCancellation() } }
            started.await(); job.cancelAndJoin()
            assertNull(db.sereinHookDao().snapshot("chat", user.id.toString()))
            var live = true
            assertNull(repo.prepareWith(config, "a", "chat", user, true, { live }) { _, _ -> live = false; recall })
            assertNull(db.sereinHookDao().snapshot("chat", user.id.toString()))
            val failed = repo.prepareWith(config, "a", "chat", user, true, { true }) { _, _ -> throw java.io.IOException("secret must not log") }
            assertEquals("network_error", failed?.status)
            assertEquals(failed, repo.prepareWith(config, "a", "chat", user, true, { true }) { _, _ -> error("no retry") })
            val first = reply()
            repo.acknowledge(config, "chat", first, recall.ids)
            repo.acknowledge(config, "chat", first, recall.ids)
            assertEquals(1, db.sereinHookDao().recent("chat", config.fingerprint()).size)
            repo.acknowledge(config, "chat", UIMessage.assistant("unfinished"), recall.ids)
            assertEquals(1, db.sereinHookDao().recent("chat", config.fingerprint()).size)
            repeat(5) { repo.acknowledge(config, "chat", reply(), emptyList()) }
            assertEquals(5, db.sereinHookDao().recent("chat", config.fingerprint()).size)
            assertTrue(db.sereinHookDao().recent("chat", config.fingerprint()).all { decodeIds(it.memoryIds).isEmpty() })
            db.openHelper.writableDatabase.execSQL("DELETE FROM ConversationEntity WHERE id='chat'")
            assertNull(db.sereinHookDao().snapshot("chat", user.id.toString()))
            assertTrue(db.sereinHookDao().recent("chat", config.fingerprint()).isEmpty())
        } finally { db.close() }
    }

    @Test fun `injection is request only excludes originals and tracks actual presence`() {
        val original = UIMessage.user("real text")
        val history = listOf(original)
        var enabled = true
        val transformer = SereinRecallTransformer(original.id, recall) { enabled }
        assertEquals(emptyList<String>(), transformer.deliveredIds)
        transformer.attach(emptyList())
        assertTrue(transformer.deliveredIds.isEmpty())
        val request = transformer.attach(history)
        assertEquals(recall.ids, transformer.deliveredIds)
        assertEquals("real text", request.single().rawArchiveText())
        assertEquals(1, history.single().parts.size)
        assertEquals(request, transformer.attach(request))
        assertTrue(request.single().toText().contains("serein_live_context"))
        enabled = false
        assertEquals(history, transformer.attach(history))
        val withExtra = original.copy(parts = original.parts + extraInfoMessagePart("private device context"))
        assertEquals("real text", withExtra.rawArchiveText())
    }
}
