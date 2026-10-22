package me.rerere.rikkahub.web

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the server reference under one lock, including creation and shutdown. */
internal class WebServerLifecycle<T : Any> {
    private val mutex = Mutex()
    private var server: T? = null

    suspend fun start(create: suspend () -> T) = mutex.withLock {
        withContext(NonCancellable) { if (server == null) server = create() }
    }

    suspend fun stop(close: suspend (T) -> Unit) = mutex.withLock {
        withContext(NonCancellable) { stopLocked(close) }
    }

    suspend fun restart(create: suspend () -> T, close: suspend (T) -> Unit) = mutex.withLock {
        withContext(NonCancellable) {
            stopLocked(close)
            server = create()
        }
    }

    private suspend fun stopLocked(close: suspend (T) -> Unit) {
        server?.let { close(it) }
        server = null
    }
}
