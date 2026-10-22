package me.rerere.rikkahub.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Uses an independent timer: a saturated coroutine dispatcher must not hide its own stall. */
internal class TaskWatchdog(
    private val timer: ScheduledExecutorService,
    private val onStall: (List<Snapshot>) -> Unit,
) {
    data class Snapshot(val task: String, val stage: String, val elapsedMillis: Long)
    private val sequence = AtomicLong()
    private val active = ConcurrentHashMap<Long, Trace>()

    fun begin(task: String, timeoutMillis: Long = 15_000): Trace {
        val id = sequence.incrementAndGet()
        return Trace(id, task).also { trace ->
            active[id] = trace
            if (timeoutMillis > 0) trace.alarm = timer.schedule({
                if (!trace.closed.get()) runCatching { onStall(snapshots()) }
            }, timeoutMillis, TimeUnit.MILLISECONDS)
        }
    }

    fun snapshots(): List<Snapshot> = active.values.map { trace ->
        Snapshot(trace.task, trace.stage, (System.nanoTime() - trace.started) / 1_000_000)
    }.sortedByDescending { it.elapsedMillis }

    inner class Trace internal constructor(private val id: Long, internal val task: String) : AutoCloseable {
        internal val started = System.nanoTime()
        internal val closed = AtomicBoolean()
        @Volatile internal var alarm: ScheduledFuture<*>? = null
        @Volatile internal var stage = "queued"
        fun stage(value: String) { stage = value }
        override fun close() {
            closed.set(true)
            alarm?.cancel(false)
            active.remove(id)
        }
    }
}
