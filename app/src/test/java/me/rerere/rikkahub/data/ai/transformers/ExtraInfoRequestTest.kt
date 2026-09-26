package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ExtraInfoRequestTest {
    @Test fun `switching master off discards cached time and never collects on retry`() = runBlocking {
        val cache = ExtraInfoRequestCache()
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val user = UIMessage.user("hello")
        val enabled = SystemToolsSetting(timeContextInjectionEnabled = true)
        assertEquals("old time", cache.resolve(conversation, assistant, user, enabled, true) { "old time" })
        for (allowCollection in listOf(false, true)) {
            val result = cache.resolve(conversation, assistant, user,
                enabled.copy(extraInfoInjectionEnabled = false), allowCollection) { error("must not collect") }
            assertNull(result)
            assertEquals(listOf(user), attachExtraInfoToRequest(listOf(user), user.id, result))
        }
    }

    @Test fun `request-only injection leaves system history ids and tool chain unchanged`() {
        val user = UIMessage.user("question")
        val tool = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("call", "lookup", "{}", listOf(UIMessagePart.Text("result"))),
        ))
        val history = listOf(UIMessage.system("stable prefix"), user, tool)
        val request = attachExtraInfoToRequest(history, user.id, "snapshot")
        assertEquals(3, request.size)
        assertEquals(history.first(), request.first())
        assertEquals(tool, request.last())
        assertEquals(user.id, request[1].id)
        assertEquals("question", user.toText())
        assertEquals(1, request[1].parts.count { it.isExtraInfoInjectionPart() })
        assertEquals(request, attachExtraInfoToRequest(request, user.id, "snapshot"))
        assertEquals(history, attachExtraInfoToRequest(history, Uuid.random(), "snapshot"))
    }

    @Test fun `cached snapshot survives retry and approval without re-reading device`() = runBlocking {
        val cache = ExtraInfoRequestCache()
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val user = UIMessage.user("hello")
        var calls = 0
        repeat(3) { index ->
            assertEquals("first", cache.resolve(conversation, assistant, user, SystemToolsSetting(), index == 0) {
                calls++
                "first"
            })
        }
        assertEquals(1, calls)
    }

    @Test fun `failed source snapshot is cached but changed user settings and assistant are not`() = runBlocking {
        val cache = ExtraInfoRequestCache()
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val user = UIMessage.user("hello")
        val option = SystemToolsSetting()
        assertNull(cache.resolve(conversation, assistant, user, option, true) { null })
        assertNull(cache.resolve(conversation, assistant, user, option, true) { error("must reuse empty") })
        val edited = user.copy(parts = listOf(UIMessagePart.Text("edited")))
        assertEquals("edited", cache.resolve(conversation, assistant, edited, option, true) { "edited" })
        assertEquals("changed", cache.resolve(conversation, assistant, edited,
            option.copy(batteryContextInjectionEnabled = true), true) { "changed" })
        assertEquals("other", cache.resolve(conversation, Uuid.random(), edited, option, true) { "other" })
    }

    @Test fun `disabled missing continuation and evicted entries never start collection`() = runBlocking {
        val cache = ExtraInfoRequestCache(1)
        val assistant = Uuid.random()
        val user = UIMessage.user("hello")
        val first = Uuid.random()
        val second = Uuid.random()
        val option = SystemToolsSetting()
        assertNull(cache.resolve(first, assistant, user, option, false) { error("no collection") })
        cache.resolve(first, assistant, user, option, true) { "first" }
        cache.resolve(second, assistant, user, option, true) { "second" }
        assertNull(cache.resolve(first, assistant, user, option, false) { error("evicted") })
        assertNull(cache.resolve(second, assistant, user, option.copy(extraInfoInjectionEnabled = false), true) {
            error("disabled")
        })
    }

    @Test fun `cancelled collection is never cached`() = runBlocking {
        val cache = ExtraInfoRequestCache()
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val user = UIMessage.user("hello")
        try {
            cache.resolve(conversation, assistant, user, SystemToolsSetting(), true) { throw CancellationException() }
            fail("must cancel")
        } catch (_: CancellationException) { }
        assertEquals("retry", cache.resolve(conversation, assistant, user, SystemToolsSetting(), true) { "retry" })
    }
}
