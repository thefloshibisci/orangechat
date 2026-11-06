package me.rerere.rikkahub.data.interaction

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test

class InteractionPlacementTest {
    @Test fun regeneratedReplyHidesOldPaymentsUntilTheirOriginalReplyIsSelected() {
        val original = UIMessage.assistant("original").copy(parts = listOf(
            tool("{\"id\":\"payment\"}"), tool("{\"id\":\"second\"}")))
        val reply = MessageNode(messages = listOf(original, UIMessage.assistant("regenerated")), selectIndex = 1)
        val later = MessageNode(messages = listOf(UIMessage.user("later")))
        val firstCard = MessageNode(messages = listOf(UIMessage.assistant("card").copy(annotations = listOf(card))))
        val secondCard = MessageNode(messages = listOf(UIMessage.assistant("card").copy(annotations = listOf(card.copy(id = "second")))))
        val stored = listOf(reply, later, firstCard, secondCard)
        assertEquals(listOf(reply, later), interactionDisplayNodes(stored))
        assertEquals(listOf(reply, later, firstCard, secondCard), stored)
        assertEquals(interactionDisplayNodes(stored), interactionDisplayNodes(interactionDisplayNodes(stored)))
        val selectedOriginal = listOf(reply.copy(selectIndex = 0), later, firstCard, secondCard)
        assertEquals(listOf(reply.copy(selectIndex = 0), firstCard, secondCard, later), interactionDisplayNodes(selectedOriginal))
        assertEquals(card.status, interactionDisplayNodes(selectedOriginal)[1].currentMessage.annotations
            .filterIsInstance<ChatInteraction>().single().status)
        assertEquals(setOf("payment", "second"), inlineInteractionCards(selectedOriginal.map { it.currentMessage }).keys)
    }

    @Test fun fiveReplyVariantsShowOnlyTheirOwnPaymentsWithoutChangingDurableRecords() {
        val versions = (1..5).map { index -> UIMessage.assistant("").copy(parts = listOf(
            tool("{\"id\":\"payment-$index\"}"), UIMessagePart.Text("回复$index"))) }
        val reply = MessageNode(messages = versions, selectIndex = 4)
        val cards = (1..5).map { index -> MessageNode(messages = listOf(UIMessage.assistant("stored").copy(
            annotations = listOf(card.copy(id = "payment-$index", status = if (index == 2) "accepted" else "pending"))))) }
        val later = MessageNode(messages = listOf(UIMessage.user("继续聊")))
        val stored = listOf(reply, later) + cards
        for (index in 0..4) {
            val switched = stored.map { if (it.id == reply.id) it.copy(selectIndex = index) else it }
            val displayed = interactionDisplayNodes(switched)
            val inline = inlineInteractionCards(switched.map { it.currentMessage })
            assertEquals(setOf("payment-" + (index + 1)), inline.keys)
            assertEquals(listOf(switched.first(), cards[index], later), displayed)
            assertEquals(listOf(switched.first(), later), displayed.filterNot { node ->
                node.currentMessage.annotations.filterIsInstance<ChatInteraction>().any { it.id in inline }
            })
            assertEquals(5, switched.flatMap { it.currentMessage.annotations }.filterIsInstance<ChatInteraction>().size)
        }
        assertEquals("accepted", cards[1].currentMessage.annotations.filterIsInstance<ChatInteraction>().single().status)
        assertEquals(4, stored.first().selectIndex)
    }

    @Test fun userPaymentsAndOrphanedCardsStayInPlace() {
        val userCard = MessageNode(messages = listOf(UIMessage.user("card").copy(annotations = listOf(card.copy(actor = "user")))))
        val orphan = MessageNode(messages = listOf(UIMessage.assistant("card").copy(annotations = listOf(card.copy(id = "orphan")))))
        val reply = MessageNode(messages = listOf(UIMessage.assistant("reply")))
        val stored = listOf(userCard, reply, orphan)
        assertEquals(stored, interactionDisplayNodes(stored))
    }

    @Test fun legacySyntheticFollowupIsHiddenOnlyBesideAUserGift() {
        val gift = UIMessage.user("card").copy(annotations = listOf(card.copy(actor = "user")))
        val synthetic = UIMessage.user("请查看我刚发来的红包或转账卡片。")
        assertEquals(setOf(synthetic.id), legacyGiftFollowupIds(listOf(gift, synthetic)))
        assertTrue(legacyGiftFollowupIds(listOf(UIMessage.user("你好"), synthetic)).isEmpty())
        assertTrue(legacyGiftFollowupIds(listOf(gift, UIMessage.user("这是我写的附言"))).isEmpty())
        val newTool = tool("{\"id\":\"payment\"}").copy(toolName = "send_virtual_red_packet")
        assertEquals("payment", interactionToolId(newTool))
    }
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
