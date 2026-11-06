package me.rerere.rikkahub.data.interaction

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.*
import org.junit.Test

class RichChatInteractionsTest {
    private fun card(actor: String = "assistant", kind: String = "transfer") = ChatInteraction(
        "payment", "conversation", "assistant", actor, kind, amountCents = 501, walletLinked = true)
    @Test fun `money uses exact cents and rejects invalid fractions and extremes`() {
        assertEquals(501L, parseChatAmount("5.01"))
        assertEquals(10L, parseChatAmount("0.10"))
        assertEquals("5.01", formatChatAmount(501))
        listOf("0", "-1", "NaN", "Infinity", "1.001", "1000000000", "").forEach {
            assertTrue(it, runCatching { parseChatAmount(it) }.isFailure)
        }
    }
    @Test fun `red packets allow 200 exactly and reject higher amounts without limiting transfers`() {
        assertEquals(20_000L, parseChatGiftAmount("red_packet", "200.00"))
        assertEquals(1L, parseChatGiftAmount("red_packet", "0.01"))
        listOf("200.01", "201", "999999999.99").forEach {
            assertTrue(runCatching { parseChatGiftAmount("red_packet", it) }.isFailure)
        }
        assertEquals(20_001L, parseChatGiftAmount("transfer", "200.01"))
    }
    @Test fun `model cannot dispatch an over limit packet even to a virtual creation callback`() = runBlocking {
        var creates = 0
        val tools = buildRichChatTools(false, { _, _, _, _ -> creates++; card(kind="red_packet").copy(walletLinked=false) }, { _, _ -> error("unexpected") }, pretendRich = true)
        val result = runCatching { tools.single { it.name == "send_virtual_red_packet" }.execute(buildJsonObject {
            put("amount", "200.01"); put("request_id", "over-limit")
        }) }
        assertTrue(result.isFailure)
        assertEquals(0, creates)
    }
    @Test fun `sender cannot receive own payment and unconfirmed cannot be accepted`() {
        assertTrue(runCatching { validateChatInteractionAction(card(), "assistant", "accept") }.isFailure)
        validateChatInteractionAction(card(), "user", "accept")
        assertTrue(runCatching { validateChatInteractionAction(card().copy(status="unconfirmed"), "user", "accept") }.isFailure)
        validateChatInteractionAction(card().copy(status="unconfirmed"), "user", "sync")
        assertTrue(runCatching { validateChatInteractionAction(card(kind="call"), "user", "return") }.isFailure)
    }
    @Test fun `annotation roundtrip preserves wallet mode pending action and identity`() {
        val original: UIMessageAnnotation = card().copy(status="unconfirmed", pendingAction="return", theme="sakura")
        assertEquals(original, Json.decodeFromString<UIMessageAnnotation>(Json.encodeToString(original)))
        assertTrue(card().readableText().contains("互动单号:payment"))
    }
    @Test fun `tool reports actual unconfirmed receipt rather than success`() = runBlocking {
        val tools = buildRichChatTools(false, { kind, amount, note, requestId ->
            assertEquals("transfer",kind);assertEquals("5.01",amount);assertEquals("once",requestId)
            card().copy(status="unconfirmed",note=note)
        }, { _, _ -> error("unexpected") })
        assertFalse(tools.any { it.name == "request_voice_call" })
        val result = tools.single { it.name == "send_wallet_transfer" }.execute(buildJsonObject {
            put("amount","5.01");put("request_id","once")
        })
        assertTrue(result.toString().contains("unconfirmed"))
    }
}
