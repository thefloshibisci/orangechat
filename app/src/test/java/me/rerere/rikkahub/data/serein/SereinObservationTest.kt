package me.rerere.rikkahub.data.serein

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
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
class SereinObservationTest {
    private val config = SereinHookConfig(true,"a","https://memory.example","synthetic-key",generation="one")
    private val result = parseHookRecall("""{"ok":true,"injected":false,"additional_context":"SECRET MEMORY BODY",
        "recalled_ids":["scene:test"],"cards":[{"id":"scene:test","title":"Synthetic title","source_kind":"scene","text":"SECRET MEMORY BODY","score":0.91}],
        "debug":{"semantic_recall_debug":{"semantic_recall_router":{"route":"memory","reason":"relevant"}}}}""")
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries().build().also { seed(it) }
    private fun seed(db: AppDatabase) {
        db.openHelper.writableDatabase.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES ('chat','a','synthetic','[]',1,2)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES ('other','b','synthetic','[]',1,2)")
    }

    @Test fun `final observations separate candidates from delivered and never contain private response bodies`() = runBlocking {
        val db=database()
        try {
            val repo=SereinHookRepository(db,SereinHookClient())
            val user=UIMessage.user("Synthetic query")
            repo.prepareWith(config,"a","chat",user,true,{true}) { _,_->result }
            assertNull(db.sereinObservationDao().next(config.uploadDestination()))
            for (state in listOf("completed","failed","interrupted")) {
                repo.report(config,"chat","orangechat:$state",user,result,result.ids,state,123,1793150859000)
                val row=db.sereinObservationDao().next(config.uploadDestination())!!
                val payload=Json.parseToJsonElement(row.payload).jsonObject
                assertEquals(if(state=="completed") result.ids else emptyList<String>(),decodeIds(payload["delivered_ids"].toString()))
                val meta=payload.getValue("observation").jsonObject
                assertEquals("Synthetic title",meta.getValue("candidates").jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)
                assertEquals("memory",meta["route"]!!.jsonPrimitive.content)
                assertFalse(row.payload.contains("SECRET MEMORY BODY"));assertFalse(row.payload.contains("synthetic-key"))
                if(state=="completed") File("build/test-artifacts/serein-observation-wire.json").also { it.parentFile.mkdirs();it.writeText(row.payload) }
                db.sereinObservationDao().remove(row.id)
            }
            repo.report(config,"other","wrong",user,result,result.ids,"completed",1,1)
            repo.report(config.copy(enabled=false),"chat","disabled",user,result,result.ids,"completed",1,1)
            assertNull(db.sereinObservationDao().next(config.uploadDestination()))
        } finally { db.close() }
    }

    @Test fun `outbox retries stable receipt pauses isolates instances and removes only acknowledged row`() = runBlocking {
        val db=database()
        try {
            val dao=db.sereinObservationDao();val repo=SereinHookRepository(db,SereinHookClient())
            repo.report(config,"chat","id",UIMessage.user("q"),result,result.ids,"completed",1,1)
            repo.report(config,"chat","id",UIMessage.user("q"),result,result.ids,"completed",1,1)
            assertEquals(1,dao.pending(config.uploadDestination()).first())
            assertFalse(drainObservations(db,config,{false}) { error("paused") })
            assertFalse(drainObservations(db,config.copy(baseUrl="https://other.example"),{true}) { error("other instance") })
            assertTrue(drainObservations(db,config,{true}) { "network_error" })
            assertEquals("network_error",dao.latestError(config.uploadDestination()).first())
            assertFalse(drainObservations(db,config.copy(gatewayKey="rotated"),{true}) { assertEquals("id",it.id); null })
            assertNull(dao.next(config.uploadDestination()))
            repo.report(config,"chat","delete",UIMessage.user("q"),result,result.ids,"completed",1,1)
            db.openHelper.writableDatabase.execSQL("DELETE FROM ConversationEntity WHERE id='chat'")
            assertNull(dao.next(config.uploadDestination()))
        } finally { db.close() }
    }

    @Test fun `client requires exact receipt IDs delivered IDs and supported metadata ACK`() = runBlocking {
        val row=SereinObservation("id","a","chat",config.uploadDestination(),
            observationPayload("id",config,"chat","q",result,result.ids,"completed",1,1),1)
        var response="""{"status":"recorded","receipt_id":"id","delivered_ids":["scene:test"],"observation_version":1}"""
        var code=200
        val client=SereinObservationClient(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/v1/host/deliveries",chain.request().url.encodedPath)
            assertEquals("Bearer synthetic-key",chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(response.toResponseBody()).build()
        }.build())
        assertNull(client.send(config,row))
        response="""{"status":"recorded","delivered_ids":["scene:test"]}"""
        assertEquals("observation_upgrade_required",client.send(config,row))
        response="""{"status":"recorded","receipt_id":"wrong","delivered_ids":["scene:test"],"observation_version":1}"""
        assertEquals("invalid_response",client.send(config,row))
        code=302; assertEquals("http_302",client.send(config,row))
    }

    @Test fun `v42 migration and process reopen preserve conversation snapshot delivery and outbox`() = runBlocking {
        val context=RuntimeEnvironment.getApplication();val name="ob-${Uuid.random().toString().take(8)}"
        fun open()=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(*APP_DATABASE_MIGRATIONS)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        try {
            var db=open();seed(db)
            db.sereinHookDao().put(SereinHookSnapshot("chat","u","fp","keep","[]","empty",1))
            db.sereinHookDao().record(SereinHookDelivery("chat","r","cfg","[]",2));db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE serein_observation_outbox")
                it.execSQL("ALTER TABLE serein_hook_snapshot RENAME TO snapshot_new")
                it.execSQL("""CREATE TABLE serein_hook_snapshot (
                    conversationId TEXT NOT NULL, userMessageId TEXT NOT NULL, fingerprint TEXT NOT NULL,
                    context TEXT NOT NULL, memoryIds TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL,
                    PRIMARY KEY(conversationId,userMessageId), FOREIGN KEY(conversationId) REFERENCES ConversationEntity(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE)""")
                it.execSQL("INSERT INTO serein_hook_snapshot SELECT conversationId,userMessageId,fingerprint,context,memoryIds,status,createdAt FROM snapshot_new")
                it.execSQL("DROP TABLE snapshot_new");it.version=42
            }
            db=open()
            assertEquals(43,db.openHelper.writableDatabase.version)
            assertEquals("keep",db.sereinHookDao().snapshot("chat","u")!!.context)
            assertEquals("{}",db.sereinHookDao().snapshot("chat","u")!!.diagnostics)
            assertEquals(1,db.sereinHookDao().recent("chat","cfg").size)
            SereinHookRepository(db,SereinHookClient()).report(config,"chat","persist",UIMessage.user("q"),result,result.ids,"completed",1,1)
            db.close();db=open()
            assertEquals("persist",db.sereinObservationDao().next(config.uploadDestination())!!.id)
            assertFalse(db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
            db.close()
        } finally { context.deleteDatabase(name) }
    }
}
