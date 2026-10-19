/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        conversationRepo.getConversationsOfAssistant(assistant?.id ?: Uuid.random())
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Own the whole Undo window, including navigation cancellation, in a finally block. */
    suspend fun deleteWithUndo(conversation: Conversation, offerUndo: suspend () -> Boolean) {
        var deleted: Conversation? = null
        try {
            // Keep assignment inside NonCancellable: cancellation cannot lose the cleanup snapshot.
            withContext(NonCancellable) {
                deleted = chatService.deleteConversation(conversation, retainAttachments = true)
            }
            if (offerUndo()) chatService.restoreConversation(checkNotNull(deleted))
        } finally {
            deleted?.let { snapshot ->
                withContext(NonCancellable) { conversationRepo.releaseDeletedConversationAttachments(snapshot) }
            }
        }
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            chatService.deleteConversationsOfAssistant(assistant.id)
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            chatService.togglePinStatus(conversationId)
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        conversationRepo.getPinnedConversations()

}
