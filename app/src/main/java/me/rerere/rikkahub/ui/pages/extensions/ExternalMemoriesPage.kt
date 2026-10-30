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
    val selected = assistants.firstOrNull { it.id.toString() == selectedId }
        ?: assistants.firstOrNull { it.id.toString() == preferredAssistantId }
        ?: assistants.firstOrNull()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    supportingContent = { Text("每位助手的记忆分开设置，点这里切换。") },
                )
            }
            key(selected.id) { memoryContent(selected) }
        }
        Text("旧的 Supabase 连接已经停用，也从手机里删掉了；云端原来的数据还在。",
            Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
