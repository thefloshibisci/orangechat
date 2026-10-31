package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.withAssistantEdit
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.pages.assistant.detail.LocalOriginalsCard
import me.rerere.rikkahub.ui.pages.assistant.detail.LocalScenesCard
import me.rerere.rikkahub.ui.pages.assistant.detail.SereinHookCard
import me.rerere.rikkahub.ui.pages.setting.settingsScaffoldContainerColor
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.LargeFlexibleTopAppBar
import org.koin.compose.koinInject

// Retain the existing route so saved navigation and older links reach the new memory page.
@Composable
fun ExternalMemoriesPage() {
    val store: SettingsStore = koinInject()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("进阶记忆") }, navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior, colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = settingsScaffoldContainerColor(CustomColors.topBarColors.containerColor),
    ) { padding ->
        AdvancedMemoryContent(
            assistants = settings.assistants,
            preferredAssistantId = settings.sereinHook.assistantId.ifBlank { settings.assistantId.toString() },
            modifier = Modifier.padding(padding),
        ) { assistant ->
            val update: (Assistant) -> Unit = { desired ->
                scope.launch { store.update { current -> current.withAssistantEdit(assistant, desired) } }
            }
            SereinHookCard(assistant)
            MemorySectionTitle("本地档案")
            LocalOriginalsCard(assistant, update)
            LocalScenesCard(assistant, update)
        }
    }
}

@Composable
internal fun AdvancedMemoryContent(
    assistants: List<Assistant>,
    preferredAssistantId: String,
    modifier: Modifier = Modifier,
    memoryContent: @Composable (Assistant) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val selected = assistants.firstOrNull { it.id.toString() == selectedId }
        ?: assistants.firstOrNull { it.id.toString() == preferredAssistantId }
        ?: assistants.firstOrNull()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (selected == null) {
            Text("请先创建一位助手，再配置记忆。", Modifier.padding(horizontal = 4.dp))
        } else {
            CardGroup {
                item(
                    onClick = { choosing = true },
                    headlineContent = {
                        Box {
                            Text("当前助手：${selected.name.ifBlank { "未命名助手" }}")
                            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                                assistants.forEach { assistant ->
                                    DropdownMenuItem(
                                        text = { Text(assistant.name.ifBlank { "未命名助手" }) },
                                        onClick = { selectedId = assistant.id.toString(); choosing = false },
                                    )
                                }
                            }
                        }
                    },
                    supportingContent = { Text("切换要配置的助手") },
                )
            }
            key(selected.id) { memoryContent(selected) }
        }
        CardGroup {
            item(onClick = { showHelp = true }, headlineContent = { Text("使用说明") },
                supportingContent = { Text("记忆用途、数据范围与同步规则") })
        }
    }
    if (showHelp) AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text("记忆使用说明") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Serein 云端记忆", style = MaterialTheme.typography.titleSmall)
                Text("回复前检索相关记忆，超时照常回复。当前用户文字会发给 Serein，选中的记忆会发给聊天模型；召回记录会同步到网页的「召回观察」。")
                Text("上传仅包含开启后已完成的新聊天文字，不含思考过程、工具结果和召回内容。历史不补传，编辑和删除不同步；断网自动重试。已接收不等于已整理，还需在 Serein 开启自动整理并配置模型。")
                Text("本地档案", style = MaterialTheme.typography.titleSmall)
                Text("原话与修改历史仅保存在手机中，不参与回复。关闭记录后，已有档案仍保留；可手动导入旧聊天。")
                Text("本地记忆保存整理后的要点，查到的内容会发给聊天模型。Serein 自动召回开启时，本地记忆工具暂停，手动管理仍可用。")
                Text("旧 Supabase 连接已停用，云端原有数据保留。")
            }
        },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
    )
}

/** Section heading styled like CardGroup titles, for sections built from several cards. */
@Composable
private fun MemorySectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 4.dp, top = 8.dp),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
    )
}
