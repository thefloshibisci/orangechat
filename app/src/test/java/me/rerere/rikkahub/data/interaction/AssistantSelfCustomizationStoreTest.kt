package me.rerere.rikkahub.data.interaction

import android.app.Application
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class AssistantSelfCustomizationStoreTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `tool writes survive raw datastore reload without replacing concurrent manual edits`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val provider = TestAndroidKeyStore.TestProvider()
        Security.addProvider(provider)
        val scope = AppScope().also { it.cancel() }
        try {
            val store = SettingsStore(RuntimeEnvironment.getApplication(), scope)
            val assistant = Assistant(name = "AI", backgroundOpacity = .7f)
            val other = Assistant(name = "other")
            store.update { Settings(assistants = listOf(assistant, other)) }
            val tool = buildAssistantSelfCustomizationTools(assistant) { patch ->
                customizeAssistant(assistant.id, patch, { store.settingsFlowRaw.first() }, {
                    store.update { current -> current.copy(
                        assistants = current.assistants.map { if (it.id == assistant.id) it.copy(systemPrompt = "manual change") else it },
                        launchCount = 123,
                    ) }
                    "file:///upload/real.png"
                }, { edit -> store.update(edit) })
            }.single()
            tool.execute(buildJsonObject {
                put("operation", "update")
                put("pat_action", "碰了碰")
                put("outgoing_pat_suffix", "的手心")
                put("avatar_source", "https://example.test/real.png")
                put("background_source", "https://example.test/real.png")
            })
            // This decodes persisted DataStore bytes instead of inspecting only an in-memory tool snapshot.
            val reloaded = store.settingsFlowRaw.first()
            val own = reloaded.assistants.single { it.id == assistant.id }
            assertEquals("manual change", own.systemPrompt)
            assertEquals("碰了碰", own.userInteractionAction)
            assertEquals("的手心", own.assistantInteractionSuffix)
            assertEquals(Avatar.Image("file:///upload/real.png"), own.avatar)
            assertTrue(own.useAssistantAvatar)
            assertEquals("file:///upload/real.png", own.background)
            assertEquals(.7f, own.backgroundOpacity, 0f)
            assertEquals(other, reloaded.assistants.single { it.id == other.id })
            assertEquals(123, reloaded.launchCount)
        } finally {
            scope.cancel()
            Security.removeProvider(provider.name)
            stopKoin()
            Dispatchers.resetMain()
        }
    }
}
