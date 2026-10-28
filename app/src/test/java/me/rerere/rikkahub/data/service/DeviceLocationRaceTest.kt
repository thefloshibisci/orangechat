package me.rerere.rikkahub.data.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DeviceLocationRaceTest {
    @Test fun `fast usable provider wins and slow listener is cancelled before return`() = runBlocking {
        withTimeout(2_000) {
            val started = CompletableDeferred<Unit>()
            var cleanedUp = false
            val result = firstNonNullResult(listOf(
                suspend {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { cleanedUp = true }
                },
                suspend { started.await(); "fast location" },
            ))
            assertEquals("fast location", result)
            assertTrue(cleanedUp)
        }
    }

    @Test fun `empty result does not cancel a provider that can still succeed`() = runBlocking {
        val emptyCompleted = CompletableDeferred<Unit>()
        assertEquals("location", firstNonNullResult(listOf(
            suspend { emptyCompleted.complete(Unit); null },
            suspend { emptyCompleted.await(); "location" },
        )))
        assertNull(firstNonNullResult<String>(listOf(suspend { null }, suspend { null })))
        assertNull(firstNonNullResult<String>(emptyList()))
    }

    @Test fun `parent cancellation releases all pending listeners`() = runBlocking {
        val started = List(2) { CompletableDeferred<Unit>() }
        val cleanedUp = List(2) { CompletableDeferred<Unit>() }
        val task = async {
            firstNonNullResult<String>(List(2) { index -> suspend {
                try { started[index].complete(Unit); awaitCancellation() }
                finally { cleanedUp[index].complete(Unit) }
            } })
        }
        withTimeout(2_000) {
            started.forEach { it.await() }
            task.cancel()
            task.join()
            assertTrue(task.isCancelled)
            assertTrue(cleanedUp.all { it.isCompleted })
        }
    }
}
