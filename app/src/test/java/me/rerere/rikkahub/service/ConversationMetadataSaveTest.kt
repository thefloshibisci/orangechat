/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */


package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMetadataSaveTest {
    @Test
    fun `title completion preserves a newer reply alternatives and metadata`() = runBlocking {
        withSession { session ->
            val latest = session.state.value.copy(
                messageNodes = session.state.value.messageNodes + MessageNode.of(UIMessage.assistant("new reply")),
                folderId = Uuid.random(),
                chatSuggestions = listOf("new suggestion"),
                compressionRevision = 7,
                sendCompressedSummary = false,
            )
            session.state.value = latest
            session.saveGeneratedTitle("generated") {
                assertTrue(session.isInUse)
                true
            }
            assertFalse(session.isInUse)
            assertEquals(latest.copy(title = "generated"), session.state.value)
            assertSame(latest.messageNodes, session.state.value.messageNodes)
        }
    }

    @Test
    fun `suggestion completion preserves newer title and deleted message state`() = runBlocking {
        withSession { session ->
            val latest = session.state.value.copy(title = "renamed", messageNodes = emptyList())
            session.state.value = latest
            session.saveGeneratedSuggestions(listOf("one", "two")) { true }
            assertEquals(latest.copy(chatSuggestions = listOf("one", "two")), session.state.value)
            assertTrue(session.state.value.messageNodes.isEmpty())
        }
    }

    @Test
    fun `missing database row leaves both metadata fields untouched`() = runBlocking {
        withSession { session ->
            val before = session.state.value
            session.saveGeneratedTitle("late title") { false }
            session.saveGeneratedSuggestions(listOf("late suggestion")) { false }
            assertSame(before, session.state.value)
        }
    }

    @Test
    fun `failed metadata persistence does not publish an unsaved result`() = runBlocking {
        withSession { session ->
            val before = session.state.value
            val failure = IllegalStateException("save failed")
            try {
                session.saveGeneratedTitle("unsaved") { throw failure }
                fail("Persistence failure must propagate")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
            assertSame(before, session.state.value)
            // The mutex is released even after a failure.
            session.saveGeneratedSuggestions(listOf("saved")) { true }
            assertEquals(before.copy(chatSuggestions = listOf("saved")), session.state.value)
        }
    }

    @Test
    fun `stream save and suggestion save wait for title persistence and retain all changes`() = runBlocking {
        withSession { session ->
            coroutineScope {
                val savingTitle = CompletableDeferred<Unit>()
                val finishTitle = CompletableDeferred<Unit>()
                val title = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.saveGeneratedTitle("generated") {
                        savingTitle.complete(Unit)
                        finishTitle.await()
                        true
                    }
                }
                savingTitle.await()
                val reply = MessageNode.of(UIMessage.assistant("stream finished"))
                val stream = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.saveMutex.withLock {
                        session.state.value = session.state.value.copy(
                            messageNodes = session.state.value.messageNodes + reply
                        )
                    }
                }
                val suggestions = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.saveGeneratedSuggestions(listOf("next")) { true }
                }
                try {
                    assertFalse(stream.isCompleted)
                    assertFalse(suggestions.isCompleted)
                } finally {
                    finishTitle.complete(Unit)
                }
                title.join()
                stream.join()
                suggestions.join()
                assertEquals("generated", session.state.value.title)
                assertEquals(listOf("next"), session.state.value.chatSuggestions)
                assertEquals(reply, session.state.value.messageNodes.last())
            }
        }
    }

    @Test
    fun `metadata completion waits until an older database load has been published`() = runBlocking {
        withSession { session ->
            coroutineScope {
                var database = session.state.value.copy(title = "old title")
                session.state.value = Conversation.ofId(session.id, database.assistantId)
                val loading = CompletableDeferred<Unit>()
                val finishLoad = CompletableDeferred<Unit>()
                val initialized = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.withStateLock {
                        assertTrue(session.isInUse)
                        val loaded = database
                        loading.complete(Unit)
                        finishLoad.await()
                        session.state.value = loaded
                    }
                }
                loading.await()
                var titlePersisted = false
                val title = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.saveGeneratedTitle("new title") {
                        titlePersisted = true
                        database = database.copy(title = "new title")
                        true
                    }
                }
                try {
                    assertFalse(titlePersisted)
                    assertFalse(title.isCompleted)
                } finally {
                    finishLoad.complete(Unit)
                }
                initialized.join()
                title.join()

                assertEquals(database, session.state.value)
                assertEquals("new title", session.state.value.title)
                assertFalse(session.isInUse)
            }
        }
    }

    @Test
    fun `opening a newly created metadata session waits and loads the committed result`() = runBlocking {
        withSession { session ->
            coroutineScope {
                var database = session.state.value.copy(title = "saved title", chatSuggestions = listOf("old"))
                // Completion may create the session before the UI has loaded any messages into it.
                session.state.value = Conversation.ofId(session.id, database.assistantId)
                val saving = CompletableDeferred<Unit>()
                val finishSave = CompletableDeferred<Unit>()
                val suggestions = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.saveGeneratedSuggestions(listOf("new")) {
                        saving.complete(Unit)
                        finishSave.await()
                        database = database.copy(chatSuggestions = listOf("new"))
                        true
                    }
                }
                saving.await()
                var loadStarted = false
                val initialized = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.withStateLock {
                        loadStarted = true
                        session.state.value = database
                    }
                }
                try {
                    assertFalse(loadStarted)
                    assertFalse(initialized.isCompleted)
                } finally {
                    finishSave.complete(Unit)
                }
                suggestions.join()
                initialized.join()

                assertEquals(database, session.state.value)
                assertEquals(listOf("new"), session.state.value.chatSuggestions)
                assertEquals("saved title", session.state.value.title)
                assertTrue(session.state.value.messageNodes.isNotEmpty())
                assertFalse(session.isInUse)
            }
        }
    }

    private suspend fun withSession(block: suspend (ConversationSession) -> Unit) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val initial = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(
                messages = listOf(UIMessage.assistant("old"), UIMessage.assistant("selected")),
                selectIndex = 1,
                isFavorite = true,
            )),
            isPinned = true,
            customSystemPrompt = "custom prompt",
            workspaceCwd = "/workspace",
        )
        val session = ConversationSession(initial.id, initial, scope, debugLog = {}) {}
        try {
            block(session)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }
}
