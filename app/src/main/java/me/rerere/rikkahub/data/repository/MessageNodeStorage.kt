package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeHeader
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

/** Caller holds a read transaction. Return every row, or fail without publishing a partial history. */
internal suspend fun MessageNodeDAO.loadCompleteNodes(conversationId: String): List<MessageNodeEntity> =
    mapCompleteNodes(conversationId) { it }

/** Decode each page before reading the next, so raw JSON for the whole chat is not retained too. */
internal suspend fun <T> MessageNodeDAO.mapCompleteNodes(
    conversationId: String,
    transform: suspend (MessageNodeEntity) -> T,
): List<T> {
    val total = getNodeCountOfConversation(conversationId)
    val result = ArrayList<T>(total)
    val pageSize = 64
    for (offset in 0 until total step pageSize) {
        val expected = minOf(pageSize, total - offset)
        val page = try {
            getNodesOfConversationPaged(conversationId, pageSize, offset)
        } catch (_: SQLiteBlobTooBigException) {
            null
        }
        if (page != null) {
            check(page.size == expected) { "消息读取不完整，请重新打开会话。原有记录未被修改。" }
            for (entity in page) result.add(transform(entity))
        } else {
            // SQLite slices by Unicode code point, not UTF-16 offset. A 64K slice stays
            // comfortably below CursorWindow's limit even with four-byte characters.
            val headers = getNodeHeaders(conversationId, pageSize, offset)
            check(headers.size == expected) { "消息读取不完整，请重新打开会话。原有记录未被修改。" }
            for (header in headers) {
                result.add(transform(readSlicedNode(header)))
            }
        }
    }
    return result
}

/** Caller holds a read transaction. Maintenance needs one complete node, never every history body. */
internal suspend fun MessageNodeDAO.loadCompleteNode(id: String): MessageNodeEntity? {
    val header = getNodeHeader(id) ?: return null
    return try {
        checkNotNull(getNodesByIds(listOf(id)).singleOrNull()) { "消息读取不完整，原有记录未被修改。" }
    } catch (_: SQLiteBlobTooBigException) {
        readSlicedNode(header)
    }
}

private suspend fun MessageNodeDAO.readSlicedNode(header: MessageNodeHeader): MessageNodeEntity {
    val text = buildString {
        for (start in 1..header.messageLength step 65_536) {
            val slice = checkNotNull(getMessageSlice(header.id, start, 65_536)) {
                "消息读取中断，请重新打开会话。"
            }
            check(slice.isNotEmpty()) { "消息读取不完整，请重新打开会话。" }
            append(slice)
        }
    }
    check(text.codePointCount(0, text.length) == header.messageLength) { "消息读取不完整，请重新打开会话。" }
    return MessageNodeEntity(header.id, header.conversationId, header.nodeIndex, text, header.selectIndex)
}
