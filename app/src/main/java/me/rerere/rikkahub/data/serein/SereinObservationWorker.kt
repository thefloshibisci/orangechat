package me.rerere.rikkahub.data.serein

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.sync.awaitChatRecovery
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal class SereinObservationClient(private val http: OkHttpClient = OkHttpClient()) {
    suspend fun send(config: SereinHookConfig, row: SereinObservation): String? {
        val root = config.rootUrl() ?: return "invalid_config"
        val call = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(15, TimeUnit.SECONDS).build().newCall(Request.Builder().url("$root/v1/host/deliveries")
                .header("Authorization", "Bearer ${config.gatewayKey}")
                .post(row.payload.toRequestBody("application/json".toMediaType())).build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resume("network_error") }
                override fun onResponse(call: Call, response: Response) {
                    val error = response.use {
                        if (!it.isSuccessful) "http_${it.code}" else try {
                            val source = requireNotNull(it.body).source()
                            source.request(65537)
                            require(source.buffer.size <= 65536)
                            val obj = Json.parseToJsonElement(source.readUtf8()).jsonObject
                            if (obj["observation_version"]?.jsonPrimitive?.intOrNull != 1) "observation_upgrade_required"
                            else {
                                require(obj["status"]?.jsonPrimitive?.content == "recorded")
                                require(obj["receipt_id"]?.jsonPrimitive?.content == row.id)
                                require(obj["delivered_ids"] == Json.parseToJsonElement(row.payload).jsonObject["delivered_ids"])
                                null
                            }
                        } catch (_: Exception) { "invalid_response" }
                    }
                    if (cont.isActive) cont.resume(error)
                }
            })
        }
    }
}

internal suspend fun drainObservations(db: AppDatabase, config: SereinHookConfig, current: () -> Boolean,
    send: suspend (SereinObservation) -> String?): Boolean {
    val dao = db.sereinObservationDao()
    repeat(24) {
        if (!current()) return false
        val row = dao.next(config.uploadDestination()) ?: return false
        if (row.assistantId != config.assistantId || db.sereinHookDao().owner(row.conversationId) != row.assistantId) {
            dao.remove(row.id)
        } else {
            val error = send(row)
            if (error == null) dao.remove(row.id) else { dao.fail(row.id, error); return true }
        }
    }
    return current() && dao.next(config.uploadDestination()) != null
}

object SereinObservationScheduler : KoinComponent {
    private const val NOW = "serein-observation-now"
    private const val PERIODIC = "serein-observation-recovery"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SereinObservationWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun start(context: Context) = get<AppScope>().launch(Dispatchers.IO) {
        get<SettingsStore>().settingsFlow.filter { !it.init }.map { it.sereinHook }.distinctUntilChanged().collectLatest { config ->
            val manager = WorkManager.getInstance(context)
            if (!config.usableBy(config.assistantId)) {
                manager.cancelUniqueWork(NOW); manager.cancelUniqueWork(PERIODIC)
            } else {
                manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<SereinObservationWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
                get<AppDatabase>().sereinObservationDao().pending(config.uploadDestination()).distinctUntilChanged()
                    .collect { if (it > 0) runNow(context) }
            }
        }
    }
}

class SereinObservationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    companion object { private val mutex = Mutex() }
    override suspend fun doWork(): Result = mutex.withLock {
        applicationContext.awaitChatRecovery()
        val settings = get<SettingsStore>()
        val config = settings.settingsFlow.first { !it.init }.sereinHook
        if (!config.usableBy(config.assistantId)) return@withLock Result.success()
        fun current() = settings.settingsFlow.value.sereinHook.let {
            it.usableBy(config.assistantId) && it.uploadDestination() == config.uploadDestination() && it.gatewayKey == config.gatewayKey
        }
        try {
            val more = coroutineScope {
                val client = SereinObservationClient()
                val job = async { drainObservations(get(), config, ::current) { client.send(config, it) } }
                val watcher = launch { settings.settingsFlow.first { !current() }; job.cancel() }
                try { job.await() } finally { watcher.cancel() }
            }
            if (more) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            if (!current() && currentCoroutineContext().isActive) Result.success() else throw e
        } catch (_: Exception) { Result.retry() }
    }
}
