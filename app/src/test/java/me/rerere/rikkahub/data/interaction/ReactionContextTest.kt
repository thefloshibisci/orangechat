package me.rerere.rikkahub.data.interaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class ReactionContextTest {
    @Test fun `reaction during streaming remains new even when request context drops history`() {
        val start = LocalDateTime(2026, 10, 24, 12, 0)
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), createdAt = start,
            finishedAt = LocalDateTime(2026, 10, 24, 12, 1))
        val user = UIMessage(role = MessageRole.USER, parts = emptyList())
        val boundary = previousReactionBoundary(listOf(reply, user), user.id)
        val startMillis = start.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        assertEquals(startMillis, boundary)
        val text = buildInteractionContext(listOf(reaction("during", startMillis + 30_000)), boundary)
        assertTrue(text.contains("New since your last reply"))
        assertFalse(text.contains("Already present"))
    }

    @Test fun `boundary ignores assistant continuation after current user and missing branch`() {
        val user = UIMessage(role = MessageRole.USER, parts = emptyList())
        val continuation = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        assertEquals(null, previousReactionBoundary(listOf(user, continuation), user.id))
        assertEquals(null, previousReactionBoundary(listOf(continuation), user.id))
    }

    private fun reaction(emoji: String, at: Long) =
        MessageReaction("k$emoji", "c", "m", "user", emoji, "q", at)

    @Test
    fun `reactions before the last reply are marked as not new`() {
        val text = buildInteractionContext(listOf(reaction("old", 100), reaction("new", 300)), sinceMillis = 200)
        val newHeader = text.indexOf("New since your last reply:")
        val oldHeader = text.indexOf("Already present before your last reply")
        assertTrue(newHeader in 0 until text.indexOf("reacted new"))
        assertTrue(oldHeader in 0 until text.indexOf("reacted old"))
        assertTrue(text.indexOf("reacted new") < oldHeader)
    }

    @Test
    fun `only old reactions produce no new section`() {
        val text = buildInteractionContext(listOf(reaction("old", 100)), sinceMillis = 200)
        assertFalse(text.contains("New since your last reply"))
        assertTrue(text.contains("reacted old"))
    }

    @Test
    fun `without a previous reply everything is new`() {
        val text = buildInteractionContext(listOf(reaction("a", 100)), sinceMillis = null)
        assertTrue(text.contains("New since your last reply"))
        assertFalse(text.contains("Already present"))
    }
}
