package me.rerere.rikkahub.data.interaction

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class InteractionContextTest {
    @Test fun regeneratingAnEarlierReplyDoesNotOfferLaterPayments() {
        val card = ChatInteraction("old-card", "c", "a", "user", "transfer")
        val earlier = UIMessage.user("earlier").copy(annotations = listOf(card))
        val later = UIMessage.user("later").copy(annotations = listOf(card.copy(id = "future-card")))
        val prompt = interactionStatePrompt(listOf(earlier, later), true, false, paymentMessageIds = setOf(earlier.id))
        assertTrue(prompt.contains("old-card"))
        assertFalse(prompt.contains("future-card"))
    }

    @Test fun paymentPromptNamesTheRealReceiveToolAndCurrentPendingUserCards() {
        val pending = ChatInteraction("receive-me", "c", "a", "user", "red_packet",
            amountCents = 20000, walletLinked = false)
        val cards = listOf(pending, pending.copy(id = "already-done", status = "accepted"),
            pending.copy(id = "assistant-gift", actor = "assistant"))
        val messages = cards.map { UIMessage.user("card").copy(annotations = listOf(it)) }
        val prompt = interactionStatePrompt(messages, true, false)
        assertTrue(prompt.contains("respond_to_payment"))
        assertTrue(prompt.contains("action=accept"))
        assertTrue(prompt.contains("id=receive-me"))
        assertFalse(prompt.contains("already-done"))
        assertFalse(prompt.contains("assistant-gift"))
        val noTools = interactionStatePrompt(messages, true, false, paymentToolsAvailable = false)
        assertTrue(noTools.contains("本轮未提供"))
        assertFalse(noTools.contains("本轮已提供"))
        assertFalse(noTools.contains("send_virtual_red_packet"))
    }

    @Test fun virtualUserGiftCanActuallyBeAcceptedThroughTheDeclaredTool() = runBlocking {
        val card = ChatInteraction("received", "c", "a", "user", "red_packet", walletLinked = false)
        var invoked = false
        val tools = buildRichChatTools(false, { _, _, _, _ -> error("must not create") },
            { id, action ->
                assertEquals(card.id, id)
                validateChatInteractionAction(card, "assistant", action)
                assertEquals("accept", action)
                invoked = true
                card.copy(status = "accepted")
            }, pretendRich = false)
        val result = tools.single { it.name == "respond_to_payment" }.execute(
            buildJsonObject { put("id", card.id); put("action", "accept") })
        assertTrue(invoked)
        val receipt = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("accepted", receipt.getValue("status").jsonPrimitive.content)
        assertFalse(receipt.getValue("walletLinked").jsonPrimitive.boolean)
    }

    @Test fun missingToolCapabilityAndGoogleBuiltInsHaveActionableReasons() {
        val model = me.rerere.ai.provider.Model(modelId = "custom")
        assertTrue(paymentToolUnavailableReason(model)!!.contains("未开启工具调用"))
        val enabled = model.copy(abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL))
        assertNull(paymentToolUnavailableReason(enabled))
        val search = enabled.copy(tools = setOf(me.rerere.ai.provider.BuiltInTools.Search))
        assertTrue(paymentToolUnavailableReason(search, me.rerere.ai.provider.ProviderSetting.Google())!!.contains("内置"))
        assertNull(paymentToolUnavailableReason(search, me.rerere.ai.provider.ProviderSetting.Google(useInteractionsApi = true)))
        assertNull(paymentToolUnavailableReason(model, me.rerere.ai.provider.ProviderSetting.Codex()))
    }

    @Test fun paymentToolsExposeOnlyTheSelectedModeBeforeExecution() {
        for (virtual in listOf(false, true)) {
            val tools = buildRichChatTools(false, { _, _, _, _ -> error("must not execute") },
                { _, _ -> error("must not execute") }, pretendRich = virtual)
            val mode = if (virtual) "virtual" else "wallet"
            assertEquals(setOf("send_${mode}_red_packet", "send_${mode}_transfer", "respond_to_payment"), tools.map { it.name }.toSet())
        }
    }

    @Test fun userModeIsIndependentAndStaleAssistantToolsCannotChangePaymentNature() {
        for (pretend in listOf(false, true)) {
            assertTrue(resolvePaymentWalletMode("user", pretend, true))
            assertFalse(resolvePaymentWalletMode("user", pretend, false))
            assertEquals(!pretend, resolvePaymentWalletMode("assistant", pretend, !pretend))
            assertTrue(runCatching { resolvePaymentWalletMode("assistant", pretend, pretend) }.isFailure)
        }
    }
    private fun call(status: String) = UIMessage.assistant("old invitation").copy(annotations = listOf(
        ChatInteraction("call", "c", "a", "assistant", "call", status = status)))

    @Test fun hangupOverridesOldInvitationEvenWhenHistoryWasNotRewrittenYet() {
        val ended = interactionStatePrompt(listOf(call("ended")), false, false)
        assertTrue(ended.contains("已挂断，通话已结束"))
        val race = interactionStatePrompt(listOf(call("accepted")), false, false)
        assertTrue(race.contains("没有接通中的语音通话"))
        assertFalse(race.contains("仍在进行"))
        assertTrue(interactionStatePrompt(listOf(call("accepted")), false, true).contains("仍在进行"))
    }

    @Test fun pendingAndDeclinedInvitationsAreNotConnected() {
        assertTrue(interactionStatePrompt(listOf(call("pending")), false, false).contains("等待接听，尚未接通"))
        assertTrue(interactionStatePrompt(listOf(call("declined")), false, false).contains("未接通，邀请已拒绝"))
    }

    @Test fun walletModeIsExplicitInToolDescriptionAndActualReceipt() = runBlocking {
        for (pretend in listOf(false, true)) {
            val tools = buildRichChatTools(false, { kind, _, _, _ ->
                ChatInteraction("p", "c", "a", "assistant", kind, walletLinked = !pretend)
            }, { _, _ -> error("unused") }, pretendRich = pretend)
            for (tool in tools.filter { it.name.startsWith("send_") }) {
                assertTrue(tool.description.contains(if (pretend) "已开启" else "已关闭"))
                val result = tool.execute(buildJsonObject { put("amount", "1.00"); put("request_id", "p") })
                val receipt = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
                assertEquals(!pretend, receipt.getValue("walletLinked").jsonPrimitive.boolean)
                assertEquals(if (pretend) "virtual" else "linked", receipt.getValue("wallet_mode").jsonPrimitive.content)
                assertEquals("pending", receipt.getValue("status").jsonPrimitive.content)
            }
        }
    }

    @Test fun existingPaymentModeWinsOverCurrentSetting() {
        val result = Json.parseToJsonElement(chatInteractionToolReceipt(
            ChatInteraction("old", "c", "a", "user", "transfer", status = "unconfirmed", walletLinked = true))).jsonObject
        assertEquals("linked", result.getValue("wallet_mode").jsonPrimitive.content)
        assertEquals("unconfirmed", result.getValue("status").jsonPrimitive.content)
    }

    @Test fun jumpIsHiddenInEveryAssistantTextPartButRawControlAndUserQuotesRemain() {
        val raw = UIMessage.assistant("收到").copy(parts = listOf(UIMessagePart.Text("收到"),
            UIMessagePart.Text("[jump]"), UIMessagePart.Text("晚安[JUMP]")))
        assertEquals(2, raw.withoutJumpControl().parts.size)
        assertFalse(raw.withoutJumpControl().toText().contains("jump", true))
        assertTrue(raw.toText().contains("[jump]"))
        val user = UIMessage.user("为什么会有[JUMP]")
        assertEquals(user, user.withoutJumpControl())
    }
}
