package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.interaction.ChatInteraction
import me.rerere.rikkahub.data.interaction.readableText
import org.junit.Assert.*
import org.junit.Test

class InteractionOutputTransformerTest {
    private val card = ChatInteraction("ea9f9b54-d2eb-3600-9c30-b7fcb31a4fa7", "c", "a", "assistant",
        "red_packet", amountCents = 1, note = "测试红包：辛苦啦！", walletLinked = true)

    @Test fun screenshotEchoDisappearsButNaturalReplyRemains() {
        val result = interactionTranscript(UIMessage.assistant("快拆开看看动效和弹窗顺不顺畅！" + card.readableText()))
        assertEquals("快拆开看看动效和弹窗顺不顺畅！", result.toText())
    }
    @Test fun multipleRecordsDoNotConsumeFollowingWords() {
        val raw = "红包来咯～\n" + card.readableText() + "\n收好啦。" + card.copy(kind = "transfer").readableText()
        assertEquals("红包来咯～\n\n收好啦。", interactionTranscript(UIMessage.assistant(raw)).toText())
    }
    @Test fun durableCardsUserTextAndToolResultsArePreserved() {
        val annotated = UIMessage.assistant(card.readableText()).copy(annotations = listOf(card))
        assertEquals(annotated, interactionTranscript(annotated))
        val user = UIMessage.user(card.readableText())
        assertEquals(user, interactionTranscript(user))
        val tool = UIMessagePart.Tool("call", "send_red_packet", "{}", listOf(UIMessagePart.Text(card.readableText())))
        val message = UIMessage.assistant("收到红包啦").copy(parts = listOf(tool, UIMessagePart.Text("收到红包啦")))
        assertEquals(message, interactionTranscript(message))
        val quote = UIMessage.assistant("你说的[AI发起红包]是什么？")
        assertEquals(quote, interactionTranscript(quote))
    }
    @Test fun incompleteStreamingRecordIsHidden() {
        val message = UIMessage.assistant("给你。 [AI发起红包] ¥0.01（关联小钱包） · 待领取")
        assertEquals("给你。", interactionTranscript(message, streaming = true).toText())
    }
}
