package me.rerere.rikkahub.data.interaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionContextTest {
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
