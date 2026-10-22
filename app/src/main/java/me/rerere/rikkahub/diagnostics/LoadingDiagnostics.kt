package me.rerere.rikkahub.diagnostics

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.common.android.Logging
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** Local task labels and stacks only; never includes chat text, URLs, user filenames or credentials. */
internal object LoadingDiagnostics {
    private const val TAG = "LoadingDiagnostics"
    private const val MAX_REPORT_CHARS = 128 * 1024
    private val timer = ScheduledThreadPoolExecutor(1) { work ->
        Thread(work, "loading-diagnostics").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val probeScope = CoroutineScope(SupervisorJob())
    private val lastCapture = AtomicLong()
    @Volatile private var reportFile: File? = null
    @Volatile private var latestReport: String? = null
    private val watchdog = TaskWatchdog(timer, ::capture)

    fun begin(context: Context, task: String, timeoutMillis: Long = 15_000): TaskWatchdog.Trace {
        reportFile = file(context)
        return watchdog.begin(task, timeoutMillis)
    }

    private fun file(context: Context) = File(context.filesDir, "diagnostics/last-loading-stall.txt")

    private fun capture(tasks: List<TaskWatchdog.Snapshot>) {
        val now = System.currentTimeMillis()
        val previous = lastCapture.get()
        if (now - previous < 60_000 || !lastCapture.compareAndSet(previous, now)) return
        val probes = ConcurrentHashMap<String, String>()
        val jobs = listOf("Main" to Dispatchers.Main, "IO" to Dispatchers.IO, "Default" to Dispatchers.Default)
            .map { (name, dispatcher) ->
                probes[name] = "pending"
                probeScope.launch(dispatcher) { probes[name] = "ran after ${System.currentTimeMillis() - now}ms" }
            }
        timer.schedule({
            try {
                val stacks = Thread.getAllStackTraces()
                val report = buildString {
                    appendLine("Local loading stall at $now; this is a diagnostic sample, not a confirmed deadlock.")
                    appendLine("Dispatcher probes: ${probes.toSortedMap()}")
                    appendLine("Tasks at timeout:")
                    tasks.take(32).forEach { appendLine("${it.task}: ${it.stage}; elapsed=${it.elapsedMillis}ms") }
                    appendLine("Tasks one second later:")
                    watchdog.snapshots().take(32).forEach { appendLine("${it.task}: ${it.stage}; elapsed=${it.elapsedMillis}ms") }
                    appendLine("Thread counts: ${stacks.keys.groupingBy { threadCategory(it.name) + ":" + it.state }.eachCount().toSortedMap()}")
                    appendLine("Thread stacks:")
                    stacks.entries.sortedBy { threadPriority(it.key.name) }.take(240).forEach { (thread, stack) ->
                        appendLine("thread ${thread.id} ${threadCategory(thread.name)} [${thread.state}]")
                        stack.take(24).forEach { appendLine("  $it") }
                    }
                }.take(MAX_REPORT_CHARS)
                latestReport = report
                reportFile?.let { target ->
                    target.parentFile?.mkdirs()
                    val temporary = File(target.parentFile, "last-loading-stall.tmp")
                    temporary.writeText(report)
                    check(temporary.renameTo(target)) { "Cannot save loading diagnostic" }
                }
                Logging.log(TAG, "本地加载超过15秒，已保存任务阶段与线程现场，可在请求日志中导出。")
            } catch (_: Exception) {
                Logging.log(TAG, "无法保存本地加载诊断。")
            } finally { jobs.forEach { it.cancel() } }
        }, 1, TimeUnit.SECONDS)
    }

    // Read through the independent timer too, so the log screen remains useful during IO starvation.
    suspend fun read(context: Context): String? {
        latestReport?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            timer.execute {
                val report = runCatching { file(context).takeIf { it.isFile }?.readText()?.take(MAX_REPORT_CHARS) }.getOrNull()
                if (continuation.isActive) continuation.resume(report)
            }
        }
    }

    fun clear(context: Context) {
        latestReport = null
        timer.execute { runCatching { file(context).delete() } }
    }

    internal fun threadCategory(name: String): String = when {
        name == "main" -> "main"
        name.startsWith("DefaultDispatcher") -> "coroutine-worker"
        name.startsWith("OkHttp") -> "http-worker"
        name.startsWith("plugin-") -> "plugin-worker"
        name == "loading-diagnostics" -> "diagnostics"
        else -> "other"
    }

    private fun threadPriority(name: String): Int = when (threadCategory(name)) {
        "main" -> 0
        "coroutine-worker" -> 1
        "http-worker", "plugin-worker" -> 2
        else -> 3
    }
}
