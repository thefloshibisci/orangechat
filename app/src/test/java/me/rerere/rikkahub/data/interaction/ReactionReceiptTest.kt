package me.rerere.rikkahub.data.interaction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.withExecutionCheckpoint
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.service.beginProactiveRequest
import org.junit.Assert.*
import org.junit.Test

class ReactionReceiptTest {
    private val assistant = Assistant(enableMessageReactions = true, splitBubbleByLine = true)
    private val started = LocalDateTime(2026, 10, 25, 12, 0)
    private val millis = started.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    private val user = UIMessage.user("你好")
    private val oldReply = UIMessage.assistant("你好呀").copy(createdAt = started, finishedAt = started)
    private val latest = UIMessage.user("继续聊聊")
    private val history = listOf(user, oldReply, latest)
    private val chat = "conversation"

    private fun reaction(at: Long = millis + 1_000, emoji: String = "ᵔ ᵕ ᵔ", actor: String = "user") =
        MessageReaction("$at:$emoji:$actor", chat, oldReply.id.toString(), actor, emoji, "你好呀", at)

    private fun turn(
        messages: List<UIMessage> = history,
        reactions: List<MessageReaction> = listOf(reaction()),
        selected: Assistant = assistant,
        conversationId: String? = chat,
        request: List<UIMessage> = messages,
    ) = prepareReactionContext(messages, reactions, conversationId, selected, request)

    private fun reply(text: String = "收到啦") = UIMessage.assistant(text).copy(
        createdAt = LocalDateTime(2026, 10, 25, 12, 1),
        finishedAt = LocalDateTime(2026, 10, 25, 12, 2),
    )

    private fun complete(turn: ReactionContextTurn, value: UIMessage = reply(), source: List<UIMessage> = history): UIMessage {
        turn.onGenerationSucceeded(value)
        return turn.finish(value, source + value)
    }

    private fun text(messages: List<UIMessage>) = messages.flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    @Test fun `success acknowledges once without changing text or visible reaction`() {
        val event = reaction()
        val original = history.toList()
        val generation = turn(reactions = listOf(event))
        assertTrue(text(generation.apply(history)).contains(event.emoji))
        val completed = complete(generation)
        assertEquals(setOf(reactionReceipt(event)), completed.reactionReceipts(chat, assistant.id.toString()))
        assertEquals("收到啦", completed.toText())
        assertEquals(original, history)
        assertEquals(listOf(event), generation.events)
        val next = history + completed + UIMessage.user("换个话题")
        assertTrue(turn(next).events.isEmpty())
        assertFalse(text(turn(next).apply(next)).contains("<message_interactions>"))
    }

    @Test fun `failure and partial text never create a receipt and retry still gets event`() {
        val generation = turn()
        generation.apply(history)
        val partial = reply().copy(finishedAt = null)
        generation.onGenerationSucceeded(partial)
        assertEquals(partial, generation.finish(partial, history + partial))
        val failed = turn()
        failed.apply(history)
        assertTrue(reply().let { failed.finish(it, history + it) }.reactionReceipts(chat, assistant.id.toString()).isEmpty())
        assertEquals(listOf(reaction()), turn().events)
    }

    @Test fun `failed continuation with inherited finished time stays unread instead of advancing legacy floor`() {
        val generation = turn()
        generation.apply(history)
        val inherited = reply().copy(parts = listOf(
            UIMessagePart.Tool("call", "lookup", "{}", listOf(UIMessagePart.Text("result")))
                .withExecutionCheckpoint("completed"),
            UIMessagePart.Text("只生成了一半"),
        ))
        assertTrue(inherited.isReactionReplyComplete())
        val partial = generation.markPending(history + inherited).last()
        assertNotNull(partial.finishedAt)
        assertTrue(partial.parts.any { it.metadata?.get("orange_reaction_pending_v1") == JsonPrimitive(true) })
        assertSame(partial, generation.finish(partial, history + partial))
        assertEquals(listOf(reaction()), turn(history + partial).events)
    }

    @Test fun `successful continuation removes pending marker and retains earlier receipts`() {
        val first = turn()
        first.apply(history)
        val existing = complete(first)
        val secondEvent = reaction(millis + 3_000, "👍")
        val source = history + existing
        val second = turn(source, listOf(reaction(), secondEvent))
        second.apply(source)
        val raw = existing.copy(parts = existing.parts + UIMessagePart.Text("接着说"))
        val pending = second.markPending(source.dropLast(1) + raw).last()
        assertEquals(setOf(reactionReceipt(reaction())), pending.reactionReceipts(chat, assistant.id.toString()))
        // The completion owner may retain its unmarked provider copy; pending metadata is not content identity.
        second.onGenerationSucceeded(raw)
        val completed = second.finish(pending, source.dropLast(1) + pending)
        assertEquals(setOf(reactionReceipt(reaction()), reactionReceipt(secondEvent)),
            completed.reactionReceipts(chat, assistant.id.toString()))
        assertFalse(Json.encodeToString(UIMessage.serializer(), completed).contains("orange_reaction_pending_v1"))
        assertTrue(turn(history + completed, listOf(reaction(), secondEvent)).events.isEmpty())
    }

