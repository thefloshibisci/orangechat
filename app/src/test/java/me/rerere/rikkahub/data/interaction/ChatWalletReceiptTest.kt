package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatWalletReceiptTest {
    private val card = ChatInteraction("payment", "conversation", "assistant", "assistant", "red_packet",
        status = "unconfirmed", amountCents = 501, walletLinked = true, pendingAction = "create")
    private fun receipt(state: String = "pending", actor: String = "assistant") = buildJsonObject {
        put("success", true); put("payment_id", "payment"); put("scope", "assistant:conversation")
        put("kind", "red_packet"); put("amount_cents", 501)
        put("direction", if (actor == "assistant") "expense" else "income"); put("status", state)
    }
    @Test fun `create and recipient settlement survive persistence roundtrip`() {
        for (actor in listOf("assistant", "user")) for (state in listOf("pending", "accepted", "returned")) {
            val result = card.copy(actor = actor).applyWalletReceipt(receipt(state, actor), "get")
            assertEquals(state, result.status)
            assertNull(result.pendingAction); assertNull(result.error)
            assertEquals(result, Json.decodeFromString<ChatInteraction>(Json.encodeToString(result)))
        }
    }
    @Test fun `sync reads original payment without replaying any pending write`() {
        assertEquals("get", chatWalletOperation("sync"))
        for (pending in listOf("create", "accept", "return")) {
            val result = card.copy(pendingAction = pending).applyWalletReceipt(receipt("accepted"), chatWalletOperation("sync"))
            assertEquals("accepted", result.status)
            assertNull(result.pendingAction)
        }
    }
    @Test fun `mismatched and malformed receipts never become confirmed`() {
        for (key in listOf("payment_id", "scope", "kind", "direction", "amount_cents", "status")) {
            val bad = JsonObject(receipt().toMutableMap().also { it[key] = JsonObject(emptyMap()) })
            val result = card.applyWalletReceipt(bad, "get")
            assertEquals("unconfirmed", result.status)
            assertTrue(result.error.orEmpty().contains(key))
            assertEquals("create", result.pendingAction)
        }
    }
    @Test fun `missing remote record does not authorize a new payment`() {
        val result = card.applyWalletReceipt(buildJsonObject {
            put("success", false); put("code", "not_found"); put("error", "没有找到这笔钱包记录")
        }, "get")
        assertEquals("unconfirmed", result.status)
        assertEquals("create", result.pendingAction)
    }
    @Test fun `missing success and explicit failure produce distinct actionable errors`() {
        val missing = JsonObject(receipt().filterKeys { it != "success" })
        val failure = JsonObject(receipt().toMutableMap().also { it["success"] = JsonPrimitive(false) })
        val malformed = JsonObject(receipt().toMutableMap().also { it["success"] = JsonObject(emptyMap()) })
        for (value in listOf(missing, failure, malformed)) {
            val result = card.applyWalletReceipt(value, "get")
            assertEquals("unconfirmed", result.status)
            assertTrue(result.error.orEmpty().contains("success"))
            assertNotEquals("钱包结果未确认", result.error)
        }
        assertNotEquals(card.applyWalletReceipt(missing, "get").error, card.applyWalletReceipt(failure, "get").error)
    }
}
