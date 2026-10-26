package me.rerere.rikkahub.data.files

import android.app.Application
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class QueueAttachmentReferencesTest {
    private val image = UIMessagePart.Image("file:///upload/image.png")

    @Test fun queuedPinsInvalidatePreviouslyScannedGarbage() {
        val guard = AttachmentReferenceCoordinator()
        val oldScan = guard.snapshot()!!
        val pins = QueueAttachmentReferences(guard)
        pins.update(listOf(image))
        assertFalse(guard.commit(oldScan, "image.png") { error("Stale scan must not unlink") })
        assertFalse(guard.commit(guard.snapshot()!!, "image.png") { error("Queued input must stay") })
    }

    @Test fun sharedQueuesRetainFilesUntilBothRelease() {
        val guard = AttachmentReferenceCoordinator()
        val first = QueueAttachmentReferences(guard)
        val second = QueueAttachmentReferences(guard)
        first.update(listOf(image))
        second.update(listOf(image))
        first.release()
        assertEquals(setOf("image.png"), guard.protectedNames())
        assertFalse(guard.commit(guard.snapshot()!!, "image.png") { error("Other queue owns it") })
        second.release()
        assertTrue(guard.protectedNames().isEmpty())
        assertTrue(guard.commit(guard.snapshot()!!, "image.png") { true })
    }

    @Test fun queueWithdrawalPreservesInputDraftReferences() {
        val guard = AttachmentReferenceCoordinator()
        val draft = Any()
        val pins = QueueAttachmentReferences(guard)
        guard.updateDraft(draft, setOf("image.png"))
        pins.update(listOf(image))
        pins.release()
        var deleted = false
        guard.ifUnpinned("image.png") { deleted = true }
        assertFalse(deleted)
        guard.updateDraft(draft, emptySet())
        guard.ifUnpinned("image.png") { deleted = true }
        assertTrue(deleted)
    }

    @Test fun submittingInputStaysProtectedAfterPendingRowsAreRemoved() {
        val guard = AttachmentReferenceCoordinator()
        val pins = QueueAttachmentReferences(guard)
        val other = UIMessagePart.Video("file:///upload/movie.mp4")
        pins.update(listOf(image, other))
        // Queue caller passes its in-flight image as well as pending parts after dispatch.
        pins.update(listOf(image))
        assertEquals(setOf("image.png"), guard.protectedNames())
        assertFalse(guard.commit(guard.snapshot()!!, "image.png") { error("Not yet persisted") })
        assertTrue(guard.commit(guard.snapshot()!!, "movie.mp4") { true })
        pins.release()
        assertTrue(guard.commit(guard.snapshot()!!, "image.png") { true })
    }

    @Test fun recursiveToolPartsAndLocalPathsPinOnlyRelevantNames() {
        val guard = AttachmentReferenceCoordinator()
        val pins = QueueAttachmentReferences(guard)
        pins.update(listOf(
            image, image, UIMessagePart.Video("file:///upload/movie.mp4"),
            UIMessagePart.Audio("/upload/audio.mp3"), UIMessagePart.Document("file:///upload/my%20file.pdf", "document"),
            UIMessagePart.Tool(toolCallId = "outer", toolName = "tool", input = "{}", output = listOf(
                UIMessagePart.Tool(toolCallId = "inner", toolName = "tool", input = "{}", output = listOf(
                    UIMessagePart.Image("file:///upload/nested.png"),
                )),
            )),
            UIMessagePart.Image("https://example.com/remote.png"), UIMessagePart.Image("content://photos/picture"),
            UIMessagePart.Image("data:image/png;base64,abc"),
        ))
        assertEquals(setOf("image.png", "movie.mp4", "audio.mp3", "my file.pdf", "nested.png"), guard.protectedNames())
    }

    @Test fun releaseIsIdempotentAndNeverDeletesBytes() {
        val guard = AttachmentReferenceCoordinator()
        val pins = QueueAttachmentReferences(guard)
        val file = File.createTempFile("queue-pin-", ".txt")
        try {
            file.writeText("preserved bytes")
            pins.update(listOf(UIMessagePart.Document(file.toURI().toString(), "test")))
            val oldScan = guard.snapshot()!!
            pins.release()
            pins.release()
            assertFalse(guard.commit(oldScan, file.name) { error("Rescan required after release") })
            assertEquals("preserved bytes", file.readText())
            assertTrue(guard.protectedNames().isEmpty())
        } finally { file.delete() }
    }
}
