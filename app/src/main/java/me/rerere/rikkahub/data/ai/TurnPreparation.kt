package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A snapshot belongs to one generation/collection, never to another user turn or retry. */
internal class TurnPreparation<T>(private val prepare: suspend () -> T) {
    private class Value<T>(val value: T)
    private val mutex = Mutex()
    private var snapshot: Value<T>? = null

    suspend fun get(): T = mutex.withLock {
        snapshot?.let { return@withLock it.value }
        // Failed or cancelled preparation must not become a successful cached snapshot.
        prepare().also {
            currentCoroutineContext().ensureActive()
            snapshot = Value(it)
        }
    }
}
