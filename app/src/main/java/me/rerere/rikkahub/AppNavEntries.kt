/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.core.net.toUri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.ui.PluginDetailPage
import me.rerere.rikkahub.plugin.ui.PluginFolderPage
import me.rerere.rikkahub.plugin.ui.PluginManagePage
import me.rerere.rikkahub.plugin.ui.PluginUIDeclarativePage
import me.rerere.rikkahub.plugin.webview.PluginWebViewPage
import me.rerere.rikkahub.ui.components.ui.EmojiPickerPage
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.anniversary.AnniversaryPage
import me.rerere.rikkahub.ui.pages.assistant.AssistantPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantBasicPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantExtensionsPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantLocalToolPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMcpPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMemoryPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantPromptPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantRequestPage
import me.rerere.rikkahub.ui.pages.backup.BackupPage
import me.rerere.rikkahub.ui.pages.chat.ChatPage
import me.rerere.rikkahub.ui.pages.debug.DebugPage
import me.rerere.rikkahub.ui.pages.developer.DeveloperPage
import me.rerere.rikkahub.ui.pages.disclaimer.LegalPage
import me.rerere.rikkahub.ui.pages.extensions.ExtensionsPage
import me.rerere.rikkahub.ui.pages.extensions.ExternalMemoriesPage
import me.rerere.rikkahub.ui.pages.extensions.PromptPage
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesPage
import me.rerere.rikkahub.ui.pages.extensions.SkillDetailPage
import me.rerere.rikkahub.ui.pages.extensions.SkillsPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspacePage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalPage
import me.rerere.rikkahub.ui.pages.favorite.FavoritePage
import me.rerere.rikkahub.ui.pages.gongdu.GongduPage
import me.rerere.rikkahub.ui.pages.health.HealthPage
import me.rerere.rikkahub.ui.pages.history.HistoryPage
import me.rerere.rikkahub.ui.pages.imggen.ImageGenPage
import me.rerere.rikkahub.ui.pages.log.LogPage
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppEditPage
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppManagerPage
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppPage
import me.rerere.rikkahub.ui.pages.search.SearchPage
import me.rerere.rikkahub.ui.pages.security.SecurityAuditPage
import me.rerere.rikkahub.ui.pages.setting.SecuritySettingPage
import me.rerere.rikkahub.ui.pages.setting.SettingAboutPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayCodeInteractionPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayColorPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayGeneralPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayIllustrationPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayMessagePage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayNotificationPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayTransparencyPage
import me.rerere.rikkahub.ui.pages.setting.SettingDonatePage
import me.rerere.rikkahub.ui.pages.setting.SettingExtraInjectionPage
import me.rerere.rikkahub.ui.pages.setting.SettingFilesPage
import me.rerere.rikkahub.ui.pages.setting.SettingMcpPage
import me.rerere.rikkahub.ui.pages.setting.SettingModelPage
import me.rerere.rikkahub.ui.pages.setting.SettingPage
import me.rerere.rikkahub.ui.pages.setting.SettingProactiveMessagePage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchPage
import me.rerere.rikkahub.ui.pages.setting.SettingSpeechPage
import me.rerere.rikkahub.ui.pages.setting.SettingSystemToolsPage
import me.rerere.rikkahub.ui.pages.setting.SettingThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingWebPage
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerPage
import me.rerere.rikkahub.ui.pages.stats.StatsPage
import me.rerere.rikkahub.ui.pages.translator.TranslatorPage
import me.rerere.rikkahub.ui.pages.video.VideoCallPage
import me.rerere.rikkahub.ui.pages.voice.IncomingCallRoute
import me.rerere.rikkahub.ui.pages.voice.VoiceCallPage
import me.rerere.rikkahub.ui.pages.webview.WebViewPage
import me.rerere.rikkahub.workflow.ui.WorkflowDetailScreen
import me.rerere.rikkahub.workflow.ui.WorkflowsScreen
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal fun EntryProviderScope<NavKey>.registerAppNavEntries(
    backStack: MutableList<NavKey>,
    onStopTts: () -> Unit,
) {
    registerChatAndAssistantEntries()
    registerSettingsEntries()
    registerExtensionAndWorkspaceEntries()
    registerPluginCallAndMiniAppEntries(
        backStack = backStack,
        onStopTts = onStopTts,
    )
}