    @Test fun `pending marker is stripped from tool request metadata while execution evidence stays`() {
        val generation = turn()
        val candidate = reply().copy(parts = listOf(
            UIMessagePart.Tool("call", "lookup", "{}", listOf(UIMessagePart.Text("result")))
                .withExecutionCheckpoint("completed"),
            UIMessagePart.Text("部分回答"),
        ))
        val marked = generation.markPending(history + candidate)
        assertTrue(Json.encodeToString(UIMessage.serializer(), marked.last()).contains("orange_reaction_pending_v1"))
        val request = generation.apply(marked)
        assertFalse(Json.encodeToString(UIMessage.serializer(), request.last()).contains("orange_reaction_pending_v1"))
        assertEquals(candidate.getTools(), request.last().getTools())
        assertEquals(history, generation.markPending(history))
    }

    @Test fun `cancellation before successful loop completion keeps request unread`() = runBlocking {
        val generation = turn()
        val job = launch {
            generation.apply(history)
            yield()
            throw CancellationException("request cancelled")
        }
        yield()
        job.cancelAndJoin()
        val candidate = reply()
        assertSame(candidate, generation.finish(candidate, history + candidate))
        assertEquals(listOf(reaction()), turn().events)
    }

    @Test fun `snapshot is fixed across tools and late reactions wait for next reply`() {
        val first = reaction()
        val later = reaction(millis + 2_000, "👍")
        val generation = turn(reactions = listOf(first))
        val request = generation.apply(history)
        assertEquals(text(request), text(generation.apply(request)))
        assertEquals(1, "<message_interactions>".toRegex().findAll(text(generation.apply(request))).count())
        val complete = complete(generation)
        assertEquals(setOf(reactionReceipt(first)), complete.reactionReceipts(chat, assistant.id.toString()))
        assertEquals(listOf(later), turn(history + complete, listOf(first, later)).events)
    }

    @Test fun `approvals tool-only batches and failed executed tools cannot acknowledge`() {
        val tool = UIMessagePart.Tool("call", "lookup", "{}")
        val variants = listOf(
            listOf<UIMessagePart>(tool),
            listOf(UIMessagePart.Text("我来查一下"), tool),
            listOf(tool.copy(output = listOf(UIMessagePart.Text("result")))),
            listOf(tool.copy(output = listOf(UIMessagePart.Text("error"))).withExecutionCheckpoint("failed"),
                UIMessagePart.Text("出了点问题")),
        )
        variants.forEach { parts ->
            val generation = turn()
            generation.apply(history)
            val candidate = reply().copy(parts = parts)
            assertSame(candidate, complete(generation, candidate))
        }
    }

    @Test fun `failed earlier tool in a separate message cannot be hidden by final text`() {
        val generation = turn()
        generation.apply(history)
        val failedTool = reply().copy(parts = listOf(
            UIMessagePart.Tool("call", "lookup", "{}", listOf(UIMessagePart.Text("error")))
                .withExecutionCheckpoint("failed"),
        ))
        val candidate = reply()
        assertSame(candidate, complete(generation, candidate, history + failedTool))
    }

    @Test fun `successful tool continuation with a final visible answer can acknowledge`() {
        val generation = turn()
        generation.apply(history)
        val candidate = reply().copy(parts = listOf(
            UIMessagePart.Tool("call", "lookup", "{}", listOf(UIMessagePart.Text("result")))
                .withExecutionCheckpoint("completed"),
            UIMessagePart.Text("收到啦"),
        ))
        val completed = complete(generation, candidate)
        assertEquals(setOf(reactionReceipt(reaction())), completed.reactionReceipts(chat, assistant.id.toString()))
    }

    @Test fun `resumed existing assistant message retains previous successful receipts`() {
        val first = turn()
        first.apply(history)
        val existing = complete(first)
        val secondEvent = reaction(millis + 3_000, "👍")
        val source = history + existing
        val second = turn(source, listOf(reaction(), secondEvent))
        second.apply(source)
        val continued = existing.copy(parts = existing.parts + UIMessagePart.Text("继续说"))
        val complete = complete(second, continued, source)
        assertEquals(setOf(reactionReceipt(reaction()), reactionReceipt(secondEvent)),
            complete.reactionReceipts(chat, assistant.id.toString()))
    }

