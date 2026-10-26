package me.rerere.rikkahub.data.files

import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [28],
    shadows = [me.rerere.rikkahub.testing.HostFileUriShadow::class])
class DuplicateAttachmentsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sameBasenameOutsideUploadIsNeverRewritten() {
        val upload = temporary.newFolder("upload")
        upload.resolve("a.png").writeText("same")
        upload.resolve("chat-p-0.png").writeText("same")
        val external = temporary.newFolder("elsewhere").resolve("chat-p-0.png").apply { writeText("different") }
        val messages = listOf(UIMessage.user("").copy(parts = listOf(UIMessagePart.Image(external.toUri().toString()))))
        assertSame(messages, remapDuplicateAttachments(messages, planDuplicateAttachments(upload)))
    }

    @Test fun originalSurvivesAndChatCopiesPointAtIt() {
        val upload = temporary.newFolder("upload")
        val original = upload.resolve("a1.jpg").apply { writeText("same-bytes") }
        upload.resolve("chat-plan-0.jpg").writeText("same-bytes")
        upload.resolve("chat-plan-1.jpg").writeText("other-byte") // same size, different content
        upload.resolve(".tmp.jpg").writeText("same-bytes")

        val plan = planDuplicateAttachments(upload)

        assertEquals(mapOf("chat-plan-0.jpg" to original), plan.canonicalByDuplicate)
    }

    @Test fun remapRewritesNestedAndVoiceParts() {
        val upload = temporary.newFolder("upload")
        val original = upload.resolve("a1.png").apply { writeText("x") }
        val copy = upload.resolve("chat-p-0.png").apply { writeText("x") }
        val plan = planDuplicateAttachments(upload)
        val copyUrl = copy.toUri().toString()
        val messages = listOf(UIMessage.user("hi").copy(parts = listOf(
            UIMessagePart.Image(copyUrl),
            UIMessagePart.VoiceMessage(copyUrl, 10, "t"),
            UIMessagePart.Tool("c", "tool", "{}", listOf(UIMessagePart.Document(copyUrl, "a.png"))),
            UIMessagePart.Text("keep"),
        )))

        val remapped = remapDuplicateAttachments(messages, plan)
        val parts = remapped.single().parts
        val expected = original.toUri().toString()

        assertEquals(expected, (parts[0] as UIMessagePart.Image).url)
        assertEquals(expected, (parts[1] as UIMessagePart.VoiceMessage).url)
        assertEquals(expected, ((parts[2] as UIMessagePart.Tool).output.single() as UIMessagePart.Document).url)
        assertTrue(parts[3] is UIMessagePart.Text)
    }

    @Test fun unchangedMessagesKeepIdentity() {
        val upload = temporary.newFolder("upload")
        upload.resolve("a.png").writeText("x")
        upload.resolve("chat-p-0.png").writeText("x")
        val plan = planDuplicateAttachments(upload)
        val messages = listOf(UIMessage.user("hi").copy(parts = listOf(
            UIMessagePart.Image(upload.resolve("a.png").toUri().toString()),
        )))
        assertSame(messages, remapDuplicateAttachments(messages, plan))
    }
}
