/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.manager
 
import android.content.Context
import android.net.Uri
import me.rerere.rikkahub.AppScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.security.SecurityAuditRepository
import me.rerere.rikkahub.data.service.DailySummaryService
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginFolder
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import java.io.File
 
/**
 * 插件管理器
 * 统一管理插件的生命周期
 */
class PluginManager(
    private val context: Context,
    private val scanner: PluginScanner,
    private val loader: PluginLoader,
    private val repository: PluginRepository,
    private val appScope: AppScope,
    private val auditRepo: SecurityAuditRepository? = null,
) {
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()
 
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _folders = MutableStateFlow<List<PluginFolder>>(emptyList())
    val folders: StateFlow<List<PluginFolder>> = _folders.asStateFlow()

    /**
     * 首次清单和配置索引就绪；不等待插件脚本执行。
     */
    private val initializationDeferred = CompletableDeferred<Unit>()
    private val lifecycleMutex = Mutex()

    init {
        appScope.launch(Dispatchers.IO) {
            try {
                refreshFolders()
                refreshPlugins()
            } finally {
                initializationDeferred.complete(Unit)
            }
        }
    }

    /**
     * 等待插件清单就绪。具体工具执行由各自运行时排队。
     */
    suspend fun awaitInitialization() {
        initializationDeferred.await()
    }
 
    suspend fun refreshPlugins() {
        lifecycleMutex.withLock {
            refreshPluginsInternal()
        }
    }

    private suspend fun refreshPluginsInternal() {
        _isLoading.value = true
        try {
            val scannedPlugins = withContext(Dispatchers.IO) { scanner.scanPlugins() }
            val savedSettings = repository.exportPluginSettings()
            val pluginsWithConfig = scannedPlugins.map { plugin ->
                val savedConfig = savedSettings.configs[plugin.manifest.id].orEmpty()
                val isEnabled = plugin.isEnabled && (savedSettings.enabled[plugin.manifest.id] ?: true)
                val folderId = savedSettings.assignments[plugin.manifest.id]
                plugin.copy(config = savedConfig, isEnabled = isEnabled, folderId = folderId)
            }
            // 审计：记录完整性校验失败的插件
            pluginsWithConfig.filter { it.loadError?.contains("完整性校验失败") == true }.forEach { plugin ->
                auditRepo?.log(
                    category = "plugin",
                    action = "integrity_failed",
                    target = plugin.manifest.id,
                    detail = "插件 ${plugin.manifest.name} (${plugin.manifest.id}) 完整性校验失败，文件可能被篡改",
                    status = "blocked",
                )
            }
            val activePlugins = pluginsWithConfig.associateBy { it.manifest.id }
            loader.getRuntimeIds()
                .filter { activePlugins[it]?.isEnabled != true }
                .forEach(loader::unloadPlugin)

            _plugins.value = pluginsWithConfig
            pluginsWithConfig.filter { it.isEnabled }.forEach { plugin ->
                val id = plugin.manifest.id
                if (loader.getLoadedPlugin(id) == null && !loader.isLoading(id)) {
                    loadPlugin(plugin)
                }
            }
            DailySummaryService.rescheduleIfEnabled(context)
        } finally {
            _isLoading.value = false
        }
    }
 
    private suspend fun refreshFolders() {
        _folders.value = repository.getFolders().sortedBy { it.sortOrder }
    }

    private fun loadPlugin(plugin: PluginInfo) {
        loader.loadPlugin(plugin) { result ->
            updatePluginState(plugin.manifest.id) {
                it.copy(loadError = result.exceptionOrNull()?.let { error ->
                    error.message ?: error.javaClass.simpleName
                })
            }
            DailySummaryService.rescheduleIfEnabled(context)
        }
    }
 
    /**
     * 预览插件（不解压到插件目录，仅解析 manifest）
     */
    suspend fun previewPlugin(uri: Uri): Result<android.util.Pair<PluginManifest, java.io.File>> {
        return try {
            withContext(Dispatchers.IO) { scanner.previewFromZip(uri) }.map { (manifest, tempDir) ->
                android.util.Pair(manifest, tempDir)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 确认导入插件（在 previewPlugin 后调用）
     */
    suspend fun confirmImport(manifest: PluginManifest, tempDir: java.io.File): Result<PluginInfo> {
        return lifecycleMutex.withLock {
            try {
                val result = withContext(Dispatchers.IO) { scanner.completeImport(manifest, tempDir) }
                result.fold(
                    onSuccess = { pluginInfo ->
                        repository.savePlugin(pluginInfo)
                        loader.unloadPlugin(pluginInfo.manifest.id)
                        refreshPluginsInternal()
                        Result.success(pluginInfo)
                    },
                    onFailure = { error -> Result.failure(error) }
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun importPlugin(uri: Uri): Result<PluginInfo> {
        return lifecycleMutex.withLock {
            try {
                val result = withContext(Dispatchers.IO) { scanner.importFromZip(uri) }
                result.fold(
                    onSuccess = { pluginInfo ->
                        repository.savePlugin(pluginInfo)
                        loader.unloadPlugin(pluginInfo.manifest.id)
                        refreshPluginsInternal()
                        Result.success(pluginInfo)
                    },
                    onFailure = { error -> Result.failure(error) }
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
 
    suspend fun deletePlugin(pluginId: String): Result<Unit> {
        return lifecycleMutex.withLock {
            runCatching {
                loader.unloadPlugin(pluginId)
                withContext(Dispatchers.IO) { scanner.deletePlugin(pluginId).getOrThrow() }
                repository.removePlugin(pluginId)
                repository.setPluginFolder(pluginId, null)
                withContext(Dispatchers.IO) { PluginDataStore(context, pluginId).deleteAll() }
                refreshPluginsInternal()
                Unit
            }
        }
    }
 
    suspend fun togglePlugin(pluginId: String, enabled: Boolean) {
        lifecycleMutex.withLock {
            val plugin = _plugins.value.find { it.manifest.id == pluginId } ?: return@withLock
            if (enabled) {
                val checked = withContext(Dispatchers.IO) { scanner.loadPluginInfo(plugin.directory) }
                if (checked?.isEnabled != true) {
                    loader.unloadPlugin(pluginId)
                    repository.setPluginEnabled(pluginId, false)
                    updatePluginState(pluginId) {
                        it.copy(isEnabled = false, loadError = checked?.loadError ?: "Plugin files are missing")
                    }
                    return@withLock
                }
            }
            repository.setPluginEnabled(pluginId, enabled)
            loader.unloadPlugin(pluginId)
            updatePluginState(pluginId) {
                it.copy(isEnabled = enabled, loadError = null)
            }
            if (enabled) loadPlugin(plugin.copy(isEnabled = true, loadError = null))
            DailySummaryService.rescheduleIfEnabled(context)
        }
    }
 
    suspend fun updatePluginConfig(pluginId: String, config: Map<String, JsonElement>) {
        lifecycleMutex.withLock {
            repository.savePluginConfig(pluginId, config)
            updatePluginState(pluginId) { it.copy(config = config) }
            val plugin = _plugins.value.find { it.manifest.id == pluginId }
            if (plugin?.isEnabled == true) {
                loader.unloadPlugin(pluginId)
                loadPlugin(plugin.copy(config = config))
            }
        }
    }
 
    suspend fun getPluginConfig(pluginId: String): Map<String, JsonElement> {
        return repository.getPluginConfig(pluginId)
    }
 
    fun getPluginsDirectory(): File = scanner.pluginsDir
 
    fun getPlugin(pluginId: String): PluginInfo? = _plugins.value.find { it.manifest.id == pluginId }
 
    suspend fun reloadAllPlugins() {
        lifecycleMutex.withLock {
            loader.unloadAll()
            refreshPluginsInternal()
            Unit
        }
    }
 
    /**
     * 调用插件工具函数（代理到 PluginLoader）
     * 供声明式 UI 的 call_js_function action 使用
     */
    suspend fun callTool(pluginId: String, toolName: String, params: JsonElement): Result<JsonElement> {
        return loader.callTool(pluginId, toolName, params)
    }
 
    suspend fun importPlugin(uri: Uri, folderId: String?): Result<PluginInfo> {
        return lifecycleMutex.withLock {
            try {
                val result = withContext(Dispatchers.IO) { scanner.importFromZip(uri) }
                result.fold(
                    onSuccess = { pluginInfo ->
                        repository.savePlugin(pluginInfo)
                        if (folderId != null) {
                            repository.setPluginFolder(pluginInfo.manifest.id, folderId)
                        }
                        loader.unloadPlugin(pluginInfo.manifest.id)
                        refreshPluginsInternal()
                        Result.success(pluginInfo)
                    },
                    onFailure = { error -> Result.failure(error) }
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun createFolder(name: String): PluginFolder {
        return lifecycleMutex.withLock {
            val folder = repository.addFolder(name)
            refreshFolders()
            folder
        }
    }

    suspend fun renameFolder(folderId: String, newName: String) {
        lifecycleMutex.withLock {
            repository.renameFolder(folderId, newName)
            refreshFolders()
        }
    }

    suspend fun deleteFolder(folderId: String) {
        lifecycleMutex.withLock {
            repository.deleteFolder(folderId)
            refreshFolders()
            refreshPluginsInternal()
            Unit
        }
    }

    suspend fun movePluginToFolder(pluginId: String, folderId: String?) {
        lifecycleMutex.withLock {
            repository.setPluginFolder(pluginId, folderId)
            updatePluginState(pluginId) { it.copy(folderId = folderId) }
        }
    }

    fun getPluginsByFolder(folderId: String?): List<PluginInfo> {
        return _plugins.value.filter { it.folderId == folderId }
    }

    private fun updatePluginState(pluginId: String, transform: (PluginInfo) -> PluginInfo) {
        _plugins.update { plugins ->
            plugins.map { plugin ->
                if (plugin.manifest.id == pluginId) transform(plugin) else plugin
            }
        }
    }
}
