/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null,
        val autoStartVoice: Boolean = false,
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data class WebView(val url: String = "", val content: String = "") : Screen

    @Serializable
    data object SettingDisplay : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingDisplayTheme : Screen

    @Serializable
    data object SettingDisplayColor : Screen

    @Serializable
    data object SettingDisplayTransparency : Screen

    @Serializable
    data object SettingDisplayIllustration : Screen

    @Serializable
    data object SettingDisplayMessage : Screen

    @Serializable
    data object SettingDisplayCodeInteraction : Screen

    @Serializable
    data object SettingDisplayGeneral : Screen

    @Serializable
    data object SettingDisplayNotification : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data object SettingSpeech : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingDonate : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object Developer : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object SecurityAudit : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(val id: String) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen

    @Serializable
    data object Anniversary : Screen

    @Serializable
    data object SettingSystemTools : Screen

    @Serializable
    data object SettingSecurity : Screen

    @Serializable
    data object SettingProactiveMessage : Screen

    @Serializable
    data object SettingExtraInjection : Screen

    @Serializable
    data object Health : Screen

    @Serializable
    data object SettingPlugins : Screen

    @Serializable
    data object Workflows : Screen

    @Serializable
    data class WorkflowDetail(val id: String) : Screen

    @Serializable
    data class PluginDetail(val pluginId: String) : Screen

    @Serializable
    data class PluginFolder(val folderId: String) : Screen

    @Serializable
    data class PluginWebView(val pluginId: String, val entryPath: String, val conversationId: String = "") : Screen

    @Serializable
    data class Gongdu(val conversationId: String) : Screen

    @Serializable
    data class PluginDeclarativeUI(val pluginId: String) : Screen

    @Serializable
    data object EmojiPicker : Screen

    @Serializable
    data object ExternalMemories : Screen

    @Serializable
    data class VoiceCall(val conversationId: String) : Screen

    /** 视频通话面板：独立入口，和语音通话共用同一通电话的后台服务。 */
    @Serializable
    data class VideoCall(val conversationId: String) : Screen

    /**
     * AI 主动发起语音通话时弹出的来电界面.
     * conversationId 用于接听后跳转到对应会话的通话页.
     */
    @Serializable
    data class IncomingCall(val conversationId: String, val interactionId: String? = null) : Screen

    @Serializable
    data object MiniAppManager : Screen

    @Serializable
    data class MiniAppEdit(val id: String?) : Screen

    @Serializable
    data class MiniApp(val url: String, val title: String?) : Screen

    @Serializable
    data class Legal(val titleRes: Int, val contentRes: Int) : Screen
}
