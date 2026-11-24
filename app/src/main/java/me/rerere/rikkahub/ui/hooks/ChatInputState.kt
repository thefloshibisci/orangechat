/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.hooks

import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import me.rerere.rikkahub.data.gongdu.gongduShareSummary
import me.rerere.rikkahub.data.gongdu.splitGongduShareText

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import kotlin.uuid.Uuid

class ChatInputState {
    val textContent = TextFieldState()
    private var content by mutableStateOf(listOf<UIMessagePart>())
    private var contentSlots: List<Int?> = emptyList()
    var messageContent: List<UIMessagePart>
        get() = content
        set(value) {
            attachmentReferenceGuard.beginWrite().use {
                val names = me.rerere.rikkahub.data.files.attachmentUrls(
                    listOf(me.rerere.ai.ui.UIMessage.user("").copy(parts = value))
                ).mapNotNull { me.rerere.rikkahub.data.files.localAttachmentFile(it)?.name }.toSet()
                attachmentReferenceGuard.updateDraft(this, names)
                val unmatched = content.indices.toMutableList()
                contentSlots = value.map { part ->
                    val match = unmatched.indexOfFirst { content[it] == part }
                    if (match < 0) null else contentSlots.getOrNull(unmatched.removeAt(match))
                }
                content = value
            }
        }
    var editingMessage by mutableStateOf<Uuid?>(null)
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        editingParts = null
        editingAttachmentUrls = emptySet()
    }

    /** Release an editor that is leaving composition without unlinking any attachment files. */
    fun releaseDraft() {
        clearInput()
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun quoteText(text: String): Boolean {
        if (isEditing() || text.isBlank()) return false
        val quoted = text.lineSequence().joinToString("\n") { "> $it" }
        setMessageText(quoted + "\n\n" + textContent.text)
        return true
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun trySetSharedMessageText(content: String): Boolean {
        if (isEditing() || gongduShareSummary(content) == null) return false
        messageContent = messageContent + UIMessagePart.Text(content)
        return true
    }

    fun removeMessagePart(index: Int) {
        if (index !in messageContent.indices) return
        val slots = contentSlots.filterIndexed { i, _ -> i != index }
        messageContent = messageContent.filterIndexed { i, _ -> i != index }
        contentSlots = slots
    }

    fun setContents(contents: List<UIMessagePart>) {
        val normalized = contents.flatMap { part ->
            if (part is UIMessagePart.Text) splitGongduShareText(part.text).map { part.copy(text = it) }
            else listOf(part)
        }
        val lastTextIndex = normalized.indexOfLast { it is UIMessagePart.Text && gongduShareSummary(it.text) == null }
        val text = if (lastTextIndex >= 0) {
            (normalized[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = normalized.filter { it !is UIMessagePart.Text || gongduShareSummary(it.text) != null }
        contentSlots = normalized.indices.filter { normalized[it] !is UIMessagePart.Text ||
            gongduShareSummary((normalized[it] as UIMessagePart.Text).text) != null }
        editingParts = normalized
        editingAttachmentUrls = normalized.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    fun getContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        val originalParts = editingParts
        if (originalParts != null) {
            val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text && gongduShareSummary(it.text) == null }
            val merged = mutableListOf<UIMessagePart>()

            originalParts.forEachIndexed { index, part ->
                when {
                    index == editedTextIndex -> {
                        if (text.isNotEmpty()) {
                            merged.add((part as UIMessagePart.Text).copy(text = text))
                        }
                    }

                    part is UIMessagePart.Text && gongduShareSummary(part.text) == null -> {
                        if (part.text.isNotEmpty()) {
                            merged.add(part)
                        }
                    }

                    else -> {
                        val currentIndex = contentSlots.indexOf(index)
                        if (currentIndex >= 0) {
                            merged.add(messageContent[currentIndex])
                        }
                    }
                }
            }
            if (editedTextIndex < 0 && text.isNotBlank()) {
                merged.add(0, UIMessagePart.Text(text))
            }
            // Newly added attachments and cards are appended in insertion order.
            messageContent.forEachIndexed { index, part -> if (contentSlots.getOrNull(index) == null) merged.add(part) }
            return merged
        }
        return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return getContents().isEmptyInputMessage()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}
