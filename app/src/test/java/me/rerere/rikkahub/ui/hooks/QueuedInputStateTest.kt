package me.rerere.rikkahub.ui.hooks

import android.app.Application
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import me.rerere.rikkahub.data.listen.ListenSong
import me.rerere.rikkahub.data.listen.listenShareText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class QueuedInputStateTest {
    @Test fun sharingKeepsDraftAndAttachmentsAndRemovesOnlyOneDuplicate() {
        val image = UIMessagePart.Image("file:///upload/shared-${Uuid.random()}.png")
        val payload = listenShareText(ListenSong("123", "歌", "歌手"))
        val input = ChatInputState().apply {
            setMessageText("unsent draft")
            messageContent = listOf(image)
        }
        try {
            assertTrue(input.trySetSharedMessageText(payload))
            assertTrue(input.trySetSharedMessageText(payload))
            input.removeMessagePart(1)
            assertEquals(
                listOf(UIMessagePart.Text("unsent draft"), image, UIMessagePart.Text(payload)),
                input.getContents(),
            )
        } finally { input.releaseDraft() }
    }

    @Test fun sharingUsesExactPayloadInEmptyDraftAndRejectsMessageEdits() {
        val image = UIMessagePart.Image("file:///upload/shared-${Uuid.random()}.png")
        val input = ChatInputState().apply { messageContent = listOf(image) }
        val payload = listenShareText(ListenSong("123", "歌", "歌手"))
        try {
            assertTrue(input.trySetSharedMessageText(payload))
            assertEquals(listOf(image, UIMessagePart.Text(payload)), input.getContents())
            input.editingMessage = Uuid.random()
            input.setMessageText("")
            assertFalse(input.trySetSharedMessageText(payload))
            assertEquals("", input.textContent.text.toString())
            assertEquals(listOf(image, UIMessagePart.Text(payload)), input.messageContent)
        } finally { input.releaseDraft() }
    }

    @Test fun editingKeepsCardAndMultipartOrderAndDoesNotResurrectRemovedCard() {
        val payload = UIMessagePart.Text(listenShareText(ListenSong("123", "歌", "歌手")))
        val image = UIMessagePart.Image("file:///upload/shared-${Uuid.random()}.png")
        val original = listOf(UIMessagePart.Text("earlier"), payload, image, UIMessagePart.Text("note"), payload)
        val input = ChatInputState().apply { editingMessage = Uuid.random(); setContents(original) }
        try {
            assertEquals("note", input.textContent.text.toString())
            input.removeMessagePart(0)
            input.setMessageText("updated")
            assertEquals(listOf(UIMessagePart.Text("earlier"), image, UIMessagePart.Text("updated"), payload), input.getContents())
        } finally { input.releaseDraft() }
    }

    @Test fun malformedCardRemainsEditableTextAndNonEditingSetContentsPreservesAllText() {
        val malformed = "[共度·歌曲]\n{broken}"
        val input = ChatInputState().apply {
            setContents(listOf(UIMessagePart.Text("earlier"), UIMessagePart.Text(malformed)))
        }
        try {
            assertEquals(malformed, input.textContent.text.toString())
            assertFalse(input.trySetSharedMessageText(malformed))
            assertEquals(listOf(UIMessagePart.Text("earlier"), UIMessagePart.Text(malformed)), input.getContents())
        } finally { input.releaseDraft() }
    }

    @Test fun editingLegacyMixedTextPreservesNotesAndRemovesOnlySelectedCard() {
        val first = listenShareText(ListenSong("123", "第一首", "歌手"))
        val last = listenShareText(ListenSong("456", "第二首", "歌手"))
        val originalText = "前文\n" + first + "\n后文\n" + last
        val original = listOf(UIMessagePart.Text(originalText))
        val input = ChatInputState().apply { editingMessage = Uuid.random(); setContents(original) }
        try {
            assertEquals("\n后文\n", input.textContent.text.toString())
            assertEquals(listOf(UIMessagePart.Text(first), UIMessagePart.Text(last)), input.messageContent)
            input.removeMessagePart(0)
            assertEquals(listOf(UIMessagePart.Text("前文\n"), UIMessagePart.Text("\n后文\n"), UIMessagePart.Text(last)), input.getContents())
            assertEquals(originalText, (original.single() as UIMessagePart.Text).text)
        } finally { input.releaseDraft() }
    }

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