private fun EntryProviderScope<NavKey>.registerChatAndAssistantEntries() {
    entry<Screen.Chat>(
        metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
    ) { key ->
        ChatPage(
            id = Uuid.parse(key.id),
            text = key.text,
            files = key.files.map { it.toUri() },
            nodeId = key.nodeId?.let { Uuid.parse(it) },
            autoStartVoice = key.autoStartVoice,
        )
    }

    entry<Screen.ShareHandler> { key ->
        ShareHandlerPage(
            text = key.text,
            image = key.streamUri,
        )
    }

    entry<Screen.History> {
        HistoryPage()
    }

    entry<Screen.Favorite> {
        FavoritePage()
    }

    entry<Screen.Assistant> {
        AssistantPage()
    }

    entry<Screen.AssistantDetail> { key ->
        AssistantDetailPage(key.id)
    }

    entry<Screen.AssistantBasic> { key ->
        AssistantBasicPage(key.id)
    }

    entry<Screen.AssistantPrompt> { key ->
        AssistantPromptPage(key.id)
    }

    entry<Screen.AssistantMemory> { key ->
        AssistantMemoryPage(key.id)
    }

    entry<Screen.AssistantRequest> { key ->
        AssistantRequestPage(key.id)
    }

    entry<Screen.AssistantMcp> { key ->
        AssistantMcpPage(key.id)
    }

    entry<Screen.AssistantLocalTool> { key ->
        AssistantLocalToolPage(key.id)
    }

    entry<Screen.AssistantInjections> { key ->
        AssistantExtensionsPage(key.id)
    }

    entry<Screen.Translator> {
        TranslatorPage()
    }

    entry<Screen.ImageGen> {
        ImageGenPage()
    }

    entry<Screen.MessageSearch> {
        SearchPage()
    }
}

private fun EntryProviderScope<NavKey>.registerSettingsEntries() {
    entry<Screen.Setting> {
        SettingPage()
    }

    entry<Screen.Backup> {
        BackupPage()
    }

    entry<Screen.SettingDisplay> {
        SettingDisplayPage()
    }

    entry<Screen.SettingTheme> {
        SettingThemePage()
    }

    entry<Screen.SettingDisplayTheme> {
        SettingDisplayThemePage()
    }

    entry<Screen.SettingDisplayColor> {
        SettingDisplayColorPage()
    }

    entry<Screen.SettingDisplayTransparency> {
        SettingDisplayTransparencyPage()
    }

    entry<Screen.SettingDisplayIllustration> {
        SettingDisplayIllustrationPage()
    }

    entry<Screen.SettingDisplayMessage> {
        SettingDisplayMessagePage()
    }

    entry<Screen.SettingDisplayCodeInteraction> {
        SettingDisplayCodeInteractionPage()
    }

    entry<Screen.SettingDisplayGeneral> {
        SettingDisplayGeneralPage()
    }

    entry<Screen.SettingDisplayNotification> {
        SettingDisplayNotificationPage()
    }

    entry<Screen.SettingProvider> {
        SettingProviderPage()
    }

    entry<Screen.SettingProviderDetail> { key ->
        val id = Uuid.parse(key.providerId)
        SettingProviderDetailPage(id = id)
    }

    entry<Screen.SettingModels> {
        SettingModelPage()
    }

    entry<Screen.SettingAbout> {
        SettingAboutPage()
    }

    entry<Screen.SettingSearch> {
        SettingSearchPage()
    }

    entry<Screen.SettingSearchDetail> { key ->
        val id = Uuid.parse(key.serviceId)
        SettingSearchDetailPage(id)
    }

    entry<Screen.SettingSpeech> {
        SettingSpeechPage()
    }

    entry<Screen.SettingMcp> {
        SettingMcpPage()
    }

    entry<Screen.SettingDonate> {
        SettingDonatePage()
    }

    entry<Screen.SettingFiles> {
        SettingFilesPage()
    }

    entry<Screen.SettingWeb> {
        SettingWebPage()
    }

    entry<Screen.Developer> {
        DeveloperPage()
    }

    entry<Screen.Debug> {
        DebugPage()
    }

    entry<Screen.Log> {
        LogPage()
    }

    entry<Screen.SecurityAudit> {
        SecurityAuditPage()
    }

    entry<Screen.SettingSystemTools> {
        SettingSystemToolsPage()
    }

    entry<Screen.SettingSecurity> {
        SecuritySettingPage()
    }

    entry<Screen.SettingProactiveMessage> {
        SettingProactiveMessagePage()
    }

    entry<Screen.SettingExtraInjection> {
        SettingExtraInjectionPage()
    }

    entry<Screen.Legal> { key ->
        LegalPage(
            titleRes = key.titleRes,
            contentRes = key.contentRes,
        )
    }
}

