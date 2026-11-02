package me.rerere.rikkahub.data.gongdu

import android.app.Application
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.testing.TestAndroidKeyStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.Security
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class GongduRepositoryTest {
    @Test fun configConnectionAndRealToolArgumentsPersistWithoutReplacingOtherSettings() = runBlocking {
        fixture { store, repo, transport ->
            val own = Assistant(name = "自己")
            val other = Assistant(name = "其他")
            store.update { Settings(assistants = listOf(own, other), launchCount = 42) }
            repo.saveConfiguration("https://example.test/mcp", "private-token")
            val id = store.settingsFlowRaw.first().gongdu.serverId!!
            assertFalse(repo.connected.value)
            assertFalse(repo.configuration.value.toString().contains("private-token"))
            repo.connect()
            repo.setAssistantEnabled(own.id, true)
            assertTrue(repo.connected.value)
            val state = store.settingsFlowRaw.first()
            assertEquals(42, state.launchCount)
            assertEquals(other, state.assistants.single { it.id == other.id })
            assertEquals(setOf(id), state.assistants.single { it.id == own.id }.mcpServers)
            assertTrue(state.mcpServers.single().commonOptions.tools.single { it.name == "publish_content" }.needsApproval)
            val feed = repo.search("猫").single()
            assertEquals("猫", transport.calls.last().second["keyword"]?.jsonPrimitive?.content)
            assertEquals("笔记正文", repo.detail(feed).content)
            assertEquals("access", transport.calls.last().second["xsec_token"]?.jsonPrimitive?.content)
            repo.call("publish_content", buildJsonObject { put("title", "待确认") })
            assertEquals(600, transport.lastTimeout)
            repo.saveConfiguration("https://different.test/mcp", "new-token")
            assertTrue(store.settingsFlowRaw.first().mcpServers.single().commonOptions.tools.isEmpty())
            assertFalse(repo.connected.value)
        }
    }
    @Test fun unrelatedOrFailedServerNeverBecomesConnected() = runBlocking {
        fixture { _, repo, transport ->
            repo.saveConfiguration("https://example.test/mcp", "")
            transport.names = emptySet()
            assertTrue(runCatching { repo.connect() }.isFailure)
            assertFalse(repo.connected.value)
            transport.state = McpStatus.Error("offline")
            assertTrue(runCatching { repo.connect() }.isFailure)
            assertFalse(repo.connected.value)
        }
    }
    @Test fun notificationBaselineDedupAndAcknowledgmentAreSavedInRawDatastore() = runBlocking {
        fixture { store, repo, transport ->
            repo.saveConfiguration("https://example.test/mcp", "")
            repo.connect()
            repo.refreshInbox()
            assertTrue(store.settingsFlowRaw.first().gongdu.inbox.events.all { it.acknowledged })
            transport.notificationId = "second"
            repo.refreshInbox()
            repo.refreshInbox()
            val pending = store.settingsFlowRaw.first().gongdu.inbox.events.filterNot { it.acknowledged }
            assertEquals(setOf("mentions:second", "likes:second", "connections:second"), pending.map { it.id }.toSet())
            pending.forEach { repo.acknowledge(it.id) }
            assertTrue(store.settingsFlowRaw.first().gongdu.inbox.events.all { it.acknowledged })
        }
    }
    @Test fun unsupportedNotificationsDoNotPreventBrowsingAndAreNotInvented() = runBlocking {
        fixture { _, repo, transport ->
            transport.names = transport.names - "list_notifications"
            repo.saveConfiguration("https://example.test/mcp", "")
            repo.connect()
            assertFalse(repo.refreshInbox().supported)
            assertEquals("n", repo.feeds().single().id)
        }
    }

    private class FakeTransport(val store: SettingsStore) : GongduTransport {
        var names = setOf("check_login_status", "get_login_qrcode", "list_feeds", "search_feeds", "get_feed_detail", "list_notifications", "publish_content")
        var state: McpStatus = McpStatus.Connected
        var notificationId = "first"
        var lastTimeout = 0
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun connect(config: McpServerConfig): McpStatus {
            store.update { it.copy(mcpServers = it.mcpServers.map { server -> if (server.id == config.id)
                server.clone(commonOptions = server.commonOptions.copy(tools = names.map { name -> McpTool(name = name) })) else server }) }
            return state
        }
        override suspend fun call(serverId: Uuid, name: String, args: JsonObject, timeoutSeconds: Int): List<UIMessagePart> {
            calls += name to args
            lastTimeout = timeoutSeconds
            val text = when (name) {
                "search_feeds", "list_feeds" -> """{"feeds":[{"id":"n","xsecToken":"access","noteCard":{"displayTitle":"猫"}}]}"""
                "get_feed_detail" -> """{"note":{"noteId":"n","title":"猫","desc":"笔记正文"}}"""
                "list_notifications" -> """{"items":[{"comment_id":"$notificationId","content":"新评论"}]}"""
                else -> "已完成"
            }
            return listOf(UIMessagePart.Text(text))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fixture(block: suspend (SettingsStore, GongduRepository, FakeTransport) -> Unit) {
        Dispatchers.setMain(Dispatchers.Default)
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val provider = TestAndroidKeyStore.TestProvider()
        Security.addProvider(provider)
        val scope = AppScope()
        try {
            val store = SettingsStore(RuntimeEnvironment.getApplication(), scope)
            val transport = FakeTransport(store)
            val repo = GongduRepository(store, transport, scope)
            block(store, repo, transport)
        } finally {
            scope.cancel()
            Security.removeProvider(provider.name)
            stopKoin()
            Dispatchers.resetMain()
        }
    }
}
