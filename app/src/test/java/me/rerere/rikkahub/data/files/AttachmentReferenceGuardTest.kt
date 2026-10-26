package me.rerere.rikkahub.data.files

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentReferenceGuardTest {
    @Test fun completedAndPendingWritersBothInvalidateAnOldScan() {
        val guard = AttachmentReferenceCoordinator()
        val snapshot = guard.snapshot()!!
        val write = guard.beginWrite()
        assertNull(guard.snapshot())
        assertFalse(guard.commit(snapshot, "a") { error("Must not unlink") })
        write.close()
        write.close() // completion/cancellation may converge on the same lease
        assertFalse(guard.commit(snapshot, "a") { error("Stale scan") })
        assertTrue(guard.commit(guard.snapshot()!!, "a") { true })
    }

    @Test fun draftsBlockCollectionUntilReleasedAndRescanned() {
        val guard = AttachmentReferenceCoordinator()
        val owner = Any()
        val old = guard.snapshot()!!
        guard.updateDraft(owner, setOf("a"))
        assertFalse(guard.commit(old, "a") { error("New draft") })
        assertFalse(guard.commit(guard.snapshot()!!, "a") { error("Active draft") })
        guard.updateDraft(owner, emptySet())
        assertTrue(guard.commit(guard.snapshot()!!, "a") { true })
    }

    @Test fun cancelledWriterReleasesItsLeaseWithoutRevivingStaleScan() = runBlocking {
        val guard = AttachmentReferenceCoordinator()
        val old = guard.snapshot()!!
        val entered = CompletableDeferred<Unit>()
        val task = launch {
            guard.beginWrite().use { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        task.cancelAndJoin()
        assertNotNull(guard.snapshot())
        assertFalse(guard.commit(old, "a") { error("Cancelled writes still invalidate") })
    }
}
