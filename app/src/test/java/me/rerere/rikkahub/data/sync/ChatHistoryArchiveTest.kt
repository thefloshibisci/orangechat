package me.rerere.rikkahub.data.sync

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class ChatHistoryArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun conversation() = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        UIMessage.user("first").toMessageNode(), UIMessage.assistant("last").toMessageNode()))

    @Test fun `legacy repeated message IDs round trip without dropping rewriting or merging entries`() {
        val shared = UIMessage.assistant("original")
        val variant = shared.copy(parts = listOf(UIMessagePart.Text("different content, same ID")))
        val first = shared.toMessageNode().copy(messages = listOf(shared, variant, shared), selectIndex = 1)
        val second = variant.toMessageNode()
        val original = conversation().copy(messageNodes = listOf(first, second),
            compressionSummaries = listOf(ConversationSummary(text = "summary")),
            compressedMessageNodeIds = setOf(first.id))
        val fork = original.copy(id = Uuid.random(), messageNodes = original.messageNodes.map { it.copy(id = Uuid.random()) },
            compressionSummaries = emptyList(), compressedMessageNodeIds = emptySet())
        val output = ByteArrayOutputStream()
        writeChatArchive(output, ChatHistoryArchive(conversations = listOf(original, fork),
            favoriteNodeIds = setOf(second.id))) { error("no attachments") }
        val decoded = readChatArchive(output.toByteArray().inputStream(), temporary.newFolder())
        assertEquals(listOf(original, fork), decoded.conversations)
        assertEquals(setOf(second.id), decoded.favoriteNodeIds)
        assertEquals(4, decoded.conversations.first().messageNodes.sumOf { it.messages.size })
        assertEquals(1, decoded.conversations.first().messageNodes.first().selectIndex)
    }

    @Test fun `round trip preserves branches compression tools voice references and binary bytes`() {
        val token = CHAT_ATTACHMENT_PREFIX + "0"
        val local = conversation()
        val voice = UIMessagePart.VoiceMessage(token, 42, "transcript")
        val tool = UIMessagePart.Tool("call", "download", "{}", listOf(UIMessagePart.Document(token, "note.bin")))
        val message = UIMessage.assistant("tool").copy(parts = listOf(tool, voice))
        val branched = local.messageNodes.last().copy(messages = listOf(local.messageNodes.last().currentMessage, message), selectIndex = 1)
        val source = local.copy(messageNodes = listOf(local.messageNodes.first(), branched),
            compressionSummaries = listOf(ConversationSummary(text = "summary")),
            compressedMessageNodeIds = setOf(local.messageNodes.first().id))
        val bytes = byteArrayOf(0, 1, -1, 127)
        val output = ByteArrayOutputStream()
        writeChatArchive(output, ChatHistoryArchive(conversations = listOf(source), attachments = mapOf(token to "attachments/0.bin"),
            favoriteNodeIds = setOf(branched.id))) { bytes.inputStream() }
        val directory = temporary.newFolder()
        val decoded = readChatArchive(output.toByteArray().inputStream(), directory)
        assertEquals(source, decoded.conversations.single())
        assertArrayEquals(bytes, directory.resolve("attachments/0.bin").readBytes())
        val rewritten = mapChatAttachmentUrls(source) { if (it == token) "file:///new.bin" else it }
        val parts = rewritten.messageNodes.last().currentMessage.parts
        assertEquals("file:///new.bin", (parts[1] as UIMessagePart.VoiceMessage).url)
        assertEquals("file:///new.bin", ((parts[0] as UIMessagePart.Tool).output.single() as UIMessagePart.Document).url)
    }

    @Test fun `archive rejects traversal unarchived local paths missing attachments and unsupported versions`() {
        fun rejected(archive: ChatHistoryArchive, extras: Map<String, ByteArray> = emptyMap()) {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("chats.json")); zip.write(JsonInstant.encodeToString(archive).toByteArray()); zip.closeEntry()
                extras.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            }
            assertTrue(runCatching { readChatArchive(bytes.toByteArray().inputStream(), temporary.newFolder()) }.isFailure)
        }
        val source = conversation()
        rejected(ChatHistoryArchive(conversations = listOf(source)), mapOf("../escape" to byteArrayOf(1)))
        rejected(ChatHistoryArchive(conversations = listOf(source.copy(messageNodes = listOf(
            UIMessage.user("text").copy(parts = listOf(UIMessagePart.Document("file:///private/secret", "secret"))).toMessageNode())))))
        rejected(ChatHistoryArchive(conversations = listOf(source), attachments = mapOf("bad" to "attachments/0.bin")))
        rejected(ChatHistoryArchive(version = 99, conversations = listOf(source)))
    }

    @Test fun `actual streamed size is bounded and duplicate identities are rejected`() {
        assertTrue(runCatching { copyChatBackupBytes(ByteArray(10).inputStream(), ByteArrayOutputStream(), 9) }.isFailure)
        val source = conversation()
        assertTrue(runCatching { validateChatHistory(listOf(source, source)) }.isFailure)
        assertTrue(runCatching { validateChatHistory(listOf(source.copy(messageNodes = source.messageNodes + source.messageNodes.first()))) }.isFailure)
    }
}