private fun EntryProviderScope<NavKey>.registerExtensionAndWorkspaceEntries() {
    entry<Screen.Extensions> {
        ExtensionsPage()
    }

    entry<Screen.QuickMessages> {
        QuickMessagesPage()
    }

    entry<Screen.ExternalMemories> {
        ExternalMemoriesPage()
    }

    entry<Screen.Prompts> {
        PromptPage()
    }

    entry<Screen.Skills> {
        SkillsPage()
    }

    entry<Screen.SkillDetail> { key ->
        SkillDetailPage(skillName = key.skillName)
    }

    entry<Screen.Workspaces> {
        WorkspacePage()
    }

    entry<Screen.WorkspaceDetail> { key ->
        WorkspaceDetailPage(key.id)
    }

    entry<Screen.WorkspaceTerminal> { key ->
        WorkspaceTerminalPage(key.id)
    }

    entry<Screen.Health> {
        HealthPage()
    }

    entry<Screen.Stats> {
        StatsPage()
    }

    entry<Screen.Anniversary> {
        AnniversaryPage()
    }

    entry<Screen.Workflows> {
        WorkflowsScreen()
    }

    entry<Screen.WorkflowDetail> { key ->
        WorkflowDetailScreen(key.id)
    }

    entry<Screen.WebView> { key ->
        WebViewPage(key.url, key.content)
    }
}

private fun EntryProviderScope<NavKey>.registerPluginCallAndMiniAppEntries(
    backStack: MutableList<NavKey>,
    onStopTts: () -> Unit,
) {
    entry<Screen.SettingPlugins> {
        val nav = LocalNavController.current
        PluginManagePage(
            onNavigateToFolder = { folderId ->
                nav.navigate(Screen.PluginFolder(folderId))
            },
            onNavigateToDetail = { pluginId ->
                nav.navigate(Screen.PluginDetail(pluginId))
            },
        )
    }

    entry<Screen.PluginFolder> { key ->
        val nav = LocalNavController.current
        PluginFolderPage(
            folderId = key.folderId,
            onNavigateBack = { backStack.removeLastOrNull() },
            onNavigateToDetail = { pluginId ->
                nav.navigate(Screen.PluginDetail(pluginId))
            },
        )
    }

    entry<Screen.PluginDetail> { key ->
        val nav = LocalNavController.current
        PluginDetailPage(
            pluginId = key.pluginId,
            onNavigateBack = { backStack.removeLastOrNull() },
            onNavigateToCustomPage = { _ -> },
            onNavigateToWebView = { pluginId, entryPath ->
                nav.navigate(Screen.PluginWebView(pluginId, entryPath))
            },
            onNavigateToDeclarativeUI = { pluginId ->
                nav.navigate(Screen.PluginDeclarativeUI(pluginId))
            },
        )
    }

    entry<Screen.PluginDeclarativeUI> { key ->
        val pluginManager = koinInject<PluginManager>()
        PluginUIDeclarativePage(
            pluginId = key.pluginId,
            pluginManager = pluginManager,
            onNavigateBack = { backStack.removeLastOrNull() },
        )
    }

    entry<Screen.PluginWebView> { key ->
        if (key.pluginId == "com.orangechat.plugin.orange-watch") {
            GongduPage(
                conversationId = key.conversationId,
                onNavigateBack = { backStack.removeLastOrNull() },
            )
            return@entry
        }
        val pluginManager = koinInject<PluginManager>()
        PluginWebViewPage(
            pluginId = key.pluginId,
            htmlEntryPath = key.entryPath,
            pluginManager = pluginManager,
            conversationId = key.conversationId,
            onNavigateBack = { backStack.removeLastOrNull() },
        )
    }

    entry<Screen.Gongdu> { key ->
        GongduPage(
            conversationId = key.conversationId,
            onNavigateBack = { backStack.removeLastOrNull() },
        )
    }

    entry<Screen.EmojiPicker> {
        EmojiPickerPage(
            onBack = { backStack.removeLastOrNull() },
        )
    }

    entry<Screen.VoiceCall> { key ->
        VoiceCallPage(
            conversationId = Uuid.parse(key.conversationId),
            onBack = { backStack.removeLastOrNull() },
        )
    }

    entry<Screen.VideoCall> { key ->
        VideoCallPage(
            conversationId = Uuid.parse(key.conversationId),
            onBack = { backStack.removeLastOrNull() },
        )
    }

    entry<Screen.IncomingCall> { key ->
        IncomingCallRoute(
            conversationId = Uuid.parse(key.conversationId),
            interactionId = key.interactionId,
            onCollapse = { backStack.removeLastOrNull() },
            stopSpeech = onStopTts,
            onConnected = {
                backStack.removeLastOrNull()
                backStack.add(Screen.VoiceCall(key.conversationId))
            },
        )
    }

    entry<Screen.MiniAppManager> {
        MiniAppManagerPage()
    }

    entry<Screen.MiniAppEdit> { key ->
        MiniAppEditPage(id = key.id)
    }

    entry<Screen.MiniApp> { key ->
        MiniAppPage(
            url = key.url,
            title = key.title,
        )
    }
}