    @Test fun `unchanged old reply empty continuation and switched alternative are not owned`() {
        val source = listOf(user, oldReply)
        val generation = turn(source)
        generation.apply(source)
        assertSame(oldReply, complete(generation, oldReply, source))
        val expected = reply()
        generation.onGenerationSucceeded(expected)
        val alternative = reply("之前生成的另一分支")
        assertSame(alternative, generation.finish(alternative, history + alternative))
    }

    @Test fun `selected branch only sees its own targets and successful receipts`() {
        val generation = turn()
        generation.apply(history)
        val selected = complete(generation)
        val alternativeTurn = turn(reactions = emptyList())
        val alternative = complete(alternativeTurn, reply("另一种回复"))
        val node = MessageNode(messages = listOf(selected, alternative))
        val selectedHistory = history + node.currentMessage
        assertTrue(turn(selectedHistory).events.isEmpty())
        val alternativeHistory = history + node.copy(selectIndex = 1).currentMessage
        assertEquals(listOf(reaction()), turn(alternativeHistory).events)
        val hidden = reaction().copy(messageId = alternative.id.toString(), quote = "另一种回复")
        assertTrue(turn(selectedHistory, listOf(hidden)).events.isEmpty())
    }

    @Test fun `conversation and assistant scopes do not borrow another owners receipts`() {
        val generation = turn()
        generation.apply(history)
        val completed = complete(generation)
        val other = assistant.copy(id = kotlin.uuid.Uuid.random())
        assertEquals(listOf(reaction()), turn(history + completed, selected = other).events)
        assertEquals(listOf(reaction().copy(conversationId = "other")),
            turn(history + completed, listOf(reaction().copy(conversationId = "other")), conversationId = "other").events)
        val candidate = reply()
        generation.onGenerationSucceeded(candidate)
        assertSame(candidate, generation.finish(candidate, history + candidate, "other", assistant.id.toString()))
        assertSame(candidate, generation.finish(candidate, history + candidate, chat, other.id.toString()))
    }

    @Test fun `fork rebase keeps copied receipts but does not alter source or unrelated scope`() {
        val generation = turn()
        generation.apply(history)
        val completed = complete(generation)
        val rebased = completed.rebaseReactionReceipts(chat, "fork", assistant.id.toString())
        assertEquals(completed.parts.map { (it as UIMessagePart.Text).text }, rebased.parts.map { (it as UIMessagePart.Text).text })
        assertTrue(turn(history + rebased, listOf(reaction().copy(id = "fork-key", conversationId = "fork")), conversationId = "fork").events.isEmpty())
        assertTrue(completed.reactionReceipts("fork", assistant.id.toString()).isEmpty())
        assertSame(completed, completed.rebaseReactionReceipts("unrelated", "fork", assistant.id.toString()))
    }

    @Test fun `same millisecond remove and add has a new version even with same deterministic primary key`() {
        val firstTime = nextReactionCreatedAt(millis)
        val first = reaction(firstTime).copy(id = "same-key")
        val generation = turn(reactions = listOf(first))
        generation.apply(history)
        val complete = complete(generation)
        val secondTime = nextReactionCreatedAt(millis, listOf(complete).latestReactionReceiptTime())
        val second = first.copy(createdAt = secondTime)
        assertTrue(secondTime > firstTime)
        assertNotEquals(reactionReceipt(first), reactionReceipt(second))
        assertEquals(listOf(second), turn(history + complete, listOf(second)).events)
    }

    @Test fun `assistant authored reactions are not reflected back as new user notices`() {
        assertTrue(turn(reactions = listOf(reaction(actor = "assistant"))).events.isEmpty())
    }

    @Test fun `edited target invalidates old quote and a midrequest edit is not acknowledged`() {
        val generation = turn()
        generation.apply(history)
        val edited = oldReply.copy(parts = listOf(UIMessagePart.Text("改过的原句")))
        val current = listOf(user, edited, latest)
        assertTrue(turn(current).events.isEmpty())
        assertTrue(complete(generation, source = current).reactionReceipts(chat, assistant.id.toString()).isEmpty())
        val editedEvent = reaction().copy(quote = "改过的原句")
        assertEquals(listOf(editedEvent), turn(current, listOf(editedEvent)).events)
    }

