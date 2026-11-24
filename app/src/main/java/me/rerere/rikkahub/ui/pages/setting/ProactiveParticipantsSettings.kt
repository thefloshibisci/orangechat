package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.AssistantProactiveConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.LightConversationEntity
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.service.ProactiveTaskExecutor
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.buildCardGroupItems
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * The participant list lives on the existing proactive-message page. A participant that has no
 * per-assistant override uses the global proactive settings; expanding a row and turning on
 * "另设该助手" is the only way to reveal the optional cooldown/limit/prompt/active-hours fields.
 *
 * 投递窗口：默认"自动跟随最新聊天"（该助手最近更新的会话）；展开后可"固定到某个窗口"。
 * 所有状态都在组合体里读取，列表用 [buildCardGroupItems] 一次性建好——
 * 旧写法把展开状态读在 CardGroup 的内容 lambda 里，状态变化只重建作用域、不重排项目。
 */
@Composable
internal fun ProactiveParticipantsSettings(settings: Settings, vm: SettingVM) {
    val context = LocalContext.current
    val repository: ConversationRepository = koinInject()
    val store: SettingsStore = koinInject()
    val chat: ChatService = koinInject()
    val executor = remember(context) { ProactiveTaskExecutor(context, store, repository, chat) }
    LaunchedEffect(settings.init) {
        if (!settings.init) withContext(Dispatchers.IO) {
            executor.migrate()
            executor.syncLatestConversations()
        }
    }
    // The old pause switch was removed from this page. Clear a legacy paused flag so the
    // remaining enable switch and manual trigger are not silently blocked by hidden state.
    LaunchedEffect(Unit) {
        if (settings.proactiveMessageSetting.globalPolicy.paused) {
            vm.updateProactiveSetting { current ->
                current.copy(globalPolicy = current.globalPolicy.copy(paused = false))
            }
        }
    }
    var selecting by remember { mutableStateOf<String?>(null) }
    var windows by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    // 展开状态必须在 LazyColumn 项里 rememberSaveable：滚出再回来仍然记得展开。
    var expandedAssistantId by rememberSaveable { mutableStateOf<String?>(null) }
    // 行文案要显示窗口标题而不是裸 id：一次性拉最近 50 个会话头备用。
    var headers by remember { mutableStateOf<Map<String, LightConversationEntity>>(emptyMap()) }
    var latestConversationId by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(settings.assistants) {
        val (collectedHeaders, latest) = withContext(Dispatchers.IO) {
            val collectedHeaders = mutableMapOf<String, LightConversationEntity>()
            val latest = mutableMapOf<String, String>()
            settings.assistants.forEach { assistant ->
                val heads = runCatching {
                    repository.getRecentConversationHeaders(assistant.id, 50)
                }.getOrDefault(emptyList())
                heads.forEach { collectedHeaders[it.id] = it }
                heads.firstOrNull()?.let { latest[assistant.id.toString()] = it.id }
                    ?: runCatching {
                        repository.getMostRecentConversationId(assistant.id)?.toString()
                    }.getOrNull()?.let { latest[assistant.id.toString()] = it }
            }
            collectedHeaders to latest
        }
        headers = collectedHeaders
        latestConversationId = latest
    }
    LaunchedEffect(selecting) {
        windows = selecting?.let { id -> withContext(Dispatchers.IO) {
            repository.getConversationsOfAssistant(Uuid.parse(id)).first()
        } }.orEmpty()
    }
    fun update(config: AssistantProactiveConfig) {
        ProactiveTaskExecutor.cancel(context, config.assistantId)
        vm.updateProactiveSetting(update = { current -> current.copy(
            assistantConfigs = current.assistantConfigs.orEmpty().filterNot { it.assistantId == config.assistantId } + config,
        ) })
    }
    fun windowTitle(conversationId: String): String = when {
        conversationId.isBlank() -> "还没有聊天窗口"
        else -> headers[conversationId]?.title?.ifBlank { null } ?: conversationId.take(8)
    }

    // 启用一个助手 = 跟随它最新的聊天窗口（不弹"选窗口"对话框；固定是展开后的可选项）。
    fun enableAssistant(assistantId: String) {
        val existing = settings.proactiveMessageSetting.assistantConfigs.orEmpty()
            .singleOrNull { it.assistantId == assistantId }
        update((existing ?: AssistantProactiveConfig(assistantId, "")).copy(
            conversationId = if (existing?.pinConversation == true) existing.conversationId
                else latestConversationId[assistantId] ?: existing?.conversationId.orEmpty(),
            pinConversation = existing?.pinConversation == true,
            enabled = true,
        ))
    }

    if (selecting != null) {
        AlertDialog(
            onDismissRequest = { selecting = null },
            title = { Text("选择投递窗口") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (windows.isEmpty()) item { Text("请先为该助手创建聊天窗口") }
                    items(windows.size, key = { windows[it].id }) { index ->
                        val window = windows[index]
                        TextButton(onClick = {
                            val id = selecting!!
                            val old = settings.proactiveMessageSetting.assistantConfigs.orEmpty()
                                .singleOrNull { it.assistantId == id }
                            update((old ?: AssistantProactiveConfig(id, window.id.toString())).copy(
                                conversationId = window.id.toString(),
                                enabled = true,
                                pinConversation = true,
                            ))
                            selecting = null
                            expandedAssistantId = id
                        }) { Text(window.title.ifBlank { window.id.toString() }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selecting = null }) { Text("关闭") } },
        )
    }

    val enabledCount = settings.proactiveMessageSetting.assistantConfigs.orEmpty().count { it.enabled }
    // 行必须在组合体里读好状态一次性建好：状态变化时重建整个列表，而不是重启某个 lambda。
    val items = buildCardGroupItems {
        item(
            headlineContent = { Text("参与助手的独立投递窗口") },
            supportingContent = {
                Text("每个助手默认自动跟随自己最新的聊天窗口，也可以展开后固定到某个窗口；目前 $enabledCount 位参与。默认跟随全局间隔、每日上限、提示和活跃时段。")
            },
        )
        settings.assistants.forEach { assistant ->
            val assistantId = assistant.id.toString()
            val config = settings.proactiveMessageSetting.assistantConfigs.orEmpty()
                .singleOrNull { it.assistantId == assistantId }
            val expanded = expandedAssistantId == assistantId
            item(
                headlineContent = { Text(assistant.name.ifBlank { "助手" }) },
                supportingContent = {
                    Text(
                        when {
                            config == null -> "尚未参与；打开开关即可参与"
                            !config.enabled -> "已暂停参与"
                            config.pinConversation ->
                                "固定窗口：${windowTitle(config.conversationId)}" +
                                    if (expanded) "" else " · 点按展开另设"
                            else ->
                                "自动跟随最新聊天：${windowTitle(config.conversationId)}" +
                                    if (expanded) "" else " · 点按展开另设"
                        },
                    )
                },
                onClick = {
                    if (config?.enabled == true) {
                        expandedAssistantId = if (expanded) null else assistantId
                    } else {
                        enableAssistant(assistantId)
                    }
                },
                trailingContent = {
                    Switch(
                        checked = config?.enabled == true,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                enableAssistant(assistantId)
                                expandedAssistantId = assistantId
                            } else {
                                config?.let { update(it.copy(enabled = false)) }
                                expandedAssistantId = null
                            }
                        },
                    )
                },
            )
            if (config != null && config.enabled && expanded) {
                item(
                    headlineContent = { Text("另设该助手") },
                    supportingContent = {
                        Text(
                            if (config.customEnabled) "已启用此助手的独立冷却、每日上限、提示和活跃时段。"
                            else "关闭时使用全局设置；打开后才会显示此助手的单独设置。",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = config.customEnabled,
                            onCheckedChange = { custom ->
                                update(config.copy(customEnabled = custom))
                            },
                        )
                    },
                )
                if (config.customEnabled) {
                    item(
                        headlineContent = { Text("此助手单独设置") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                var cooldown by remember(config.cooldownMinutes) {
                                    mutableStateOf(config.cooldownMinutes.toString())
                                }
                                var daily by remember(config.dailyLimit) {
                                    mutableStateOf(config.dailyLimit.toString())
                                }
                                OutlinedTextField(
                                    cooldown,
                                    onValueChange = { value ->
                                        cooldown = value.filter(Char::isDigit).take(5)
                                        cooldown.toIntOrNull()?.takeIf { it in 1..10080 }?.let {
                                            update(config.copy(cooldownMinutes = it))
                                        }
                                    },
                                    label = { Text("冷却分钟（1–10080）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    daily,
                                    onValueChange = { value ->
                                        daily = value.filter(Char::isDigit).take(3)
                                        daily.toIntOrNull()?.takeIf { it in 1..100 }?.let {
                                            update(config.copy(dailyLimit = it))
                                        }
                                    },
                                    label = { Text("每日任务上限（1–100）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    config.promptOverride.orEmpty(),
                                    onValueChange = { value ->
                                        update(config.copy(promptOverride = value.take(4000).ifBlank { null }))
                                    },
                                    label = { Text("此助手额外主动消息提示") },
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("另设此助手的活跃时段（仍遵守全局安静时段）", style = MaterialTheme.typography.bodySmall)
                                RowSwitch(
                                    checked = config.activeHoursEnabled == true,
                                    onCheckedChange = { update(config.copy(activeHoursEnabled = it)) },
                                )
                                if (config.activeHoursEnabled == true) {
                                    OutlinedTextField(
                                        (config.activeStartHour ?: settings.proactiveMessageSetting.activeStartHour).toString(),
                                        onValueChange = { value ->
                                            value.toIntOrNull()?.takeIf { it in 0..23 }?.let {
                                                update(config.copy(activeStartHour = it))
                                            }
                                        },
                                        label = { Text("开始小时 0–23") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        (config.activeEndHour ?: settings.proactiveMessageSetting.activeEndHour).toString(),
                                        onValueChange = { value ->
                                            value.toIntOrNull()?.takeIf { it in 0..23 }?.let {
                                                update(config.copy(activeEndHour = it))
                                            }
                                        },
                                        label = { Text("结束小时 0–23（相同为全天）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        },
                    )
                }
                if (config.pinConversation) {
                    item(
                        headlineContent = { Text("投递窗口") },
                        supportingContent = { Text("固定：${windowTitle(config.conversationId)}") },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { selecting = assistantId }) { Text("更换") }
                                TextButton(onClick = {
                                    update(config.copy(
                                        pinConversation = false,
                                        conversationId = latestConversationId[assistantId] ?: config.conversationId,
                                    ))
                                }) { Text("改回自动") }
                            }
                        },
                    )
                } else {
                    item(
                        headlineContent = { Text("投递窗口") },
                        supportingContent = { Text("自动跟随该助手最新的聊天窗口") },
                        trailingContent = {
                            TextButton(onClick = { selecting = assistantId }) { Text("固定到某个窗口") }
                        },
                    )
                }
            }
        }
    }
    CardGroup(items = items, modifier = Modifier.padding(horizontal = 8.dp), title = { Text("参与助手") })
}

@Composable
private fun RowSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onCheckedChange)
}
