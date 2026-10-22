package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

internal const val CHAT_ATTACHMENT_PREFIX = "orangechat-attachment:"
internal const val CHAT_ARCHIVE_LIMIT = 2L * 1024 * 1024 * 1024
internal const val CHAT_MANIFEST_LIMIT = 128L * 1024 * 1024

internal fun isPortableChatUrl(url: String): Boolean = url.isBlank() ||
    listOf("https://", "http://", "data:").any { url.startsWith(it, ignoreCase = true) }

@Serializable
internal data class ChatHistoryArchive(
    val format: String = "orangechat-chat-history",
    val version: Int = 1,
    val conversations: List<Conversation>,
    val attachments: Map<String, String> = emptyMap(),
    val favoriteNodeIds: Set<Uuid> = emptySet(),
)

internal fun validateChatHistory(conversations: List<Conversation>) {
    require(conversations.map { it.id }.distinct().size == conversations.size) { "备份包含重复会话 ID" }
    val nodes = hashSetOf<Uuid>()
    conversations.forEach { conversation ->
        conversation.requireMessageNodesLoaded()
        conversation.messageNodes.forEach { node ->
            require(nodes.add(node.id)) { "备份包含重复消息节点 ID" }
            require(node.messages.isNotEmpty() && node.selectIndex in node.messages.indices) { "备份消息分支无效" }
            // UIMessage IDs are embedded data, not database keys. Legacy histories may repeat
            // them across nodes or alternatives; preserve every entry, its order and selection.
        }
        require(conversation.compressedMessageNodeIds == conversation.activeCompressedMessageNodeIds) {
            "备份的压缩范围无效"
        }
        conversation.messagesForGeneration()
    }
}

/** Explicit typed traversal avoids rewriting text, tool arguments or remote URLs by string substitution. */
internal fun mapChatAttachmentUrls(conversation: Conversation, map: (String) -> String): Conversation {
    return mapChatAttachments(conversation) { part ->
        when (part) {
            is UIMessagePart.Image -> part.copy(url = map(part.url))
            is UIMessagePart.Document -> part.copy(url = map(part.url))
            is UIMessagePart.Audio -> part.copy(url = map(part.url))
            is UIMessagePart.Video -> part.copy(url = map(part.url))
            is UIMessagePart.VoiceMessage -> part.copy(url = map(part.url))
            else -> part
        }
    }
}

internal fun mapChatAttachments(conversation: Conversation, map: (UIMessagePart) -> UIMessagePart): Conversation {
    fun parts(items: List<UIMessagePart>): List<UIMessagePart> = items.map { part ->
        when (part) {
            is UIMessagePart.Tool -> part.copy(output = parts(part.output))
            else -> map(part)
        }
    }
    return conversation.copy(messageNodes = conversation.messageNodes.map { node ->
        node.copy(messages = node.messages.map { it.copy(parts = parts(it.parts)) })
    })
}

/** Hard limits apply to bytes actually read, including streams with unknown/misreported sizes. */
internal fun copyChatBackupBytes(input: InputStream, output: OutputStream, limit: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return size
        size += count
        require(size <= limit) { "聊天备份超过大小限制" }
        output.write(buffer, 0, count)
    }
}

internal fun writeChatArchive(
    output: OutputStream,
    archive: ChatHistoryArchive,
    openAttachment: (String) -> InputStream,
) {
    validateChatHistory(archive.conversations)
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("chats.json"))
        val json = JsonInstant.encodeToString(archive).toByteArray(Charsets.UTF_8)
        require(json.size <= CHAT_MANIFEST_LIMIT) { "聊天记录过大，请分批整理后备份" }
        zip.write(json)
        zip.closeEntry()
        var total = json.size.toLong()
        archive.attachments.forEach { (key, name) ->
            require(name.matches(Regex("attachments/[0-9]+(?:\\.[a-zA-Z0-9]{1,12})?")))
            zip.putNextEntry(ZipEntry(name))
            openAttachment(key).use {
                val copied = copyChatBackupBytes(it, zip, CHAT_ARCHIVE_LIMIT - total)
                require(copied > 0) { "聊天附件为空，导出未完成" }
                total += copied
            }
            zip.closeEntry()
        }
    }
}

internal fun readChatArchive(input: InputStream, directory: File): ChatHistoryArchive {
    check(directory.mkdirs() || directory.isDirectory)
    val names = hashSetOf<String>()
    var total = 0L
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            require(names.size < 100_000 && names.add(name)) { "备份文件条目重复或过多" }
            require(!entry.isDirectory && (name == "chats.json" ||
                name.matches(Regex("attachments/[0-9]+(?:\\.[a-zA-Z0-9]{1,12})?")))) { "备份文件路径无效" }
            val file = File(directory, name)
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            file.outputStream().use { out ->
                total += copyChatBackupBytes(zip, out,
                    minOf(CHAT_ARCHIVE_LIMIT - total, if (name == "chats.json") CHAT_MANIFEST_LIMIT else CHAT_ARCHIVE_LIMIT))
            }
        }
    }
    require("chats.json" in names) { "请选择橘瓣的聊天记录专项备份" }
    val archive = JsonInstant.decodeFromString<ChatHistoryArchive>(File(directory, "chats.json").readText())
    require(archive.format == "orangechat-chat-history" && archive.version == 1) { "不支持的聊天备份版本" }
    validateChatHistory(archive.conversations)
    require(archive.attachments.values.toSet().size == archive.attachments.size &&
        names == archive.attachments.values.toSet() + "chats.json") { "备份附件清单不完整" }
    require(archive.attachments.values.all { File(directory, it).length() > 0 }) { "备份包含空附件" }
    val referenced = hashSetOf<String>()
    archive.conversations.forEach { conversation ->
        mapChatAttachmentUrls(conversation) { url ->
            if (url.startsWith(CHAT_ATTACHMENT_PREFIX)) {
                require(url in archive.attachments) { "备份缺少引用的附件" }
                referenced.add(url)
            } else {
                require(isPortableChatUrl(url)) { "备份包含未归档的本地附件" }
            }
            url
        }
    }
    require(referenced == archive.attachments.keys) { "备份包含无关附件" }
    val nodeIds = archive.conversations.flatMap { it.messageNodes }.map { it.id }.toSet()
    require(nodeIds.containsAll(archive.favoriteNodeIds)) { "备份收藏引用无效" }
    return archive
}
