package me.rerere.rikkahub.data.interaction

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class InteractionPlacementTest {
    private val card = ChatInteraction("payment", "c", "a", "assistant", "red_packet", status = "accepted")
    private fun tool(output: String) = UIMessagePart.Tool("call", "send_red_packet", "{}", listOf(UIMessagePart.Text(output)))
    @Test fun `inline uses current annotation status instead of old tool receipt`() {
        val messages = listOf(UIMessage.assistant("").copy(parts = listOf(tool("{\"id\":\"payment\",\"status\":\"pending\"}"))),
            UIMessage.assistant("stored").copy(annotations = listOf(card)))
        assertEquals(card, inlineInteractionCards(messages)["payment"])
        assertEquals("stored", messages.last().toText())
    }
    @Test fun `unmatched or malformed tool output leaves standalone card available`() {
        val message = UIMessage.assistant("stored").copy(annotations = listOf(card))
        for (output in listOf("error", "{\"id\":\"other\"}")) {
            assertTrue(inlineInteractionCards(listOf(message, UIMessage.assistant("").copy(parts = listOf(tool(output))))).isEmpty())
        }
    }
}
