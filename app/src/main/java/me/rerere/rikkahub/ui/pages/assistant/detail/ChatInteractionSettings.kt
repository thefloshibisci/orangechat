package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.InputChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.interaction.CHAT_REACTION_KAOMOJI
import me.rerere.rikkahub.data.interaction.MAX_CHAT_REACTION_PRESET_LENGTH
import me.rerere.rikkahub.data.interaction.MAX_CUSTOM_CHAT_REACTION_PRESETS
import me.rerere.rikkahub.data.interaction.normalizeCustomChatReactionPreset
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
internal fun ChatInteractionSettings(
    assistant: Assistant,
    customReactionPresets: List<String>,
    onAddReactionPreset: (String) -> Unit,
    onRemoveReactionPreset: (String) -> Unit,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardGroup(title = { Text("聊天互动") }) {
            item(
                headlineContent = { Text("头像互动") },
                supportingContent = { Text("双击头像互动，AI 也能调用工具对你使用。双击会触发一次回复。") },
                trailingContent = {
                    Switch(
                        checked = assistant.enableAvatarInteractions,
                        onCheckedChange = { enabled ->
                            onEdit { current -> current.copy(enableAvatarInteractions = enabled) }
                        },
                    )
                },
            )
            item(
                headlineContent = { Text("消息表情回应") },
                supportingContent = { Text("颜文字贴在对应气泡下面，AI 下一次回复时能看见。") },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMessageReactions,
                        onCheckedChange = { enabled ->
                            onEdit { current -> current.copy(enableMessageReactions = enabled) }
                        },
                    )
                },
            )
        }

        key(assistant.id) {
            if (assistant.enableAvatarInteractions) {
                InteractionCard(
                    title = "你对 AI 的互动",
                    action = assistant.userInteractionAction,
                    actionDescription = "显示在你双击 AI 头像后的事件里。",
                    actionPlaceholder = "例如：揉了揉",
                    suffix = assistant.userInteractionSuffix,
                    suffixDescription = "会接在动作后面，可以补充对象和动作结果。",
                    suffixPlaceholder = "例如：的脑袋，并递来一杯牛奶",
                    onActionChange = { text -> onEdit { it.copy(userInteractionAction = text) } },
                    onSuffixChange = { text -> onEdit { it.copy(userInteractionSuffix = text) } },
                )
                InteractionCard(
                    title = "AI 对你的互动",
                    action = assistant.assistantInteractionAction,
                    actionDescription = "AI 主动调用头像互动工具时显示的动作。",
                    actionPlaceholder = "例如：拍了拍",
                    suffix = assistant.assistantInteractionSuffix,
                    suffixDescription = "会接在 AI 的动作后面，用来补充互动内容。",
                    suffixPlaceholder = "例如：的肩膀，并抱了抱你",
                    onActionChange = { text -> onEdit { it.copy(assistantInteractionAction = text) } },
                    onSuffixChange = { text -> onEdit { it.copy(assistantInteractionSuffix = text) } },
                )
            }
        }

        ReactionPresetSettings(
            presets = customReactionPresets,
            onAdd = onAddReactionPreset,
            onRemove = onRemoveReactionPreset,
        )
    }
}

@Composable
private fun InteractionCard(
    title: String,
    action: String,
    actionDescription: String,
    actionPlaceholder: String,
    suffix: String,
    suffixDescription: String,
    suffixPlaceholder: String,
    onActionChange: (String) -> Unit,
    onSuffixChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            FormItem(
                label = { Text("动作") },
                description = { Text(actionDescription) },
                modifier = Modifier.padding(8.dp),
            ) {
                InteractionTextField(
                    initialText = action,
                    label = actionPlaceholder,
                    maxLength = 20,
                    singleLine = true,
                    onChange = onActionChange,
                )
            }
            HorizontalDivider()
            FormItem(
                label = { Text("后缀") },
                description = { Text(suffixDescription) },
                modifier = Modifier.padding(8.dp),
            ) {
                InteractionTextField(
                    initialText = suffix,
                    label = suffixPlaceholder,
                    maxLength = 80,
                    onChange = onSuffixChange,
                )
            }
        }
    }
}

@Composable
internal fun ReactionPresetSettings(
    presets: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    fun addPreset() {
        val trimmed = draft.trim()
        val normalized = normalizeCustomChatReactionPreset(draft)
        error = when {
            trimmed.isEmpty() -> "先输入一个颜文字"
            trimmed.length > MAX_CHAT_REACTION_PRESET_LENGTH ->
                "颜文字最多 $MAX_CHAT_REACTION_PRESET_LENGTH 个字符"
            trimmed in CHAT_REACTION_KAOMOJI -> "这个已经在内置颜文字里了"
            normalized == null -> "这个颜文字暂时不能添加"
            normalized in presets -> "这个颜文字已经添加过了"
            presets.size >= MAX_CUSTOM_CHAT_REACTION_PRESETS ->
                "最多添加 $MAX_CUSTOM_CHAT_REACTION_PRESETS 个自定义颜文字"
            else -> null
        }
        if (error == null) {
            val accepted = checkNotNull(normalized)
            onAdd(accepted)
            draft = ""
            showAddDialog = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "颜文字预设",
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            FormItem(
                label = { Text("自定义颜文字") },
                description = {
                    Text("添加后会立即出现在贴颜文字和 AI 的预设列表里，最多 $MAX_CUSTOM_CHAT_REACTION_PRESETS 个。")
                },
                modifier = Modifier.padding(8.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    presets.forEach { preset ->
                        InputChip(
                            onClick = {},
                            selected = false,
                            label = { Text(preset) },
                            trailingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = "删除 $preset",
                                    modifier = Modifier.size(16.dp).clickable { onRemove(preset) },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        tonalElevation = 2.dp,
                        modifier = Modifier.clip(CircleShape).clickable {
                            draft = ""
                            error = null
                            showAddDialog = true
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = "添加预设",
                            modifier = Modifier.padding(6.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            HorizontalDivider()
            FormItem(
                label = { Text("内置颜文字") },
                description = {
                    Text(CHAT_REACTION_KAOMOJI.joinToString(separator = "　"))
                },
                modifier = Modifier.padding(8.dp),
            )
        }
    }
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加颜文字") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it.take(MAX_CHAT_REACTION_PRESET_LENGTH)
                        error = null
                    },
                    label = { Text("新的颜文字") },
                    placeholder = { Text("例如：꒰｡ › ·̮ ‹ ｡꒱") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "${presets.size}/$MAX_CUSTOM_CHAT_REACTION_PRESETS")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addPreset() }),
                )
            },
            confirmButton = { TextButton(onClick = ::addPreset) { Text("添加") } },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } },
        )
    }
}

/** The editor owns selection/composition; delayed persistence never replaces its active buffer. */
@Composable
internal fun InteractionTextField(
    initialText: String,
    label: String,
    maxLength: Int,
    singleLine: Boolean = false,
    onChange: (String) -> Unit,
) {
    val state = rememberTextFieldState(initialText)
    val latestOnChange by rememberUpdatedState(onChange)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect { latestOnChange(it) }
    }
    OutlinedTextField(
        state = state,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        inputTransformation = InputTransformation.maxLength(maxLength),
        lineLimits = if (singleLine) {
            TextFieldLineLimits.SingleLine
        } else {
            TextFieldLineLimits.MultiLine(maxHeightInLines = 3)
        },
    )
}
