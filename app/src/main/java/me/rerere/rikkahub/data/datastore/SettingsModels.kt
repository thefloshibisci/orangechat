/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.MiniApp
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class Settings(
    val gongdu: me.rerere.rikkahub.data.gongdu.GongduSetting = me.rerere.rikkahub.data.gongdu.GongduSetting(),
    @Transient
    val init: Boolean = false,
    // Legacy backup fields; no longer gate application startup.
    val disclaimerAccepted: Boolean = false,
    val disclaimerAcceptedAt: Int = 0,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val customReactionPresets: List<String> = emptyList(),
    val displaySetting: DisplaySetting = DisplaySetting(),
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid = Uuid.random(),
    val imageGenerationModelId: Uuid = DEFAULT_IMAGE_GENERATION_MODEL_ID,
    val imageGenerationAspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val suggestionModelId: Uuid = Uuid.random(),
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = DEFAULT_OCR_MODEL_ID,
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid? = null,
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val voiceCallKeepListeningDuringReply: Boolean = true,
    val videoCallSetting: me.rerere.rikkahub.data.video.VideoCallSetting = me.rerere.rikkahub.data.video.VideoCallSetting(),
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = true,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val autoBackupConfig: AutoBackupConfig = AutoBackupConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
    val systemToolsSetting: SystemToolsSetting = SystemToolsSetting(),
    val proactiveMessageSetting: ProactiveMessageSetting = ProactiveMessageSetting(),
    val keepAliveEnabled: Boolean = false,
    val externalMemories: List<ExternalMemory> = emptyList(),
    val sereinHook: me.rerere.rikkahub.data.serein.SereinHookConfig = me.rerere.rikkahub.data.serein.SereinHookConfig(),
    val miniApps: List<MiniApp> = emptyList(),
    val forceConfirmToolCalls: Boolean = true,
    val workflowHeadlessBlockSensitive: Boolean = true,
    val autoApproveAllTools: Boolean = false,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,
    @SerialName("custom")
    CUSTOM,
}

@Serializable
enum class DisplayMaterialMode {
    @SerialName("follow_theme")
    FOLLOW_THEME,
    @SerialName("flat")
    FLAT,
    @SerialName("translucent")
    TRANSLUCENT,
    @SerialName("glass")
    GLASS,
}

@Serializable
data class AnniversaryEntry(
    val id: String,
    val title: String,
    /** ISO-8601 local date, for example 2023-05-20. */
    val startDate: String,
    /** true 表示未来倒数日；false 表示从开始日累计天数。 */
    val countdown: Boolean = false,
)

@Serializable
data class DisplaySetting(
    val chatDisplayMode: ChatDisplayMode = ChatDisplayMode.STANDARD,
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateBelowName: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = false,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val materialMode: DisplayMaterialMode = DisplayMaterialMode.FOLLOW_THEME,
    val interfaceRealtimeRendering: Boolean = false,
    // 聊天气泡实时背景模糊（默认关闭，避免旧用户升级后自动增加每气泡 GPU 成本；固定 3 dp）
    val chatBubbleRealtimeBlur: Boolean = false,
    // 抽屉实时模糊强度（单位 dp，设置页 Slider 约束 3..20）
    val interfaceBlurRadius: Float = 8f,
    val interfaceSurfaceOpacity: Float = 82f,
    val popupSurfaceOpacity: Float = 90f,
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val chatBubbleTransparency: Float = 0f,
    val thinkingChainTransparency: Float = 0f,
    // 自定义字体
    val customFontPath: String = "",
    // 设置页自定义背景
    val settingsBackgroundPath: String = "",
    // 输入框自定义背景
    val inputBackgroundPath: String = "",
    // 头像框（QQ挂件风格）
    val userAvatarFramePath: String = "",
    val aiAvatarFramePath: String = "",
    val userAvatarFrameOffsetX: Float = 0f,
    val userAvatarFrameOffsetY: Float = 0f,
    val userAvatarFrameScale: Float = 1f,
    val aiAvatarFrameOffsetX: Float = 0f,
    val aiAvatarFrameOffsetY: Float = 0f,
    val aiAvatarFrameScale: Float = 1f,
    // 侧边栏背景
    val drawerBackgroundPath: String = "",
    // 侧边栏根面板不透明度（百分比）
    val drawerSurfaceOpacity: Float = 100f,
    // 侧边栏元素透明度
    val drawerItemAlpha: Float = 1f,
    // 显示在聊天侧边栏“插件”页中的快捷插件 ID
    val drawerPluginShortcutIds: Set<String> = emptySet(),
    // 已移除功能的历史字段，仅用于兼容旧设置；不再提供界面或读取执行路径。
    val watchControlColor: Long? = null,
    val watchControlAlpha: Float = 0.18f,
    // 纪念日：完全保存在本机；保留旧选中 ID 以兼容历史设置
    val anniversaries: List<AnniversaryEntry> = emptyList(),
    val anniversaryAiInjectionEnabled: Boolean = false,
    val anniversaryAiInjectionId: String? = null,
    // 颜色自定义
    val chatTextColor: Long? = null,
    val globalTextColor: Long? = null,
    val userBubbleColor: Long? = null,
    val assistantBubbleColor: Long? = null,
    val thinkingFontSizeRatio: Float = 1.0f,
    val thinkingBubbleColor: Long? = null,
    val chatBackgroundColor: Long? = null,
    val primaryColor: Long? = null,
    val inputFieldColor: Long? = null,
    // 气泡背景图 & 圆角
    val userBubbleImagePath: String = "",
    val assistantBubbleImagePath: String = "",
    val userBubbleFrame: BubbleFrameSetting = BubbleFrameSetting(),
    val assistantBubbleFrame: BubbleFrameSetting = BubbleFrameSetting(),
    val bubbleCornerRadius: Float = 16f,
    val bubbleImageOverlayEnabled: Boolean = false, // 关=纯图片, 开=图片+主题色遮罩
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid): Model? {
    return this.providers.findModelById(uuid)
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

/** The open conversation can belong to a different assistant from the global selection. */
fun Settings.getChatModelForAssistant(assistantId: Uuid): Model? {
    val assistant = getAssistantById(assistantId) ?: getCurrentAssistant()
    return findModelById(assistant.chatModelId ?: chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}
