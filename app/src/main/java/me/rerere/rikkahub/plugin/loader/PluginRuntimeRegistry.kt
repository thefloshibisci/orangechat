package me.rerere.rikkahub.plugin.loader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/** Each runtime keeps thread affinity; retiring it never waits for plugin code. */
internal class PluginRuntimeRegistry<T : Any>(private val destroy: (T) -> Unit) {
    private class Entry<T>(val dispatcher: ExecutorCoroutineDispatcher) {
        var value: T? = null
        var finished = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry<T>>()
    private var closed = false

    fun load(id: String, create: () -> T, onResult: (Result<T>) -> Unit) {
        synchronized(lock) {
            check(!closed) { "Plugin loader is closed" }
            entries.remove(id)?.let(::retire)
            val entry = Entry<T>(Executors.newSingleThreadExecutor { task ->
                Thread(task, "plugin-quickjs-$id").apply { isDaemon = true }
            }.asCoroutineDispatcher())
            entries[id] = entry
            scope.launch(entry.dispatcher) {
                val result = runCatching(create)
                synchronized(lock) {
                    entry.value = result.getOrNull()
                    entry.finished = true
                    // An old load must never publish after disable, delete or replacement.
                    if (entries[id] === entry) onResult(result)
                }
            }
        }
    }

    fun get(id: String): T? = synchronized(lock) { entries[id]?.value }

    fun values(): List<T> = synchronized(lock) { entries.values.mapNotNull { it.value } }

    fun isLoading(id: String): Boolean = synchronized(lock) { entries[id]?.finished == false }

    fun ids(): Set<String> = synchronized(lock) { entries.keys.toSet() }

    fun remove(id: String) {
        synchronized(lock) { entries.remove(id)?.let(::retire) }
    }

    fun clear() {
        synchronized(lock) {
            entries.values.forEach(::retire)
            entries.clear()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            clear()
        }
    }

    suspend fun <R> call(id: String, timeoutMillis: Long, action: (T) -> R): Result<R> {
        val pending = synchronized(lock) {
            val entry = entries[id]
                ?: return Result.failure(IllegalStateException("Plugin not loaded: $id"))
            // Detached from the caller so a native evaluation cannot hold its timeout open.
            scope.async(entry.dispatcher) {
                val value = synchronized(lock) {
                    check(entries[id] === entry) { "Plugin was disabled or replaced: $id" }
                    entry.value ?: error("Plugin failed to load: $id")
                }
                runCatching { action(value) }
            }
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { pending.await() }
                ?: Result.failure(IllegalStateException("Plugin call timed out: $id"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pending.cancel()
        }
    }

    private fun retire(entry: Entry<T>) {
        scope.launch(entry.dispatcher) {
            try {
                entry.value?.let(destroy)
            } finally {
                entry.value = null
                entry.dispatcher.close()
            }
        }
    }
}
