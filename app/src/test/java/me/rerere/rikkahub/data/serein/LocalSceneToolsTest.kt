package me.rerere.rikkahub.data.serein

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalSceneToolsTest {
    @Test fun `default off live revocation owner binding and all five operations`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = LocalSceneRepository(db)
            val assistant = Assistant()
            assertFalse(assistant.enableLocalSceneTools)
            assertTrue(buildLocalSceneTools(assistant, repo) { true }.isEmpty())
            var enabled = true
            val tools = buildLocalSceneTools(assistant.copy(enableLocalSceneTools = true), repo) { enabled }.associateBy { it.name }
            assertEquals(5, tools.size)
            suspend fun call(name: String, json: String): JsonElement = Json.parseToJsonElement(
                (tools.getValue("local_scene_$name").execute(Json.parseToJsonElement(json)).single() as UIMessagePart.Text).text,
            )
            val save = """{"operation_id":"stable","title":"茶","body":"喜欢清淡的茶","cues":"口味"}"""
            val saved = call("save", save).jsonObject
            assertEquals(saved, call("save", save))
            assertEquals("authored_memory_not_verbatim", saved.getValue("source_kind").jsonPrimitive.content)
            assertEquals(false, saved.getValue("automatic_recall").jsonPrimitive.boolean)
            val id = saved.getValue("id").jsonPrimitive.content
            assertEquals(saved, call("read", """{"id":"$id"}"""))
            assertEquals(1, call("search", """{"query":"口味"}""").jsonObject.getValue("items").jsonArray.size)
            assertTrue(runCatching { call("search", """{"query":""}""") }.isFailure)
            assertTrue(runCatching { call("read", """{"id":"$id","assistantId":"other"}""") }.isFailure)
            val foreign = repo.create("other", "foreign", "Private", "Other assistant")
            assertEquals(JsonNull, call("read", """{"id":"${foreign.id}"}"""))
            val updated = call("edit", """{"id":"$id","expected_revision":1,"title":"茶","body":"喜欢清淡的绿茶","cues":"口味"}""").jsonObject
            assertEquals(2, updated.getValue("revision").jsonPrimitive.int)
            assertTrue(runCatching { call("delete", """{"id":"$id","expected_revision":"2"}""") }.isFailure)
            enabled = false
            assertTrue(runCatching { call("save", save) }.isFailure)
            assertTrue(runCatching { call("read", """{"id":"$id"}""") }.isFailure)
            enabled = true
            assertTrue(call("delete", """{"id":"$id","expected_revision":2}""").jsonObject.getValue("deleted").jsonPrimitive.boolean)
            assertEquals(JsonNull, call("read", """{"id":"$id"}"""))
            assertEquals(foreign, repo.read("other", foreign.id))
        } finally { db.close() }
    }
}
