package me.rerere.rikkahub.data.model

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelCapabilityMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelCapabilityCatalog
import me.rerere.ai.registry.ModelRegistry
import me.rerere.common.http.await
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.codex.mergeCodexModelMetadata
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CapabilitySyncState(
    val refreshing: Boolean = false,
    val lastUpdatedAt: Long? = null,
    val error: String? = null,
)

/** Public catalog request never includes provider keys, custom headers, or saved model IDs. */
class ModelCapabilitySync(
    context: Context,
    private val settingsStore: SettingsStore,
    private val fetchCatalog: (suspend () -> String)? = null,
    cacheFile: File = File(context.filesDir, "model-capabilities-v1.json"),
) {
    private val cache = AtomicFile(cacheFile)
    private val started = AtomicBoolean()
    private val refreshMutex = Mutex()
    private val stateMutable = MutableStateFlow(CapabilitySyncState())
    val state = stateMutable.asStateFlow()
    @Volatile private var catalog: ModelCapabilityCatalog? = null
    private var lastAttemptAt = 0L
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val text = cache.openRead().use { stream ->
                    check(stream.channel.size() in 1..MAX_BYTES) { "Invalid catalog cache size" }
                    stream.bufferedReader().readText()
                }
                catalog = ModelCapabilityCatalog.parse(text)
                stateMutable.value = CapabilitySyncState(lastUpdatedAt = cache.baseFile.lastModified())
            }
            launch {
                settingsStore.settingsFlowRaw.map { it.providers }.distinctUntilChanged().collect {
                    try {
                        applyCatalog()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        stateMutable.value = stateMutable.value.copy(error = "能力资料暂时无法保存，继续使用现有设置。")
                    }
                }
            }
            while (isActive) {
                refresh()
                delay(RETRY_INTERVAL)
            }
        }
    }

    /** Enabling automatic detection is an explicit reset; offline starts from built-in rules. */
    fun enableAutomatic(provider: ProviderSetting, model: Model): Model {
        if ((model.providerOverwrite ?: provider) is ProviderSetting.Codex) {
            return model.copy(capabilityMode = ModelCapabilityMode.AUTO)
        }
        val builtIn = model.copy(
            inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId),
            outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId),
            abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId),
            capabilityMode = ModelCapabilityMode.AUTO,
            capabilitySource = "builtin",
        )
        return catalog?.resolve(provider, builtIn)?.let { (metadata, source) -> metadata.applyTo(builtIn, source) }
            ?: builtIn
    }

    /** Reuses an existing authenticated list response, including models with a native override. */
    suspend fun applyCodexMetadata(discovered: List<Model>) = withContext(Dispatchers.IO) {
        if (discovered.isEmpty()) return@withContext
        fun refresh(provider: ProviderSetting): ProviderSetting {
            val models = mergeCodexModelMetadata(provider, discovered)
            return if (models == provider.models) provider else provider.copyProvider(models = models)
        }
        val snapshot = settingsStore.settingsFlowRaw.first()
        if (snapshot.providers.map(::refresh) == snapshot.providers) return@withContext
        settingsStore.update { current ->
            current.copy(providers = current.providers.map(::refresh))
        }
    }

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            val lastSuccess = stateMutable.value.lastUpdatedAt
            if (!force && lastSuccess != null && now - lastSuccess in 0 until REFRESH_INTERVAL) return@withLock
            if (!force && lastAttemptAt != 0L && now - lastAttemptAt in 0 until RETRY_INTERVAL) return@withLock
            lastAttemptAt = now
            stateMutable.value = stateMutable.value.copy(refreshing = true, error = null)
            try {
                val text = fetchCatalog?.invoke() ?: downloadCatalog()
                val parsed = ModelCapabilityCatalog.parse(text)
                val stream = cache.startWrite()
                try {
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    cache.finishWrite(stream)
                } catch (error: Throwable) {
                    cache.failWrite(stream)
                    throw error
                }
                catalog = parsed
                applyCatalog()
                stateMutable.value = CapabilitySyncState(lastUpdatedAt = System.currentTimeMillis())
            } catch (cancelled: CancellationException) {
                stateMutable.value = stateMutable.value.copy(refreshing = false)
                throw cancelled
            } catch (_: Exception) {
                stateMutable.value = stateMutable.value.copy(
                    refreshing = false,
                    error = "资料更新失败，继续使用已保存的能力和离线资料。",
                )
            }
        }
    }

    private suspend fun downloadCatalog(): String =
        client.newCall(Request.Builder().url(CATALOG_URL).build()).await().use { response ->
            check(response.isSuccessful) { "Catalog HTTP ${response.code}" }
            check(response.body.contentLength() <= MAX_BYTES) { "Catalog too large" }
            val source = response.body.source()
            source.request(MAX_BYTES + 1)
            check(source.buffer.size <= MAX_BYTES) { "Catalog too large" }
            source.readUtf8()
        }

    private suspend fun applyCatalog() {
        val currentCatalog = catalog ?: return
        val snapshot = settingsStore.settingsFlowRaw.first()
        if (snapshot.providers.map(currentCatalog::refresh) == snapshot.providers) return
        settingsStore.update { current ->
            val providers = current.providers.map(currentCatalog::refresh)
            if (providers == current.providers) current else current.copy(providers = providers)
        }
    }

    companion object {
        const val CATALOG_URL = "https://models.dev/api.json"
        private const val MAX_BYTES = 16L * 1024 * 1024
        private const val REFRESH_INTERVAL = 24L * 60 * 60 * 1000
        private const val RETRY_INTERVAL = 60L * 60 * 1000
    }
}
