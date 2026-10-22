package me.rerere.rikkahub.data.files

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `missing and empty attachments are removed without losing text or identity`() {
        val empty = temporary.newFile("empty.png")
        val missing = File(temporary.root, "missing.png")
        val original = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Text("保留这段文字"), UIMessagePart.Image(empty.toURI().toString()),
            UIMessagePart.Image(missing.toURI().toString()),
        ))
        val result = filterAttachments(listOf(original), AttachmentValidator()::isValid).single()
        assertEquals(original.id, result.id)
        assertEquals(listOf(original.parts.first()), result.parts)
        assertEquals(3, original.parts.size)
    }

    @Test fun `corrupt images are rejected but valid documents and remote images are retained`() {
        val corrupt = temporary.newFile("corrupt.png").apply { writeText("not an image") }
        val document = temporary.newFile("中文 文件.txt").apply { writeText("document") }
        val validator = AttachmentValidator()
        assertFalse(validator.isValid(UIMessagePart.Image(corrupt.toURI().toString())))
        assertTrue(validator.isValid(UIMessagePart.Document(document.toURI().toString(), document.name)))
        assertTrue(validator.isValid(UIMessagePart.Image("https://example.com/image.png")))
    }

    @Test fun `nested tool attachments are removed without making an executed tool pending`() {
        val tool = UIMessagePart.Tool("call", "camera", "{}", output = listOf(UIMessagePart.Image("file:///gone.png")))
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
        val cleaned = filterAttachments(messages) { false }.single().parts.single() as UIMessagePart.Tool
        assertEquals(tool.toolCallId, cleaned.toolCallId)
        assertTrue(cleaned.isExecuted)
        assertFalse(cleaned.canResumeExecution)
        assertTrue(attachmentUrls(listOf(messages.single().copy(parts = listOf(cleaned)))).isEmpty())
    }

    @Test fun `valid PNG is retained in both history and outgoing messages`() {
        val png = temporary.newFile("valid.png").apply {
            writeBytes(Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8UKHBwMDAxMDAwMDAAAATsgGUSte00gAAAABJRU5ErkJggg=="
            ))
        }
        val part = UIMessagePart.Image(png.toURI().toString())
        assertTrue(AttachmentValidator().isValid(part))
        assertTrue(AttachmentValidator(forSending = true).isValid(part))
    }

    @Test fun `SVG is preserved in history but skipped when sending bitmap images`() {
        val svg = temporary.newFile("drawing.svg").apply {
            writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>""")
        }
        val part = UIMessagePart.Image(svg.toURI().toString())
        assertTrue(AttachmentValidator().isValid(part))
        assertFalse(AttachmentValidator(forSending = true).isValid(part))
    }

    @Test fun `unchanged history retains object identity and image only messages retain their ids`() {
        val original = listOf(UIMessage.user("text"))
        assertSame(original, filterAttachments(original) { true })
        val imageOnly = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///gone.png")))
        val cleaned = filterAttachments(listOf(imageOnly)) { false }
        assertEquals(imageOnly.id, cleaned.single().id)
        assertTrue(cleaned.single().parts.isEmpty())
    }
}
