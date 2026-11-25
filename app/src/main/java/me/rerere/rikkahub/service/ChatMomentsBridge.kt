/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatDraftAccess
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatDraftAccessSnapshot
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatDraftContract
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatDraftIdentity
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatDraftInstruction
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatDraftPolicy
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatSelection
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatSelectionPrepareResult
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatSelectionStore
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localtasks.LocalTaskIds
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettingsStore
import me.rerere.rikkahub.plugin.localtasks.SharedPreferencesLocalTaskStore
import kotlin.uuid.Uuid

internal class ChatMomentsBridge(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val localTools: LocalTools,
    private val submitMomentsInstruction: (Uuid, List<UIMessagePart>, Uuid) -> QueuedMessage?,
) {
    fun prepareMomentsChatSelection(
        conversation: Conversation,
        selectedMessages: List<UIMessage>,
        includeImages: Boolean = false,
    ): LocalMomentsChatSelectionPrepareResult {
        val conversationId = conversation.id
        if (!conversation.messageNodesLoaded) {
            return LocalMomentsChatSelectionPrepareResult.Rejected("消息尚未完整加载，请重新打开会话后操作")
        }
        val requestedIds = selectedMessages.map { it.id.toString() }.distinct()
        if (requestedIds.isEmpty()) {
            return LocalMomentsChatSelectionPrepareResult.Rejected("请先选择要整理的消息")
        }
        if (requestedIds.size > LocalDataApi.MAX_CHAT_MESSAGES) {
            return LocalMomentsChatSelectionPrepareResult.Rejected(
                "一张朋友圈卡片最多 ${LocalDataApi.MAX_CHAT_MESSAGES} 条消息，请减少选择后重试",
            )
        }
        // Re-read from the authoritative conversation instead of trusting the UI list.
        val currentMessages = conversation.currentMessages
        val byId = currentMessages.associateBy { it.id.toString() }
        val ordered = requestedIds.mapNotNull { byId[it] }
        if (ordered.size != requestedIds.size) {
            return LocalMomentsChatSelectionPrepareResult.Rejected("选中的消息已变化，请重新选择")
        }
        if (ordered.any { it.role != MessageRole.USER && it.role != MessageRole.ASSISTANT }) {
            return LocalMomentsChatSelectionPrepareResult.Rejected("只能整理你和助手的聊天消息")
        }
        if (ordered.none { message ->
                message.parts.any { part ->
                    (part is UIMessagePart.Text && part.text.isNotBlank()) ||
                        (part is UIMessagePart.Image && part.url.isNotBlank())
                }
            }
        ) {
            return LocalMomentsChatSelectionPrepareResult.Rejected(
                "选中的消息没有可写入的正文或图片；系统内容、思考过程和工具结果不会进入卡片",
            )
        }

        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantOf(conversation)
        val model = settings.getChatModelOf(conversation)
            ?: return LocalMomentsChatSelectionPrepareResult.Rejected("请先为会话选择可用的聊天模型")
        if (!model.abilities.contains(ModelAbility.TOOL)) {
            return LocalMomentsChatSelectionPrepareResult.Rejected(
                "当前模型不支持工具调用，无法让助手整理朋友圈草稿",
            )
        }
        val pluginManager = localTools.hostPluginManager
        if (pluginManager.getPlugin(LocalMomentsChatDraftContract.PLUGIN_ID) == null &&
            pluginManager.isLoading.value
        ) {
            return LocalMomentsChatSelectionPrepareResult.Rejected("插件列表正在加载，请稍后重试")
        }
        val access = momentsChatDraftAccess(
            conversationId = conversationId,
            assistantId = assistant.id.toString(),
            includeImages = includeImages,
        )
        access.denial?.let {
            return LocalMomentsChatSelectionPrepareResult.Rejected(it.message)
        }
        if (!access.canUseImages && ordered.none { message ->
                message.parts.any { part -> part is UIMessagePart.Text && part.text.isNotBlank() }
            }
        ) {
            return LocalMomentsChatSelectionPrepareResult.Rejected(
                "选中的消息只有图片，但当前未授权图片草稿；请开启图片或改选含正文的消息",
            )
        }

        val anchor = Uuid.random()
        val selection = LocalMomentsChatSelection(
            conversationId = conversationId.toString(),
            assistantId = assistant.id.toString(),
            anchorMessageId = anchor.toString(),
            title = conversation.title,
            messages = currentMessages,
            selectedMessageIds = requestedIds,
            includeImagesRequested = includeImages,
            includeImages = access.canUseImages,
            identity = LocalMomentsChatDraftIdentity(
                userName = settings.displaySetting.userNickname.ifBlank { "我" },
                assistantName = assistant.name.ifBlank { "助手" },
                userAvatar = settings.displaySetting.userAvatar,
                assistantAvatar = assistant.avatar,
            ),
        )
        val prepared = LocalMomentsChatSelectionStore.prepare(selection)
        if (prepared is LocalMomentsChatSelectionPrepareResult.Rejected) return prepared
        val queued = submitMomentsInstruction(
            conversationId,
            listOf(
                UIMessagePart.Text(
                    LocalMomentsChatDraftInstruction.text(
                        messageCount = ordered.size,
                        includeImages = access.canUseImages,
                    ),
                ),
            ),
            anchor,
        )
        if (queued == null) {
            LocalMomentsChatSelectionStore.clear(conversationId.toString())
            return LocalMomentsChatSelectionPrepareResult.Rejected(
                "整理请求没有进入发送队列，请稍后重试；未写入任何草稿",
            )
        }
        return LocalMomentsChatSelectionPrepareResult.Prepared(
            anchorMessageId = anchor.toString(),
            messageCount = ordered.size,
            imageOmissionReason = access.imageOmissionReason,
        )
    }

    private fun momentsChatDraftAccess(
        conversationId: Uuid,
        assistantId: String,
        includeImages: Boolean,
    ): LocalMomentsChatDraftAccess {
        val pluginManager = localTools.hostPluginManager
        val plugin = pluginManager.getPlugin(LocalMomentsChatDraftContract.PLUGIN_ID)
        val approved = context.getSharedPreferences(
            LocalMomentsChatDraftContract.HOST_GRANT_PREFERENCES,
            Context.MODE_PRIVATE,
        ).getStringSet(LocalMomentsChatDraftContract.PLUGIN_ID, emptySet()).orEmpty()
        val taskSettings = LocalTaskSettingsStore(
            SharedPreferencesLocalTaskStore(
                context,
                LocalTaskIds.storeName(LocalMomentsChatDraftContract.PLUGIN_ID),
            ),
        ).get()
        return LocalMomentsChatDraftPolicy.evaluate(
            LocalMomentsChatDraftAccessSnapshot(
                pluginInstalled = plugin != null,
                pluginEnabled = plugin?.isEnabled == true,
                pluginLoadError = plugin?.loadError,
                grantedCapabilities = plugin?.manifest?.permissions?.toSet().orEmpty()
                    .intersect(approved),
                taskSettings = taskSettings,
                assistantId = assistantId,
                turnAssistantId = assistantId,
                assistantKnown = true,
                headless = false,
                conversationId = conversationId.toString(),
                turnConversationId = conversationId.toString(),
                userSelectionAvailable = true,
                imagesRequested = includeImages,
            ),
        )
    }
}
