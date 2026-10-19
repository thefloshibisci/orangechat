package me.rerere.rikkahub.service

import android.app.Application
import android.net.Uri
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ConversationMutationFilesTest {
    private val file = "file:///test/shared.png"
    private fun image() = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Image(file)))
    private fun conversation(vararg nodes: MessageNode) = Conversation(assistantId = Uuid.random(), messageNodes = nodes.toList())

    @Test
    fun `a removed file stays alive while an unchanged node or alternative still references it`() {
        val changed = image()
        val retained = image()
        for (before in listOf(
            conversation(MessageNode.of(changed), MessageNode.of(retained)),
            conversation(MessageNode(messages = listOf(changed, retained))),
        )) {
            val mutation = before.generationMessageMutation(listOf(changed.copy(parts = UIMessage.assistant("removed image").parts)))
            // The first case needs the full reference check; the alternative case has no candidate at all.
            assertTrue(mutation.unreferencedFiles().isEmpty())
            assertEquals(retained, mutation.conversation.messageNodes.last().messages.last())
        }
    }

    @Test
    fun `nested tool output is protected until its final reference disappears`() {
        val message = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("call", "tool", "{}",
            output = listOf(UIMessagePart.Tool("inner", "inner", "{}", output = listOf(UIMessagePart.Image(file)))))))
        val before = conversation(MessageNode.of(message))
        val mutation = before.generationMessageMutation(listOf(message.copy(parts = UIMessage.assistant("removed").parts)))
        assertEquals(setOf(Uri.parse(file)), mutation.removedFileCandidates())
        assertEquals(listOf(Uri.parse(file)), mutation.unreferencedFiles())
    }

    @Test
    fun `text streaming and retained images produce no file deletion candidates`() {
        val message = image()
        val before = conversation(MessageNode.of(message))
        val mutation = before.generationMessageMutation(listOf(message.copy(parts = message.parts + UIMessagePart.Text("stream"))))
        assertTrue(mutation.removedFileCandidates().isEmpty())
        assertTrue(mutation.unreferencedFiles().isEmpty())
    }

    @Test
    fun `text updates do not inspect unrelated historical file bodies`() {
        val unreadableParts = object : AbstractList<UIMessagePart>() {
            override val size: Int get() = 1
            override fun get(index: Int): UIMessagePart = error("Untouched history was inspected for files")
        }
        val untouched = UIMessage.assistant("").copy(parts = unreadableParts)
        val streaming = UIMessage.assistant("initial")
        val before = conversation(MessageNode.of(untouched), MessageNode.of(streaming))
        val mutation = before.generationMessageMutation(listOf(streaming.copy(parts = UIMessage.assistant("stream").parts)))
        assertTrue(mutation.unreferencedFiles().isEmpty())
    }
}
