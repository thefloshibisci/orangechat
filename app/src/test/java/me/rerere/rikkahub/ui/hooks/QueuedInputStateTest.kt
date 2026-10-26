package me.rerere.rikkahub.ui.hooks

import android.app.Application
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class QueuedInputStateTest {
    @Test fun queueEditorPreservesOriginalMediaAndEarlierTextSegments() {
        val image = UIMessagePart.Image("file:///upload/image-${Uuid.random()}.png")
        val audio = UIMessagePart.Audio("file:///upload/audio-${Uuid.random()}.wav")
        val input = ChatInputState().apply {
            editingMessage = Uuid.random()
            setContents(listOf(UIMessagePart.Text("first"), image, UIMessagePart.Text("last"), audio))
        }
        try {
            input.setMessageText("replaced")
            assertEquals(listOf(UIMessagePart.Text("first"), image, UIMessagePart.Text("replaced"), audio), input.getContents())
            assertFalse(input.shouldDeleteFileOnRemove(image))
            assertFalse(input.shouldDeleteFileOnRemove(audio))
        } finally { input.releaseDraft() }
    }

    @Test fun releasingTemporaryEditorDropsItsPinWithoutAffectingMainDraft() {
        val name = "queued-${Uuid.random()}.png"
        val mainName = "main-${Uuid.random()}.png"
        val main = ChatInputState().apply { setContents(listOf(UIMessagePart.Image("file:///upload/$mainName"))) }
        val editor = ChatInputState().apply {
            editingMessage = Uuid.random()
            setContents(listOf(UIMessagePart.Image("file:///upload/$name")))
        }
        try {
            assertTrue(name in attachmentReferenceGuard.protectedNames())
            editor.releaseDraft()
            assertFalse(name in attachmentReferenceGuard.protectedNames())
            assertTrue(mainName in attachmentReferenceGuard.protectedNames())
            assertEquals(listOf(UIMessagePart.Image("file:///upload/$mainName")), main.getContents())
        } finally { main.releaseDraft(); editor.releaseDraft() }
    }

    @Test fun cancelDraftDoesNotChangeOriginalQueuedPayload() {
        val original = listOf(UIMessagePart.Text("original"), UIMessagePart.Document("file:///upload/test.txt", "test.txt"))
        val input = ChatInputState().apply { editingMessage = Uuid.random(); setContents(original) }
        input.messageContent = emptyList()
        input.setMessageText("discarded")
        input.releaseDraft()
        assertEquals("original", (original.first() as UIMessagePart.Text).text)
        assertEquals(2, original.size)
        assertTrue(input.isEmpty())
        assertFalse(input.isEditing())
    }

    @Test fun emptyLastTextStillAllowsEarlierOriginalTextToRemainQueued() {
        val input = ChatInputState().apply {
            editingMessage = Uuid.random()
            setContents(listOf(UIMessagePart.Text("keep earlier text"), UIMessagePart.Text("edit last text")))
        }
        try {
            input.setMessageText("")
            assertFalse(input.isEmpty())
            assertEquals(listOf(UIMessagePart.Text("keep earlier text")), input.getContents())
        } finally { input.releaseDraft() }
    }
}
