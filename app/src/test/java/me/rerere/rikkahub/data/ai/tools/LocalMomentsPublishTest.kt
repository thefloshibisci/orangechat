package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.plugin.localdata.AuthorKind
import me.rerere.rikkahub.plugin.localdata.LocalDataActor
import me.rerere.rikkahub.plugin.localdata.LocalDataGrant
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.localdata.LocalPageRequest
import me.rerere.rikkahub.plugin.localdata.LocalPostFilter
import me.rerere.rikkahub.plugin.localtasks.LocalAssistantPolicy
import me.rerere.rikkahub.plugin.localtasks.LocalTaskIds
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettingsStore
import me.rerere.rikkahub.plugin.localtasks.SharedPreferencesLocalTaskStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocalMomentsPublishTest {
    @Test
    fun publishedPostIsVisibleInUserFeedAndRetryKeepsId() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val assistant = Assistant(name = "测试助手")
        val assistantId = assistant.id.toString()
        val pluginId = LocalMomentsContract.PLUGIN_ID
        app.deleteDatabase(me.rerere.rikkahub.plugin.localdata.LocalDataPaths(pluginId, app.filesDir).databaseName)
        app.getSharedPreferences(LocalMomentsTools.HOST_GRANT_PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
        app.getSharedPreferences(LocalTaskIds.storeName(pluginId), Context.MODE_PRIVATE)
            .edit().clear().commit()
        val capabilities = setOf("localdata.read", "localdata.write", "localdata.publish")
        val manifest = PluginManifest(id = pluginId, name = "朋友圈", description = "", version = "1.0.0",
            author = "OrangeChat", icon = "", entry = "main.js", permissions = capabilities.toList())
        val plugin = PluginInfo(manifest, app.filesDir, isEnabled = true)
        val grants = app.getSharedPreferences(LocalMomentsTools.HOST_GRANT_PREFERENCES, Context.MODE_PRIVATE)
        grants.edit().putStringSet(pluginId, capabilities).commit()
        val settings = LocalTaskSettings(participants = listOf(assistantId),
            assistantPolicies = mapOf(assistantId to LocalAssistantPolicy.AUTO_PUBLISH))
        val taskSettings = LocalTaskSettingsStore(SharedPreferencesLocalTaskStore(app, LocalTaskIds.storeName(pluginId)))
        taskSettings.set(settings)
        val tools = LocalMomentsTools(app, MutableStateFlow(Settings(assistants = listOf(assistant))),
            { plugin.takeIf { id -> id.manifest.id == it } }, { app.cacheDir })
        val turn = ToolInvocationContext(callerAssistantId = assistantId, callerConversationId = "chat")
        fun publisher(context: ToolInvocationContext = turn, anchor: String? = "turn") =
            tools.build(context, anchorUserId = anchor).singleOrNull { it.name == LocalMomentsContract.TOOL_PUBLISH }
        suspend fun publish(tool: Tool, body: String): kotlinx.serialization.json.JsonObject {
            val result = tool.execute(buildJsonObject { put("body", body) }).single() as UIMessagePart.Text
            return Json.parseToJsonElement(result.text).jsonObject
        }

        assertNull(publisher(turn.copy(isHeadless = true)))
        assertNull(publisher(anchor = null))
        val tool = requireNotNull(publisher())
        val postId = publish(tool, "春日来信").getValue("postId").jsonPrimitive.content
        assertEquals(postId, publish(requireNotNull(publisher()), "春日来信").getValue("postId").jsonPrimitive.content)

        taskSettings.set(settings.copy(requireReview = true))
        assertNull(publisher())
        assertEquals("publish_not_granted", publish(tool, "尚待审核").getValue("error").jsonObject["code"]?.jsonPrimitive?.content)
        taskSettings.set(settings)
        grants.edit().putStringSet(pluginId, capabilities - "localdata.write").commit()
        assertNull(publisher())
        assertEquals("publish_not_granted", publish(tool, "已撤权").getValue("error").jsonObject["code"]?.jsonPrimitive?.content)

        LocalDataSession(app, pluginId, LocalDataGrant.all()).use { session ->
            val post = session.store.listPosts(LocalDataActor.User, LocalPostFilter(), LocalPageRequest()).items.single()
            assertEquals(postId, post.id)
            assertEquals("春日来信", post.body)
            assertEquals(AuthorKind.ASSISTANT, post.author.kind)
            assertEquals(assistantId, post.author.id)
        }
    }
}
