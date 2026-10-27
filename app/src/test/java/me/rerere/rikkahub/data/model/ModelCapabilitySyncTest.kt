package me.rerere.rikkahub.data.model

import android.app.Application
import android.content.Context
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelCapabilityMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.testing.TestAndroidKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ModelCapabilitySyncTest {
    private val catalog = """{"openai":{"models":{"new-model":{"tool_call":true,"reasoning":true,"modalities":{"input":["text","image"],"output":["text"]}}}}}"""
    private fun auto() = Model(modelId = "new-model", capabilityMode = ModelCapabilityMode.AUTO)

    @Test fun `network refresh updates saved automatic models and failure keeps last valid capabilities`() = runBlocking {
        fixture { context, store, _ ->
            val automatic = auto()
            val manual = auto().copy(capabilityMode = ModelCapabilityMode.MANUAL)
            val provider = ProviderSetting.OpenAI(models = listOf(automatic, manual))
            store.update { it.copy(providers = listOf(provider)) }
            val unrelatedProviders = store.settingsFlowRaw.first().providers.filterNot { it.id == provider.id }
            var fail = false
            val cache = File(context.cacheDir, "test-capability-network.json")
            val sync = ModelCapabilitySync(context, store, {
                if (fail) throw IOException("synthetic offline") else catalog
            }, cache)
            sync.refresh(force = true)
            val saved = store.settingsFlowRaw.first().providers.single { it.id == provider.id }.models
            assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), saved.first().abilities)
            assertEquals(manual, saved.last())
            assertEquals(catalog, cache.readText())
            fail = true
            sync.refresh(force = true)
            assertEquals(saved, store.settingsFlowRaw.first().providers.single { it.id == provider.id }.models)
            assertNotNull(sync.state.value.error)
            assertFalse(sync.state.value.refreshing)
            assertEquals(catalog, cache.readText())
            assertEquals(unrelatedProviders, store.settingsFlowRaw.first().providers.filterNot { it.id == provider.id })
        }
    }

    @Test fun `startup restores stale cache while offline and applies it to later additions`() = runBlocking {
        fixture { context, store, scope ->
            val manual = auto().copy(capabilityMode = ModelCapabilityMode.MANUAL)
            val provider = ProviderSetting.OpenAI(models = listOf(manual))
            store.update { it.copy(providers = listOf(provider)) }
            val cache = File(context.cacheDir, "test-capability-offline.json")
            cache.writeText(catalog)
            assertTrue(cache.setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000))
            val sync = ModelCapabilitySync(context, store, { throw IOException("synthetic offline") }, cache)
            sync.start(scope)
            withTimeout(10000) { sync.state.first { it.error != null } }
            assertEquals(manual, store.settingsFlowRaw.first().providers.single { it.id == provider.id }.models.single())
            store.update { current ->
                current.copy(providers = current.providers.map { savedProvider ->
                    if (savedProvider.id == provider.id) savedProvider.addModel(auto()) else savedProvider
                })
            }
            val saved = withTimeout(10000) {
                store.settingsFlowRaw.first { settings ->
                    settings.providers.single { it.id == provider.id }.models.last().capabilitySource != null
                }
            }
            assertEquals(listOf(Modality.TEXT, Modality.IMAGE), saved.providers.single { it.id == provider.id }.models.last().inputModalities)
            assertEquals(manual, saved.providers.single { it.id == provider.id }.models.first())
        }
    }

    @Test fun `manual changes made during a catalog request win over background refresh`() = runBlocking {
        fixture { context, store, scope ->
            val provider = ProviderSetting.OpenAI(models = listOf(auto()))
            store.update { it.copy(providers = listOf(provider)) }
            val started = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val sync = ModelCapabilitySync(context, store, {
                started.complete(Unit)
                proceed.await()
                catalog
            }, File(context.cacheDir, "test-capability-concurrent.json"))
            val refreshing = scope.async(Dispatchers.IO) { sync.refresh(force = true) }
            withTimeout(10000) { started.await() }
            store.update { current ->
                current.copy(providers = current.providers.map { savedProvider ->
                    if (savedProvider.id != provider.id) savedProvider else savedProvider.copyProvider(
                        models = savedProvider.models.map { model ->
                            model.copy(displayName = "edited while waiting", capabilityMode = ModelCapabilityMode.MANUAL)
                        },
                    )
                })
            }
            proceed.complete(Unit)
            withTimeout(10000) { refreshing.await() }
            val saved = store.settingsFlowRaw.first().providers.single { it.id == provider.id }.models.single()
            assertEquals("edited while waiting", saved.displayName)
            assertEquals(ModelCapabilityMode.MANUAL, saved.capabilityMode)
            assertTrue(saved.abilities.isEmpty())
        }
    }

    @Test fun `automatic reset preserves Codex metadata and uses built in fallback for ordinary models`() = runBlocking {
        fixture { context, store, _ ->
            val sync = ModelCapabilitySync(context, store, { error("must not fetch") })
            val codex = Model(modelId = "provider-only", inputModalities = listOf(Modality.IMAGE),
                abilities = listOf(ModelAbility.REASONING), codexReasoningEfforts = listOf("high"))
            val reset = sync.enableAutomatic(ProviderSetting.Codex(), codex)
            assertEquals(codex.copy(capabilityMode = ModelCapabilityMode.AUTO), reset)
            val ordinary = sync.enableAutomatic(ProviderSetting.OpenAI(), Model(modelId = "gpt-4o"))
            assertEquals(ModelCapabilityMode.AUTO, ordinary.capabilityMode)
            assertTrue(Modality.IMAGE in ordinary.inputModalities)
            assertEquals("builtin", ordinary.capabilitySource)
        }
    }


    @Test fun `existing authenticated list updates saved auto native overrides while preserving manual choices`() = runBlocking {
        fixture { context, store, _ ->
            val automatic = auto()
            val manual = auto().copy(capabilityMode = ModelCapabilityMode.MANUAL)
            val override = auto().copy(providerOverwrite = ProviderSetting.Codex())
            val nativeProvider = ProviderSetting.Codex(models = listOf(automatic, manual))
            val overrideProvider = ProviderSetting.OpenAI(models = listOf(override))
            store.update { it.copy(providers = listOf(nativeProvider, overrideProvider)) }
            val targetIds = setOf(nativeProvider.id, overrideProvider.id)
            val unrelatedProviders = store.settingsFlowRaw.first().providers.filterNot { it.id in targetIds }
            val sync = ModelCapabilitySync(context, store, { error("must not make another account request") })
            val remote = auto().copy(abilities = listOf(ModelAbility.TOOL), codexReasoningEfforts = listOf("high"))
            sync.applyCodexMetadata(listOf(remote))
            val providers = store.settingsFlowRaw.first().providers
            val nativeModels = providers.single { it.id == nativeProvider.id }.models
            val overrideModels = providers.single { it.id == overrideProvider.id }.models
            assertEquals(listOf(ModelAbility.TOOL), nativeModels.first().abilities)
            assertEquals(manual, nativeModels.last())
            assertEquals(listOf("high"), overrideModels.single().codexReasoningEfforts)
            assertEquals(override.id, overrideModels.single().id)
            assertEquals(unrelatedProviders, providers.filterNot { it.id in targetIds })
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fixture(block: suspend (Context, SettingsStore, AppScope) -> Unit) {
        Dispatchers.setMain(Dispatchers.Default)
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val keyStoreProvider = TestAndroidKeyStore.TestProvider()
        Security.addProvider(keyStoreProvider)
        val scope = AppScope()
        try {
            val context = RuntimeEnvironment.getApplication()
            val store = SettingsStore(context, scope)
            withTimeout(10000) { store.settingsFlow.first { !it.init } }
            block(context, store, scope)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            stopKoin()
            Security.removeProvider(keyStoreProvider.name)
            Dispatchers.resetMain()
        }
    }
}