    @Test fun `editing or switching the triggering user message cannot consume its request snapshot`() {
        val generation = turn()
        generation.apply(history)
        val candidate = reply()
        val edited = latest.copy(parts = listOf(UIMessagePart.Text("换一个问题")))
        assertSame(candidate, complete(generation, candidate, history.dropLast(1) + edited))
        assertSame(candidate, complete(generation, candidate, history.dropLast(1) + UIMessage.user("不同分支")))
    }

    @Test fun `missing request anchor disabled reactions and absent conversation never acknowledge`() {
        val generation = turn()
        generation.apply(listOf(UIMessage.system("user was truncated")))
        assertTrue(complete(generation).reactionReceipts(chat, assistant.id.toString()).isEmpty())
        assertTrue(turn(selected = assistant.copy(enableMessageReactions = false)).events.isEmpty())
        assertTrue(turn(conversationId = null).events.isEmpty())
    }

    @Test fun `upgrade floor uses completed legacy replies and stays fixed after empty modern reply`() {
        val old = reaction(millis - 1)
        val fresh = reaction(millis + 1)
        assertEquals(listOf(fresh), turn(reactions = listOf(old, fresh)).events)
        val empty = turn(reactions = emptyList())
        empty.apply(history)
        val modern = complete(empty)
        // The late event predates modern.createdAt but was never delivered in its snapshot.
        assertEquals(listOf(fresh), turn(history + modern, listOf(old, fresh)).events)
        val partialLegacy = oldReply.copy(finishedAt = null)
        assertEquals(listOf(old), turn(listOf(user, partialLegacy, latest), listOf(old)).events)
    }

    @Test fun `proactive synthetic trigger cannot relabel a consumed reaction as new`() {
        val generation = turn()
        generation.apply(history)
        val completed = complete(generation)
        val real = history + completed
        val proactive = beginProactiveRequest(real, "time context")
        val next = turn(real, request = proactive)
        assertTrue(next.events.isEmpty())
        assertFalse(text(next.apply(proactive)).contains("<message_interactions>"))
    }

    @Test fun `proactive pass wait stop and skip remain unread while a visible send consumes once`() {
        for (decision in listOf("[PASS]", "[WAIT:20]", "[STOP]", "[SKIP]", "[JUMP]")) {
            val proactive = beginProactiveRequest(history)
            val generation = turn(request = proactive)
            generation.apply(proactive)
            val candidate = reply(decision)
            assertSame(candidate, complete(generation, candidate))
            assertEquals(listOf(reaction()), turn().events)
        }
        val proactive = beginProactiveRequest(history)
        val generation = turn(request = proactive)
        generation.apply(proactive)
        assertEquals(setOf(reactionReceipt(reaction())), complete(generation).reactionReceipts(chat, assistant.id.toString()))
    }

    @Test fun `stored metadata survives conversation backup without appearing in wire text or exports`() {
        val generation = turn()
        generation.apply(history)
        val original = reply().copy(parts = listOf(UIMessagePart.Text("保留原文", buildJsonObject { put("existing", "keep") })))
        val completed = complete(generation, original)
        val conversation = Conversation(assistantId = assistant.id, messageNodes = (history + completed).map { MessageNode.of(it) })
        val json = Json { ignoreUnknownKeys = true }
        val restored = json.decodeFromString(Conversation.serializer(), json.encodeToString(Conversation.serializer(), conversation))
        assertEquals(completed, restored.currentMessages.last())
        assertEquals("保留原文", restored.currentMessages.last().toText())
        val request = turn(restored.currentMessages, reactions = emptyList()).apply(restored.currentMessages)
        assertEquals(JsonPrimitive("keep"), request.last().parts.first().metadata?.get("existing"))
        assertFalse(json.encodeToString(UIMessage.serializer(), request.last()).contains("orange_reaction_receipt_v1"))
        assertTrue(turn(restored.currentMessages).events.isEmpty())
    }

    @Test fun `more than one batch leaves undelivered reactions for following replies`() {
        val events = (1..30).map { reaction(millis + it * 1000L, "reaction$it") }
        val generation = turn(reactions = events)
        assertEquals(24, generation.events.size)
        generation.apply(history)
        val completed = complete(generation)
        assertEquals(events.take(6), turn(history + completed, events).events)
    }

    @Test fun `media reply can retain receipt without manufacturing empty text`() {
        val generation = turn()
        generation.apply(history)
        val candidate = reply().copy(parts = listOf(UIMessagePart.Image("file:///test.png")))
        val completed = complete(generation, candidate)
        assertEquals(1, completed.parts.size)
        assertTrue(completed.parts.single() is UIMessagePart.Image)
        assertEquals(setOf(reactionReceipt(reaction())), completed.reactionReceipts(chat, assistant.id.toString()))
        assertNull(generation.apply(history + completed).last().parts.single().metadata)
    }
}
