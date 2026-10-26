package me.rerere.rikkahub.data.files

import java.io.Closeable
import java.util.WeakHashMap

/**
 * Optimistic exclusion for file garbage collection. Writers never wait for a database scan:
 * starting/finishing one invalidates the scan. Only the final filesystem unlink runs under
 * this monitor, with no coroutine suspension, Room transaction or settings lock inside it.
 */
internal class AttachmentReferenceCoordinator {
    private var revision = 0L
    private var writers = 0
    private val drafts = WeakHashMap<Any, Set<String>>()

    @Synchronized fun beginWrite(): Closeable {
        revision++
        writers++
        var closed = false
        return Closeable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    writers--
                    revision++
                }
            }
        }
    }

    @Synchronized fun updateDraft(owner: Any, names: Set<String>) {
        revision++
        if (names.isEmpty()) drafts.remove(owner) else drafts[owner] = names
    }

    @Synchronized fun protectedNames(): Set<String> = drafts.values.flatten().toSet()

    /** Existing lifecycle GC already owns its DB transaction; still honor live draft/merge pins. */
    @Synchronized fun ifUnpinned(name: String, unlink: () -> Unit) {
        if (drafts.values.none { name in it }) unlink()
    }

    @Synchronized fun snapshot(): Long? = revision.takeIf { writers == 0 }

    /** No suspending work or hashing here. False means keep the file, not a deletion failure. */
    @Synchronized fun commit(snapshot: Long, name: String, unlink: () -> Boolean): Boolean {
        if (writers != 0 || revision != snapshot || drafts.values.any { name in it }) return false
        return unlink()
    }
}

internal val attachmentReferenceGuard = AttachmentReferenceCoordinator()
