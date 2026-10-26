package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class MessageQueueTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test fun fifoPreservesAllLocalRequestExtras() {
        val queue = MessageQueue()
        try {
            val interaction = UIMessageAnnotation.AvatarInteraction("user", "assistant")
            val first = queue.enqueue(text("first"), answer = false, interaction = interaction,
                transientSystemPrompt = "hidden context", enableTools = false)!!
            val second = queue.enqueue(text("second"))!!
            assertSame(first, queue.takeNext())
            assertFalse(first.answer)
            assertFalse(first.enableTools)
            assertEquals("hidden context", first.transientSystemPrompt)
            assertEquals(interaction, first.interaction)
            assertSame(second, queue.takeNext())
            assertNull(queue.takeNext())
            assertEquals(listOf(first, second), queue.inFlightMessages)
        } finally { queue.clear() }
    }

    @Test fun snapshotsCallerOwnedParts() {
        val queue = MessageQueue()
        try {
            val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
            queue.enqueue(parts)
            parts.clear()
            assertEquals(text("original"), queue.takeNext()!!.parts)
        } finally { queue.clear() }
    }

    @Test fun editingHeadBlocksFifoAndPreservesObservers() {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("first"))!!
            queue.enqueue(text("second"))
            assertSame(first, queue.beginEdit(first.id))
            assertNull(queue.takeNext())
            queue.finishEdit(first.id, text("edited"))
            val edited = queue.takeNext()!!
            assertEquals(first.id, edited.id)
            assertEquals(text("edited"), edited.parts)
            assertSame(first.reply, edited.reply)
            assertSame(first.completion, edited.completion)
            assertEquals(text("second"), queue.takeNext()!!.parts)
        } finally { queue.clear() }
    }

    @Test fun editingLaterDoesNotBlockEarlierAndCancelRestoresOriginal() {
        val queue = MessageQueue()
        try {
            queue.enqueue(text("first"))
            val second = queue.enqueue(text("second"))!!
            queue.beginEdit(second.id)
            assertEquals(text("first"), queue.takeNext()!!.parts)
            assertNull(queue.takeNext())
            assertNull(queue.finishEdit(second.id))
            assertEquals(text("second"), queue.takeNext()!!.parts)
        } finally { queue.clear() }
    }

    @Test fun invalidEditStaysLockedUntilCorrectedOrCancelled() {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("first"))!!
            queue.beginEdit(first.id)
            assertNull(queue.finishEdit(first.id, text(" \n")))
            assertTrue(queue.state.value.messages.single().isEditing)
            assertNull(queue.takeNext())
            queue.finishEdit(first.id)
            assertEquals(first.id, queue.takeNext()!!.id)
        } finally { queue.clear() }
    }

    @Test fun withdrawResolvesOnlyItsOwnObservers() = runBlocking {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("first"))!!
            val second = queue.enqueue(text("second"))!!
            assertSame(first, queue.remove(first.id))
            assertNull(first.reply.await())
            assertTrue(first.completion.isCancelled)
            assertFalse(second.reply.isCompleted)
            assertFalse(second.completion.isCompleted)
            assertNull(queue.remove(first.id))
            assertEquals(second.id, queue.takeNext()!!.id)
        } finally { queue.clear() }
    }

    @Test fun pauseFailsReplyButRetainsContentUntilExplicitResume() = runBlocking {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("first"))!!
            queue.pause()
            assertTrue(runCatching { first.reply.await() }.exceptionOrNull() is MessageQueuePausedException)
            assertFalse(first.completion.isCompleted)
            queue.enqueue(text("second"))
            queue.beginEdit(first.id)
            queue.finishEdit(first.id, text("edited"))
            assertTrue(queue.state.value.paused)
            assertNull(queue.takeNext())
            queue.resume()
            assertEquals(text("edited"), queue.takeNext()!!.parts)
            assertEquals(text("second"), queue.takeNext()!!.parts)
        } finally { queue.clear() }
    }

    @Test fun completionIsPerTurnAndReleasesInFlightOwnership() = runBlocking {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("first"))!!
            val second = queue.enqueue(text("second"))!!
            queue.takeNext()
            queue.complete(first.id, "first reply")
            assertEquals("first reply", first.reply.await())
            first.completion.await()
            assertTrue(queue.inFlightMessages.isEmpty())
            assertFalse(second.reply.isCompleted)
            assertTrue(queue.hasPendingOrInFlight)
            queue.takeNext()
            queue.complete(second.id, "second reply")
            assertFalse(queue.hasPendingOrInFlight)
            queue.complete(first.id, "late replacement")
            assertEquals("first reply", first.reply.await())
        } finally { queue.clear() }
    }

    @Test fun submittingAfterPauseFailsObserverWithoutDroppingItsManualResumeContent() = runBlocking {
        val queue = MessageQueue()
        try {
            val first = queue.enqueue(text("backlog"))!!
            queue.pause()
            val late = queue.enqueue(text("new voice or bot input"), answer = false,
                transientSystemPrompt = "snapshot", enableTools = false)!!
            assertTrue(runCatching { late.reply.await() }.exceptionOrNull() is MessageQueuePausedException)
            assertFalse(late.completion.isCompleted)
            assertEquals(listOf(first.id, late.id), queue.state.value.messages.map { it.id })
            assertNull(queue.takeNext())
            queue.resume()
            queue.complete(queue.takeNext()!!.id, "first reply")
            val dispatched = queue.takeNext()!!
            assertEquals(late.id, dispatched.id)
            assertEquals(text("new voice or bot input"), dispatched.parts)
            assertEquals("snapshot", dispatched.transientSystemPrompt)
            assertFalse(dispatched.answer)
            assertFalse(dispatched.enableTools)
            queue.complete(dispatched.id, "manual completion")
            late.completion.await()
            assertTrue(runCatching { late.reply.await() }.exceptionOrNull() is MessageQueuePausedException)
        } finally { queue.clear() }
    }

    @Test fun failedGenerationCompletesBothObserversWithTheCause() = runBlocking {
        val queue = MessageQueue()
        val entry = queue.enqueue(text("question"))!!
        queue.takeNext()
        val error = IllegalStateException("provider failed")
        queue.complete(entry.id, error = error)
        val replyError = runCatching { entry.reply.await() }.exceptionOrNull()
        val completionError = runCatching { entry.completion.await() }.exceptionOrNull()
        // Coroutine stack recovery can copy an exception while retaining the original as its cause.
        assertTrue(replyError === error || replyError?.cause === error)
        assertTrue(completionError === error || completionError?.cause === error)
        assertFalse(queue.hasPendingOrInFlight)
    }

    @Test fun approvalFailureDoesNotRemoveOrResumePendingContent() = runBlocking {
        val queue = MessageQueue()
        try {
            val entry = queue.enqueue(text("pending"))!!
            queue.failReplyWaiters("approval required")
            assertEquals("approval required", runCatching { entry.reply.await() }.exceptionOrNull()?.message)
            assertEquals(1, queue.state.value.messages.size)
            assertFalse(entry.completion.isCompleted)
            assertFalse(queue.state.value.paused)
        } finally { queue.clear() }
    }

    @Test fun clearWithdrawsPendingAndInFlightWithoutLateResurrection() = runBlocking {
        val queue = MessageQueue()
        val first = queue.enqueue(text("first"))!!
        val second = queue.enqueue(text("second"))!!
        queue.takeNext()
        assertEquals(2, queue.clear().size)
        assertFalse(queue.hasPendingOrInFlight)
        assertTrue(queue.state.value.paused)
        for (entry in listOf(first, second)) {
            assertNull(entry.reply.await())
            assertTrue(entry.completion.isCancelled)
        }
        queue.complete(first.id, "late")
        queue.finishEdit(second.id, text("late edit"))
        assertFalse(queue.hasPendingOrInFlight)
        assertTrue(queue.clear().isEmpty())
    }

    @Test fun rejectsEmptyInputAndKeepsConversationQueuesIsolated() {
        val first = MessageQueue()
        val second = MessageQueue()
        try {
            assertNull(first.enqueue(emptyList()))
            assertNull(first.enqueue(text(" \n")))
            val image = listOf(UIMessagePart.Image("/tmp/queue-image.png"))
            val imageMessage = first.enqueue(image)!!
            assertEquals(image, first.takeNext()!!.parts)
            first.complete(imageMessage.id)
            first.enqueue(text("first"))
            second.enqueue(text("second"))
            first.pause()
            assertNull(first.takeNext())
            assertEquals(text("second"), second.takeNext()!!.parts)
        } finally { first.clear(); second.clear() }
    }
}
