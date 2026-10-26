package me.rerere.rikkahub.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Finalize the interrupted turn before any newly resumed input can start. */
internal suspend fun ConversationSession.stopQueuedGeneration(
    pauseQueue: Boolean,
    ownsRequest: () -> Boolean = { true },
    finishInterruptedMessage: suspend () -> Unit,
): Boolean {
    val (barrier, jobs) = synchronized(this) {
        if (isDeleted || !ownsRequest()) return false
        val barrier = blockQueueDispatch()
        if (pauseQueue) messageQueue.pause()
        barrier to cancelJobs()
    }
    try {
        withContext(NonCancellable) {
            jobs.forEach { it.join() }
            if (!isDeleted) finishInterruptedMessage()
        }
    } catch (error: Exception) {
        messageQueue.pause()
        throw error
    } finally {
        barrier.close()
    }
    return true
}
