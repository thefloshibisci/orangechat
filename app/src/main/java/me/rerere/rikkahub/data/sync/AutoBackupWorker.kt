package me.rerere.rikkahub.data.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.work.await
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.AutoBackupConfig
import me.rerere.rikkahub.data.datastore.AutoBackupDestination
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.util.concurrent.TimeUnit

object AutoBackupScheduler : KoinComponent {
    private val scheduleMutex = Mutex()
    private const val PERIODIC = "orangechat-auto-backup"
    private const val IMMEDIATE = "orangechat-auto-backup-now"

    private fun constraints(config: AutoBackupConfig) = Constraints.Builder()
        .setRequiresStorageNotLow(true)
        .setRequiredNetworkType(when {
            config.destination == AutoBackupDestination.LOCAL -> NetworkType.NOT_REQUIRED
            config.wifiOnly -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }).build()

    suspend fun schedule(context: Context, advanceClock: Boolean = false) = scheduleMutex.withLock {
        // Read after acquiring the lock so a completing worker cannot overwrite a newer UI setting.
        val config = get<SettingsStore>().settingsFlowRaw.first().autoBackupConfig
        val manager = WorkManager.getInstance(context)
        if (!config.enabled) {
            manager.cancelUniqueWork(PERIODIC).await()
            manager.cancelUniqueWork(IMMEDIATE).await()
            return@withLock
        }
        val tag = config.scheduleTag()
        if (!advanceClock && manager.getWorkInfosForUniqueWorkFlow(PERIODIC).first().any {
                !it.state.isFinished && tag in it.tags
            }) return@withLock
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(config.intervalHours.coerceIn(1, 8760), TimeUnit.HOURS)
                .addTag(tag)
                .setNextScheduleTimeOverride(nextAutoBackupTime(config))
                .setConstraints(constraints(config)).build()).await()
    }

    fun runNow(context: Context, config: AutoBackupConfig) {
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoBackupWorker>().setConstraints(constraints(config)).build())
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    companion object { private val mutex = Mutex() }

    override suspend fun doWork(): Result = mutex.withLock {
        val store = get<SettingsStore>()
        val settings = store.settingsFlow.first { !it.init }
        val config = settings.autoBackupConfig
        if (!config.enabled) return@withLock Result.success()
        var scheduleNext = false
        val result = try {
            when (config.destination) {
                AutoBackupDestination.LOCAL -> {
                    require(config.directoryUri.isNotBlank()) { "请选择本地备份文件夹" }
                    val zip = get<WebDavSync>().prepareBackupFile(WebDavConfig(), includePlugins = true)
                    try { writeLocalBackup(config.directoryUri.toUri(), zip) } finally { zip.delete() }
                }
                AutoBackupDestination.WEBDAV -> {
                    require(settings.webDavConfig.url.isNotBlank()) { "请先配置 WebDAV" }
                    get<WebDavSync>().backup(settings.webDavConfig)
                }
                AutoBackupDestination.S3 -> {
                    require(settings.s3Config.endpoint.isNotBlank() && settings.s3Config.bucket.isNotBlank()) {
                        "请先配置 S3"
                    }
                    get<S3Sync>().backupToS3(settings.s3Config)
                }
            }
            val now = System.currentTimeMillis()
            store.update { current ->
                current.copy(
                    autoBackupConfig = if (current.autoBackupConfig.scheduleConfig() == config.scheduleConfig())
                        current.autoBackupConfig.copy(lastSuccessTime = now, lastError = "") else current.autoBackupConfig,
                    backupReminderConfig = current.backupReminderConfig.copy(lastBackupTime = now),
                )
            }
            scheduleNext = true
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.update { current ->
                current.copy(autoBackupConfig = if (current.autoBackupConfig.scheduleConfig() == config.scheduleConfig())
                    current.autoBackupConfig.copy(lastError = "备份失败，请检查文件夹权限或远程配置（${e.javaClass.simpleName}）")
                    else current.autoBackupConfig)
            }
            if (runAttemptCount < 3) Result.retry() else {
                scheduleNext = true
                Result.failure()
            }
        }
        if (scheduleNext) {
            try {
                AutoBackupScheduler.schedule(applicationContext, advanceClock = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AutoBackupWorker", "Unable to schedule next backup", e)
            }
        }
        result
    }

    private suspend fun writeLocalBackup(tree: Uri, zip: File) = withContext(Dispatchers.IO) {
        val resolver = applicationContext.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "orangechat_auto_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.zip"
        val partial = checkNotNull(DocumentsContract.createDocument(resolver, root, "application/zip", "$name.partial"))
        var completed = false
        try {
            checkNotNull(resolver.openOutputStream(partial, "w")).use { output ->
                zip.inputStream().use { input -> check(input.copyTo(output) == zip.length()) }
            }
            checkNotNull(DocumentsContract.renameDocument(resolver, partial, name))
            completed = true
        } finally {
            if (!completed) runCatching { DocumentsContract.deleteDocument(resolver, partial) }
        }
        // Retention is best-effort and runs only AFTER the new archive was completely written.
        // Never touch manual exports or other applications' files.
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val backups = mutableListOf<Pair<String, String>>()
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childName = cursor.getString(1)
                    if (childName.matches(Regex("orangechat_auto_[0-9]+_[0-9a-f-]+\\.zip"))) {
                        backups += cursor.getString(0) to childName
                    }
                }
            }
            backups.sortedByDescending { it.second }.drop(7).forEach { (id, _) ->
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
        Unit
    }
}
