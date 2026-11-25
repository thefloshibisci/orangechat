/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Application
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.interaction.AssistantCustomizationImages
import me.rerere.rikkahub.data.interaction.AssistantCustomizationPatch
import me.rerere.rikkahub.data.interaction.MAX_CUSTOM_CHAT_REACTION_PRESETS
import me.rerere.rikkahub.data.interaction.ReactionPresetToolResult
import me.rerere.rikkahub.data.interaction.customizeAssistant
import me.rerere.rikkahub.data.interaction.interactionText
import me.rerere.rikkahub.data.interaction.normalizeCustomChatReactionPreset
import me.rerere.rikkahub.data.interaction.normalizeCustomChatReactionPresets
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

internal class ChatInteractionCoordinator(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
    private val getOrCreateSession: (Uuid) -> ConversationSession,
    private val sendMessage: (Uuid, List<UIMessagePart>, UIMessageAnnotation.AvatarInteraction) -> Unit,
    private val saveConversationLocked: suspend (Uuid, Conversation) -> Unit,
    private val addError: (Throwable, Uuid?, String?) -> Unit,
) {
    fun sendAvatarInteraction(conversationId: Uuid, targetMessage: UIMessage) {
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) return
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(session.state.value.assistantId) ?: return
        if (!assistant.enableAvatarInteractions) return
        if (session.state.value.currentMessages.none { it.id == targetMessage.id }) return
        val target = targetMessage.role
        if (target != MessageRole.USER && target != MessageRole.ASSISTANT) return
        val targetName = if (target == MessageRole.USER) "自己" else assistant.name.ifBlank { "AI" }
        val text = runCatching {
            interactionText(
                "你",
                targetName,
                assistant.userInteractionAction.ifBlank { "拍了拍" },
                assistant.userInteractionSuffix,
            )
        }.getOrElse {
            addError(it, conversationId, "头像互动")
            return
        }
        sendMessage(
            conversationId,
            listOf(UIMessagePart.Text(text)),
            UIMessageAnnotation.AvatarInteraction(
                actor = "user",
                target = if (target == MessageRole.USER) "user" else "assistant",
                targetMessageId = targetMessage.id.toString(),
            ),
        )
    }

    suspend fun appendAssistantAvatarInteraction(conversationId: Uuid, assistant: Assistant): String {
        val session = getOrCreateSession(conversationId)
        return session.saveMutex.withLock {
            check(conversationRepo.existsConversationById(conversationId)) { "聊天已删除" }
            val current = session.state.value
            check(current.assistantId == assistant.id) { "助手已改变" }
            val liveAssistant = settingsStore.settingsFlow.value.getAssistantById(assistant.id)
            check(liveAssistant?.enableAvatarInteractions == true) {
                "头像互动已关闭"
            }
            val text = interactionText(
                liveAssistant.name.ifBlank { "AI" },
                "你",
                liveAssistant.assistantInteractionAction.ifBlank { "拍了拍" },
                liveAssistant.assistantInteractionSuffix,
            )
            val notice = UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(UIMessagePart.Text(text)),
                annotations = listOf(
                    UIMessageAnnotation.AvatarInteraction(
                        actor = "assistant",
                        target = "user",
                        targetMessageId = current.currentMessages.lastOrNull {
                            it.role == MessageRole.USER && it.annotations.none { annotation ->
                                annotation is UIMessageAnnotation.AvatarInteraction
                            }
                        }?.id?.toString(),
                    ),
                ),
            )
            saveConversationLocked(
                conversationId,
                current.copy(messageNodes = current.messageNodes + notice.toMessageNode()),
            )
            text
        }
    }

    suspend fun customizeCurrentAssistant(
        conversationId: Uuid,
        assistantId: Uuid,
        patch: AssistantCustomizationPatch?,
    ): Assistant {
        val session = getOrCreateSession(conversationId)
        fun checkCaller() {
            session.requireActive()
            check(session.state.value.assistantId == assistantId) { "此聊天的助手已改变" }
        }
        checkCaller()
        val images = AssistantCustomizationImages(context, saveImage = { file, mime ->
            // Keep upload files reusable and generated/remote images durable across restarts and backup.
            if (filesManager.isManagedChatFile(file.toURI().toString())) {
                file.toURI().toString()
            } else {
                val saved = filesManager.saveManagedFromUri(
                    folder = FileFolders.UPLOAD,
                    uri = file.toURI().toString().toUri(),
                    displayName = "assistant-image.${mime.substringAfter('/').substringBefore('+')}",
                    mimeType = mime,
                )
                filesManager.getFile(saved).toURI().toString()
            }
        })
        return customizeAssistant(
            assistantId = assistantId,
            patch = patch,
            readSettings = {
                checkCaller()
                check(conversationRepo.existsConversationById(conversationId)) { "聊天已删除" }
                settingsStore.settingsFlowRaw.first()
            },
            importImage = images::importImage,
            commit = { edit ->
                session.saveMutex.withLock {
                    checkCaller()
                    check(conversationRepo.existsConversationById(conversationId)) { "聊天已删除" }
                    settingsStore.update { current ->
                        checkCaller()
                        edit(current)
                    }
                }
            },
        )
    }

    suspend fun manageReactionPresets(
        operation: String,
        value: String,
    ): ReactionPresetToolResult {
        val initial = settingsStore.settingsFlowRaw.first()
        val currentPresets = normalizeCustomChatReactionPresets(initial.customReactionPresets)
        return when (operation.trim().lowercase()) {
            "list" -> ReactionPresetToolResult(
                success = true,
                message = "Loaded ${currentPresets.size} custom kaomoji presets.",
                presets = currentPresets,
            )

            "add" -> {
                val normalized = normalizeCustomChatReactionPreset(value)
                    ?: return ReactionPresetToolResult(
                        success = false,
                        message = "Invalid preset, built-in duplicate, or value is too long.",
                        presets = currentPresets,
                    )
                if (normalized in currentPresets) {
                    return ReactionPresetToolResult(
                        success = true,
                        message = "Preset already exists.",
                        presets = currentPresets,
                    )
                }
                if (currentPresets.size >= MAX_CUSTOM_CHAT_REACTION_PRESETS) {
                    return ReactionPresetToolResult(
                        success = false,
                        message = "Custom preset limit reached.",
                        presets = currentPresets,
                    )
                }
                settingsStore.update { settings ->
                    settings.copy(
                        customReactionPresets = normalizeCustomChatReactionPresets(
                            settings.customReactionPresets + normalized,
                        ),
                    )
                }
                val latest = normalizeCustomChatReactionPresets(
                    settingsStore.settingsFlowRaw.first().customReactionPresets,
                )
                ReactionPresetToolResult(
                    success = normalized in latest,
                    message = if (normalized in latest) "Preset added." else "Unable to save preset.",
                    presets = latest,
                )
            }

            "remove" -> {
                val normalized = normalizeCustomChatReactionPreset(value)
                    ?: return ReactionPresetToolResult(
                        success = false,
                        message = "Built-in presets cannot be removed; value was not a custom preset.",
                        presets = currentPresets,
                    )
                settingsStore.update { settings ->
                    settings.copy(
                        customReactionPresets = settings.customReactionPresets.filterNot {
                            it == normalized
                        },
                    )
                }
                val latest = normalizeCustomChatReactionPresets(
                    settingsStore.settingsFlowRaw.first().customReactionPresets,
                )
                ReactionPresetToolResult(
                    success = normalized !in latest,
                    message = if (normalized !in latest) "Preset removed." else "Unable to remove preset.",
                    presets = latest,
                )
            }

            else -> ReactionPresetToolResult(
                success = false,
                message = "Unsupported operation: $operation",
                presets = currentPresets,
            )
        }
    }
}
