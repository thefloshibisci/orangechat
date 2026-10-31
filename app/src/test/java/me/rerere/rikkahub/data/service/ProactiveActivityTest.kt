package me.rerere.rikkahub.data.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class ProactiveActivityTest {
    private val pending = ProactiveActivity("run-1", 123, "等待", "对方说在学习")

    @Test fun `note is separate from message and wait protocol`() {
        val result = parseProactiveDecision("[NOTE:对方说在学习]\n[WAIT:45]", false)
        assertFalse(result.shouldSend)
        assertEquals(45, result.waitMinutes)
        assertEquals("对方说在学习", result.activityNote)
        assertEquals("", result.message)
    }

    @Test fun `note never becomes notification text`() {
        val result = parseProactiveDecision("[NOTE:分享新发现]\n刚看到一件有趣的事", false)
        assertTrue(result.shouldSend)
        assertEquals("刚看到一件有趣的事", result.message)
    }

    @Test fun `note does not turn an internal jump into a real jump`() {
        val result = parseProactiveDecision("[NOTE:不要用[JUMP]]\n[PASS]", false)
        assertFalse(result.shouldJump)
        assertFalse(result.shouldSend)
    }

    @Test fun `notes are bounded and reasoning is not archived`() {
        val result = parseProactiveDecision("[NOTE:${"x".repeat(300)}]\n[STOP]", "private reasoning", false)
        assertEquals(240, result.activityNote.length)
        assertFalse(result.activityNote.contains("private reasoning"))
    }

    @Test fun `snapshot survives serialization before acknowledgement`() {
        val saved = Json.encodeToString(listOf(pending))
        assertEquals(listOf(pending), Json.decodeFromString<List<ProactiveActivity>>(saved))
    }

    @Test fun `claim touches only snapshot ids and is idempotent`() {
        val newer = pending.copy(id = "run-2")
        val claimed = claimProactiveActivities(listOf(pending, newer), setOf(pending.id), "user-1")
        assertEquals("user-1", claimed.first().claimedByUserId)
        assertNull(claimed.last().claimedByUserId)
        assertEquals(claimed, claimProactiveActivities(claimed, setOf(pending.id), "user-2"))
    }

    @Test fun `pending injection does not mutate stored messages or system prefix`() {
        val system = UIMessage.system("stable persona")
        val user = UIMessage.user("回来了")
        val original = listOf(system, user)
        val injected = injectProactiveActivities(original, listOf(pending), user.id.toString())
        assertEquals(system, injected.first())
        assertEquals(1, user.parts.size)
        assertEquals(2, injected.last().parts.size)
        assertEquals(original, injectProactiveActivities(original, listOf(pending), null))
    }

    @Test fun `claimed history stays at original turn without reinjecting at new turn`() {
        val first = UIMessage.user("回来了")
        val next = UIMessage.user("继续")
        val claimed = claimProactiveActivities(listOf(pending), setOf(pending.id), first.id.toString())
        val before = injectProactiveActivities(listOf(first), listOf(pending), first.id.toString()).first()
        val after = injectProactiveActivities(listOf(first, next), claimed, next.id.toString())
        assertEquals(before, after.first())
        assertEquals(next, after.last())
    }

    @Test fun `record is not injected into unrelated branch`() {
        val claimed = pending.copy(claimedByUserId = "another-user-turn")
        val user = UIMessage.user("别的分支")
        assertEquals(listOf(user), injectProactiveActivities(listOf(user), listOf(claimed), user.id.toString()))
    }

    @Test fun `later activity cannot be attached to an earlier user turn`() {
        val user = UIMessage.user("回来了")
        val future = pending.copy(timestamp = Long.MAX_VALUE)
        assertEquals(listOf(user), injectProactiveActivities(listOf(user), listOf(future), user.id.toString()))
    }

    @Test fun `unfinished activity note is never delivered as a chat message`() {
        val result = parseProactiveDecision("[NOTE:还没写完", false)
        assertFalse(result.shouldSend)
        assertEquals("", result.message)
    }

    @Test fun `intermediate tool text never exposes note protocol`() {
        assertEquals("查到了一条记录", stripProactiveNotes("[NOTE:回顾记忆]\n查到了一条记录"))
    }

    @Test fun `old claimed turns stay byte-identical after many new background records`() {
        val first = UIMessage.user("早")
        val later = UIMessage.user("回来了")
        var records = trimProactiveActivities(
            listOf(pending.copy(claimedByUserId = first.id.toString())),
        )
        val before = injectProactiveActivities(listOf(first), records, null).first()
        repeat(200) { n ->
            records = trimProactiveActivities(records + ProactiveActivity("run-x$n", 1000L + n, "这轮没有发消息"))
        }
        records = claimProactiveActivities(records, records.filter { it.claimedByUserId == null }.mapTo(HashSet()) { it.id }, later.id.toString())
        repeat(200) { n ->
            records = trimProactiveActivities(records + ProactiveActivity("run-y$n", 5000L + n, "决定再等30分钟"))
        }
        val after = injectProactiveActivities(listOf(first, later), records, null)
        assertEquals(before, after.first())
        assertEquals(64, records.count { it.claimedByUserId == null })
    }

    @Test fun `trim drops oldest pending before any claimed record`() {
        val claimed = (0 until 3).map { ProactiveActivity("c$it", it.toLong(), "已发送主动消息", claimedByUserId = "u") }
        val pendings = (0 until 5).map { ProactiveActivity("p$it", 10L + it, "等待") }
        val trimmed = trimProactiveActivities(claimed + pendings, maxPending = 2, maxClaimed = 10)
        assertEquals(listOf("c0", "c1", "c2", "p3", "p4"), trimmed.map { it.id })
    }

    @Test fun `claimed records are never evicted by default`() {
        val records = (0..1100).map {
            ProactiveActivity("c$it", it.toLong(), "已发送主动消息", claimedByUserId = "user-$it")
        }
        assertEquals(records, trimProactiveActivities(records))
    }

    @Test fun `tool retry keeps activity bound to the same user turn`() {
        val user = UIMessage.user("检查一下")
        val bound = claimProactiveActivities(listOf(pending), setOf(pending.id), user.id.toString())
        val firstRequest = injectProactiveActivities(listOf(user), bound, user.id.toString())
        val toolRetry = injectProactiveActivities(listOf(user), bound, user.id.toString())
        val nextTurn = UIMessage.user("继续")
        val laterRequest = injectProactiveActivities(listOf(user, nextTurn), bound, nextTurn.id.toString())
        assertEquals(firstRequest, toolRetry)
        assertEquals(firstRequest.first(), laterRequest.first())
        assertEquals(nextTurn, laterRequest.last())
    }
}
