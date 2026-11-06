package me.rerere.rikkahub.data.interaction

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class InteractionRequestIdentityTest {
    private fun input(amount: String = "520", note: String = "晚安", requestId: String = "gift-1") =
        buildJsonObject { put("request_id", requestId); put("amount", amount); put("note", note) }

    @Test fun regenerationsCanReuseModelRequestIdsWhileSameReplyRetriesReuseTheCard() = runBlocking {
        val cards = mutableMapOf<String, ChatInteraction>()
        val tools = buildRichChatTools(true, { kind, amount, note, requestId ->
            val cents = if (kind == "call") 0L else parseChatGiftAmount(kind, amount)
            cards[requestId]?.also {
                require(it.kind == kind && it.amountCents == cents && it.note == note)
            } ?: ChatInteraction(requestId, "conversation", "assistant", "assistant", kind,
                amountCents = cents, note = note).also { cards[requestId] = it }
        }, { _, _ -> error("unexpected response") }, pretendRich = true)
        val transfer = tools.single { it.name == "send_virtual_transfer" }
        val originalReply = Uuid.random()
        transfer.withInteractionRequestScope(originalReply).execute(input())
        val originalId = cards.keys.single()
        cards[originalId] = cards.getValue(originalId).copy(status = "accepted")
        val retry = transfer.withInteractionRequestScope(originalReply).execute(input())
        assertEquals(1, cards.size)
        assertTrue(retry.toString().contains("accepted"))
        assertTrue(runCatching { transfer.withInteractionRequestScope(originalReply).execute(input("521")) }.isFailure)
        assertTrue(runCatching { transfer.withInteractionRequestScope(originalReply).execute(input(note = "改附言")) }.isFailure)
        assertEquals(1, cards.size)
        // Regeneration creates a new persisted reply ID even if the model repeats its request_id.
        val secondReply = Uuid.random()
        transfer.withInteractionRequestScope(secondReply).execute(input("521", "新回复"))
        transfer.withInteractionRequestScope(secondReply).execute(input("521", "新回复"))
        assertEquals(2, cards.size)
        assertEquals(52000L, cards.getValue(originalId).amountCents)
        assertEquals("accepted", cards.getValue(originalId).status)
        assertEquals(52100L, cards.values.single { it.id != originalId }.amountCents)
    }

    @Test fun everySendModeUsesStableScopeWithoutChangingThePublicArguments() = runBlocking {
        for (name in listOf("send_virtual_red_packet", "send_virtual_transfer",
                "send_wallet_red_packet", "send_wallet_transfer", "request_voice_call")) {
            val seen = mutableListOf<JsonObject>()
            val tool = Tool(name, "") { seen += it.jsonObject; emptyList() }
            val reply = Uuid.random()
            val args = input()
            tool.withInteractionRequestScope(reply).execute(args)
            tool.withInteractionRequestScope(reply).execute(args)
            tool.withInteractionRequestScope(Uuid.random()).execute(args)
            assertEquals(seen[0], seen[1])
            assertNotEquals(seen[0]["request_id"], seen[2]["request_id"])
            assertEquals(args - "request_id", seen[0] - "request_id")
            assertEquals("gift-1", args.getValue("request_id").jsonPrimitive.content)
            assertEquals(36, seen[0].getValue("request_id").jsonPrimitive.content.length)
        }
    }

    @Test fun invalidIdsDoNotReachCreationAndNonSendToolsStayUnchanged() = runBlocking {
        var creates = 0
        val tool = Tool("send_virtual_transfer", "") { creates++; emptyList() }.withInteractionRequestScope(Uuid.random())
        for (id in listOf("", "   ", "x".repeat(129))) {
            assertTrue(runCatching { tool.execute(input(requestId = id)) }.isFailure)
        }
        assertTrue(runCatching { tool.execute(buildJsonObject { put("amount", "520") }) }.isFailure)
        assertEquals(0, creates)
        for (name in listOf("respond_to_payment", "mcp_other_tool")) {
            val unrelated = Tool(name, "") { emptyList() }
            assertSame(unrelated, unrelated.withInteractionRequestScope(Uuid.random()))
        }
    }
}
