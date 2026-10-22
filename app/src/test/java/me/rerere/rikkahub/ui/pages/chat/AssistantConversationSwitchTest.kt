package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantConversationSwitchTest {
    @Test
    fun `new chat cannot initialize from the old assistant while selection is still saving`() = runBlocking {
        val oldAssistant = Uuid.random()
        val requestedAssistant = Uuid.random()
        var diskAssistant = oldAssistant
        val writeStarted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        var opened: Conversation? = null
        val sessionScope = CoroutineScope(coroutineContext + SupervisorJob())
        val switch = async(start = CoroutineStart.UNDISPATCHED) {
            val id = selectAssistantConversation(
                assistantId = requestedAssistant,
                createNew = true,
                persistSelection = {
                    writeStarted.complete(Unit)
                    finishWrite.await()
                    diskAssistant = it
                },
                findConversation = { error("New chat must not look up history") },
            )
            // The destination screen initializes from persisted selection, like ChatService.
            val session = ConversationSession(
                id, Conversation.ofId(id, oldAssistant).copy(messageNodesLoaded = false),
                sessionScope, debugLog = {}, onIdle = {},
            )
            try {
                opened = session.initialize(load = { null }, create = {
                    Conversation.ofId(id, diskAssistant, newConversation = true)
                        .updateCurrentMessages(listOf(UIMessage.user("requested assistant preset")))
                })
            } finally {
                session.cleanup()
            }
        }
        try {
            writeStarted.await()
            assertEquals(oldAssistant, diskAssistant)
            assertNull(opened)
            assertFalse(switch.isCompleted)
            finishWrite.complete(Unit)
            switch.await()
            assertEquals(requestedAssistant, opened!!.assistantId)
            assertEquals("requested assistant preset", opened!!.currentMessages.single().toText())
        } finally {
            switch.cancel()
            sessionScope.cancel()
        }
    }

    @Test
    fun `resume waits for persistence and queries only the selected assistants history`() = runBlocking {
        val requested = Uuid.random()
        val existing = Uuid.random()
        val finishWrite = CompletableDeferred<Unit>()
        var queried = false
        val switch = async(start = CoroutineStart.UNDISPATCHED) {
            selectAssistantConversation(requested, false,
                persistSelection = { finishWrite.await() },
                findConversation = {
                    assertEquals(requested, it)
                    queried = true
                    existing
                },
            )
        }
        assertFalse(queried)
        finishWrite.complete(Unit)
        assertEquals(existing, switch.await())
        assertTrue(queried)
    }

    @Test
    fun `assistant without history receives a fresh conversation`() = runBlocking {
        val requested = Uuid.random()
        var persisted: Uuid? = null
        val id = selectAssistantConversation(requested, false,
            persistSelection = { persisted = it },
            findConversation = { assertEquals(requested, persisted); null },
        )
        assertNotEquals(requested, id)
        assertEquals(requested, persisted)
    }

    @Test
    fun `failed selection never opens a conversation`() = runBlocking {
        var opened = false
        val failure = IllegalStateException("disk write failed")
        try {
            selectAssistantConversation(Uuid.random(), false,
                persistSelection = { throw failure },
                findConversation = { error("Must not query after a failed write") },
            )
            opened = true
            fail("Expected the write error")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertFalse(opened)
    }

    @Test
    fun `cancelled earlier selection cannot navigate after a newer selection`() = runBlocking {
        val first = Uuid.random()
        val second = Uuid.random()
        val blockedWrite = CompletableDeferred<Unit>()
        val opened = mutableListOf<Uuid>()
        val earlier = async(start = CoroutineStart.UNDISPATCHED) {
            opened += selectAssistantConversation(first, true,
                persistSelection = { blockedWrite.await() }, findConversation = { null },
            )
        }
        earlier.cancel()
        val latestId = selectAssistantConversation(second, true,
            persistSelection = {}, findConversation = { null },
        )
        opened += latestId
        earlier.join()
        assertEquals(listOf(latestId), opened)
    }
}
