package me.rerere.rikkahub.data.serein

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.sync.awaitChatRecovery
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.TimeUnit

internal class SereinUploadQueue(private val dao: SereinUploadDAO) {
    suspend fun sourceIsCurrent(row: SereinUpload): Boolean {
        if (dao.read(row.id)?.status != "pending" || dao.owner(row.conversationId) != row.assistantId) return false
        val node = dao.node(row.nodeId)?.takeIf { it.conversationId == row.conversationId } ?: return false
        val message = runCatching { JsonInstant.decodeFromString<List<UIMessage>>(node.messages)
            .getOrNull(node.selectIndex) }.getOrNull() ?: return false
        return message.id.toString() == row.messageId && message.uploadText()?.let(::hookHash) == row.sourceHash
    }

    suspend fun drain(config: SereinHookConfig, isCurrent: () -> Boolean,
        send: suspend (SereinUpload) -> UploadResult): Boolean {
        repeat(24) {
            if (!isCurrent()) return false
            val row = dao.next(config.uploadDestination()) ?: return false
            if (!sourceIsCurrent(row)) {
                dao.finish(row.id, "skipped", "source_changed", clearBody = true)
            } else {
                val result = send(row) // No Room write transaction around network IO.
                when (result.status) {
                    "sent" -> dao.finish(row.id, "sent", clearBody = true)
                    "blocked" -> dao.finish(row.id, "blocked", result.error, clearBody = true)
                    else -> {
                        dao.finish(row.id, "pending", result.error)
                        return true
                    }
                }
            }
        }
        return isCurrent() && dao.next(config.uploadDestination()) != null
    }
}

object SereinUploadScheduler : KoinComponent {
    private const val NOW = "serein-upload-now"
    private const val PERIODIC = "serein-upload-recovery"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true).build()

    fun runNow(context: Context, restart: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SereinUploadWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    /** One indexed count observer while opted in; no per-button or per-stream upload task. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(context: Context) = get<AppScope>().launch(Dispatchers.IO) {
        get<SettingsStore>().settingsFlow.filter { !it.init }.map { it.sereinHook }.distinctUntilChanged()
            .collectLatest { config ->
                val manager = WorkManager.getInstance(context)
                if (!config.uploadsFor(config.assistantId)) {
                    manager.cancelUniqueWork(NOW); manager.cancelUniqueWork(PERIODIC)
                } else {
                    // Periodic safety pass covers process death and enqueue-vs-worker-finish races.
                    manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<SereinUploadWorker>(15, TimeUnit.MINUTES)
                            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                            .build())
                    get<AppDatabase>().sereinUploadDao().counts(config.uploadDestination())
                        .map { it.pending }.distinctUntilChanged().collect { if (it > 0) runNow(context) }
                }
            }
    }
}

class SereinUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    companion object { private val mutex = Mutex() }

    override suspend fun doWork(): Result = mutex.withLock {
        applicationContext.awaitChatRecovery()
        val settings = get<SettingsStore>()
        val config = settings.settingsFlow.first { !it.init }.sereinHook
        if (!config.uploadsFor(config.assistantId)) return@withLock Result.success()
        fun current() = settings.settingsFlow.value.sereinHook.let {
            it.uploadsFor(config.assistantId) && it.uploadDestination() == config.uploadDestination() &&
                it.gatewayKey == config.gatewayKey && it.uploadStartedAt == config.uploadStartedAt
        }
        try {
            val more = coroutineScope {
                val upload = async {
                    SereinUploadQueue(get<AppDatabase>().sereinUploadDao()).drain(config, ::current) {
                        get<SereinUploadClient>().send(config, it)
                    }
                }
                val changed = launch { settings.settingsFlow.first { !current() }; upload.cancel() }
                try { upload.await() } finally { changed.cancel() }
            }
            if (more) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            if (!current() && currentCoroutineContext().isActive) Result.success() else throw e
        } catch (_: Exception) { Result.retry() }
    }
}
