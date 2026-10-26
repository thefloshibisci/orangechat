package me.rerere.rikkahub.data.datastore

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.SystemToolOption
import me.rerere.rikkahub.data.ai.tools.SystemTools
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.data.security.SecretCrypto
import me.rerere.rikkahub.data.serein.SereinHookConfig
import me.rerere.rikkahub.testing.TestAndroidKeyStore
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.encodeToString
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
class LegacyExternalMemoryStoreTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `startup and backup restoration erase old connection without reviving it in live settings`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val provider = TestAndroidKeyStore.TestProvider()
        Security.addProvider(provider)
        val activeScope = AppScope()
        try {
            val context = RuntimeEnvironment.getApplication()
            val store = SettingsStore(context, AppScope().also { it.cancel() })
            @Suppress("UNCHECKED_CAST")
            val disk = SettingsStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
                .get(store) as DataStore<Preferences>
            val legacy = ExternalMemory(supabaseUrl = "https://synthetic.invalid", supabaseKey = "synthetic-legacy")
            val assistant = Assistant(name = "kept", externalMemoryIds = setOf(legacy.id), saveLocalRawMessages = true)
            val hook = SereinHookConfig(baseUrl = "https://memory.example", gatewayKey = "synthetic-hook")
            val old = Settings(assistants = listOf(assistant), externalMemories = listOf(legacy), sereinHook = hook,
                compressPrompt = "custom retained")
            disk.edit {
                it[SettingsStore.EXTERNAL_MEMORIES] = JsonInstant.encodeToString(old.externalMemories)
                it[stringPreferencesKey("assistants")] = JsonInstant.encodeToString(old.assistants)
                it[SettingsStore.SEREIN_HOOK] = JsonInstant.encodeToString(hook)
            }
            val live = SettingsStore(context, activeScope)
            withTimeout(10000) { disk.data.first { !it.contains(SettingsStore.EXTERNAL_MEMORIES) } }
            val initial = live.settingsFlowRaw.first()
            assertTrue(initial.externalMemories.isEmpty())
            assertTrue(initial.assistants.all { it.externalMemoryIds.isEmpty() })
            assertEquals(hook, initial.sereinHook)
            val protected = disk.data.first()[stringPreferencesKey("assistants")]!!
            assertTrue(SecretCrypto.isEncrypted(protected))
            assertFalse(SecretCrypto.decrypt(protected, "assistants")!!.contains("externalMemoryIds"))
            live.withRestoreSession { it.apply(old) }
            assertTrue(live.settingsFlow.value.externalMemories.isEmpty())
            assertTrue(live.settingsFlow.value.assistants.all { it.externalMemoryIds.isEmpty() })
            assertEquals("custom retained", live.settingsFlow.value.compressPrompt)
            assertEquals(hook, live.settingsFlow.value.sereinHook)
            assertTrue(live.settingsFlow.value.assistants.single { it.id == assistant.id }.saveLocalRawMessages)
            assertFalse(disk.data.first().contains(SettingsStore.EXTERNAL_MEMORIES))
            // Normalize/persist the built-in assistants before measuring unrelated saves.
            live.update { it.copy(launchCount = 1) }
            val unchangedAssistantCiphertext = disk.data.first()[stringPreferencesKey("assistants")]
            repeat(10) { live.update { current -> current.copy(launchCount = current.launchCount + 1) } }
            assertEquals(unchangedAssistantCiphertext, disk.data.first()[stringPreferencesKey("assistants")])
            assertEquals(11, live.settingsFlow.value.launchCount)
            // Real editor merges must still preserve a concurrently committed setting.
            val editorBase = live.settingsFlow.value
            live.update { it.copy(enableWebSearch = true) }
            live.updateFrom(editorBase, editorBase.copy(compressPrompt = "edited synthetic prompt"))
            assertTrue(live.settingsFlow.value.enableWebSearch)
            assertEquals("edited synthetic prompt", live.settingsFlow.value.compressPrompt)
        } finally {
            activeScope.coroutineContext[Job]?.cancelAndJoin()
            stopKoin()
            Security.removeProvider(provider.name)
            Dispatchers.resetMain()
        }
    }

    @Test fun `stale supabase tool option cannot expose a callable query`() {
        val settings = Settings(externalMemories = listOf(ExternalMemory(supabaseKey = "synthetic")))
        val tools = SystemTools(RuntimeEnvironment.getApplication(), settings).getTools(setOf(SystemToolOption.SupabaseQuery))
        assertTrue(tools.isEmpty())
    }
}
