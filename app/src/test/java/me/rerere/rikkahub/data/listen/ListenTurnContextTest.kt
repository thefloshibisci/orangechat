package me.rerere.rikkahub.data.listen

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ListenTurnContextTest {
    @Test fun movingTimeOnlyChangesTheTriggeringTurn() {
        val msgs = listOf(UIMessage.system("stable"), UIMessage.user("old"), UIMessage.assistant("old reply"), UIMessage.user("now"))
        val first = ListenTurnContext(msgs.last().id, "3 秒") { true }.attach(msgs)
        val next = ListenTurnContext(msgs.last().id, "17 秒") { true }.attach(msgs)
        (0 until msgs.lastIndex).forEach { assertSame(msgs[it], first[it]); assertSame(first[it], next[it]) }
        assertNotEquals(first.last().parts, next.last().parts)
        assertEquals(msgs.last().parts, first.last().parts.dropLast(1))
        assertEquals(1, msgs.last().parts.size)
    }

    @Test fun frozenAcrossToolStepsAndNoStaleFallbackOrRevokedDisclosure() {
        val u = UIMessage.user("now")
        val after = UIMessage.assistant("tool step")
        val nextUser = UIMessage.user("not the trigger")
        val history = listOf(u, after, nextUser)
        var allowed = true
        val turn = ListenTurnContext(u.id, "<lyrics> & title") { allowed }
        val sent = turn.attach(history)
        assertSame(after, sent[1]); assertSame(nextUser, sent[2]); assertEquals(sent, turn.attach(history))
        assertTrue((sent.first().parts.last() as UIMessagePart.Text).text.contains("&lt;lyrics&gt; &amp; title"))
        val truncated = history.drop(1); assertSame(truncated, turn.attach(truncated))
        allowed = false; assertSame(history, turn.attach(history))
    }
}
