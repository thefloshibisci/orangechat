package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.repository.PluginSettingsExport
import java.io.File

/** Shared restore path for S3, WebDAV and local ZIPs. */
internal class BackupArchiveRestorer(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val database: DatabaseBackupCoordinator,
    private val pluginRepository: PluginRepository? = null,
) {
    suspend fun restore(archive: File, restoreFiles: Boolean, restoreDatabase: Boolean, includePlugins: Boolean = false) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                check(!ChatHistoryBackup.pendingFile(context).exists()) { "请先取消待恢复的聊天记录，或重启完成恢复" }
                val transaction = BackupRestoreTransaction(context.dataDir)
                transaction.begin()
                try {
                    val databaseDirectory = context.getDatabasePath("rikka_hub").parentFile!!
                        .relativeTo(context.dataDir).invariantSeparatorsPath
                    val staged = stageBackupArchive(
                        archive, transaction, restoreFiles, restoreDatabase, includePlugins, databaseDirectory,
                    )
                    val settings = staged.settings?.let {
                        json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(it)).let { restored ->
                            check(!restored.init) { "Cannot restore dummy settings" }
                            restored.copy(autoBackupConfig = restored.autoBackupConfig.forRestore())
                        }
                    }
                    val plugins = staged.pluginSettings?.let { json.decodeFromString<PluginSettingsExport>(it) }
                    if (restoreDatabase) database.validateStagedRestore(transaction.stagePath("$databaseDirectory/rikka_hub"))
                    settingsStore.withRestoreSession { settingsSession ->
                        if (plugins != null) {
                            requireNotNull(pluginRepository).withRestoreSession { pluginSession ->
                                commitBackupRestore(
                                    transaction, staged.files, staged.databases, settingsSession, settings, pluginSession, plugins,
                                )
                            }
                        } else {
                            commitBackupRestore<Settings, PluginSettingsExport>(
                                transaction, staged.files, staged.databases, settingsSession, settings, null, null,
                            )
                        }
                    }
                } catch (error: Throwable) {
                    if (!transaction.isPrepared) {
                        runCatching { transaction.discardUnprepared() }.onFailure(error::addSuppressed)
                    }
                    throw error
                }
            }
        }

    companion object {
        internal val restoreMutex = Mutex()
    }
}
