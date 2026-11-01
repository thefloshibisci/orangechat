package me.rerere.rikkahub.utils

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatReadStatusTest {
    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
    private fun answer(text: String) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    @Test fun visibilitySelectsOnlyRealDisplayedContentAndTheCurrentBranch() {
        val oldNode = Uuid.random()
        val visibleNode = Uuid.random()
        val placeholderNode = Uuid.random()
        val oldSkipped = answer("old, outside the viewport")
        val oldBranch = answer("unselected regeneration")
        val selectedBranch = answer("selected regeneration")
        val placeholder = answer("")
        val nodes = mapOf(oldNode to oldSkipped, visibleNode to selectedBranch, placeholderNode to placeholder)
        val seen = visibleAssistantMessageIds(nodes, listOf("divider", visibleNode, placeholderNode))
        assertEquals(setOf(selectedBranch.id.toString()), seen)
        assertTrue(oldSkipped.id.toString() !in seen && oldBranch.id.toString() !in seen)
        assertTrue(visibleAssistantMessageIds(nodes, listOf(placeholderNode)).isEmpty())
        // When the screen resumes after the placeholder fills, it can finally be recorded.
        val filled = nodes + (placeholderNode to placeholder.copy(parts = listOf(UIMessagePart.Text("arrived"))))
        assertEquals(setOf(placeholder.id.toString()), visibleAssistantMessageIds(filled, listOf(placeholderNode)))
    }

    @Test fun pendingAndEmptyOrSkippedResponsesStayUnread() {
        val request = user("hello")
        assertTrue(repliedUserMessageIds(listOf(request)).isEmpty())
        assertTrue(repliedUserMessageIds(listOf(request, answer(""))).isEmpty())
        assertTrue(repliedUserMessageIds(listOf(request, answer("[SKIP]"))).isEmpty())
    }

    @Test fun responseMarksOnlyPrecedingMessagesAndRecomputesForSelectedBranch() {
        val first = user("first")
        val second = user("second")
        val last = user("still waiting")
        val response = answer("received")
        assertEquals(setOf(first.id, second.id), repliedUserMessageIds(listOf(first, second, response, last)))
        assertTrue(repliedUserMessageIds(listOf(first, second, response.copy(parts = emptyList()), last)).isEmpty())
    }

    @Test fun firstReasoningOrMediaChunkCountsButEmptyPlaceholderDoesNot() {
        val request = user("hello")
        val placeholder = answer("")
        for (part in listOf(UIMessagePart.Reasoning("thinking"), UIMessagePart.Image("file:///answer.png"),
            UIMessagePart.Tool("call-1", "search", "{}"))) {
            assertEquals(setOf(request.id), repliedUserMessageIds(listOf(request, placeholder.copy(parts = listOf(part)))))
        }
    }

    @Test fun proactiveResponseDoesNotMarkUnansweredUserMessageRead() {
        val request = user("hello")
        val trigger = user("[主动消息上下文]")
        assertTrue(repliedUserMessageIds(listOf(request, trigger, answer("scheduled greeting"))).isEmpty())
    }

    @Test fun assistantMessagesBecomeReadOnlyAfterARealUserTurn() {
        val first = answer("one")
        val second = answer("two")
        val reply = user("got it")
        val latest = answer("three")
        assertEquals(setOf(first.id, second.id), readAssistantMessageIds(listOf(first, second, reply, latest)))
        assertTrue(readAssistantMessageIds(listOf(first, user("[主动消息上下文]"), latest)).isEmpty())
    }
}
