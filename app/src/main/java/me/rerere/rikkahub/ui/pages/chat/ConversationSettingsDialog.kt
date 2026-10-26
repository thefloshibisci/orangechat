package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomBodies
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomHeaders

@Composable
internal fun ConversationSettingsDialog(
    conversation: Conversation,
    assistant: Assistant,
    onSave: (Assistant, Assistant) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Keep the opening snapshot so saving merges only fields actually edited in this dialog.
    val previous = remember(conversation.id) { assistant }
    var draft by remember(conversation.id) { mutableStateOf(ConversationRequestDraft.from(previous)) }
    var extras by remember(conversation.id) { mutableStateOf(previous) }
    var confirmReset by remember { mutableStateOf(false) }
    val valid = draft.isValid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话设置") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (conversation.config == null) "正在继承助手默认，修改后仅为此会话保存。"
                    else "已独立保存，仅影响当前会话。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "模型、思考、搜索、MCP、技能和工作区可在输入栏选择；角色、记忆与外观继续使用助手设置。",
                    style = MaterialTheme.typography.bodySmall,
                )
                RequestNumberField("温度，留空使用接口默认", draft.temperature, validOptionalFloat(draft.temperature, 0f..2f)) {
                    draft = draft.copy(temperature = it)
                }
                RequestNumberField("Top P，留空使用接口默认", draft.topP, validOptionalFloat(draft.topP, 0f..1f)) {
                    draft = draft.copy(topP = it)
                }
                RequestNumberField("最大输出 token，留空使用接口默认", draft.maxTokens, validOptionalPositiveInt(draft.maxTokens)) {
                    draft = draft.copy(maxTokens = it)
                }
                RequestNumberField("保留消息数，0 为全部", draft.contextMessages, validNonNegativeInt(draft.contextMessages)) {
                    draft = draft.copy(contextMessages = it)
                }
                RequestNumberField("最多保留上传图片数，0 为全部", draft.contextImages, validNonNegativeInt(draft.contextImages)) {
                    draft = draft.copy(contextImages = it)
                }
                RequestNumberField("最多保留语音数，0 为全部", draft.contextVoices, validNonNegativeInt(draft.contextVoices)) {
                    draft = draft.copy(contextVoices = it)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("流式输出", modifier = Modifier.weight(1f))
                    Switch(checked = extras.streamOutput, onCheckedChange = { extras = extras.copy(streamOutput = it) })
                }
                HorizontalDivider()
                CustomHeaders(headers = extras.customHeaders, onUpdate = { extras = extras.copy(customHeaders = it) })
                CustomBodies(customBodies = extras.customBodies, onUpdate = { extras = extras.copy(customBodies = it) })
                if (!valid) Text("请检查数字：温度 0–2，Top P 0–1，数量需为有效整数。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { confirmReset = true }) { Text("恢复助手默认") }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(previous, draft.applyTo(extras))
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复助手默认？") },
            text = { Text("当前会话将重新继承助手的模型、工具和请求设置。聊天记录会保留。") },
            confirmButton = {
                TextButton(onClick = { onReset(); onDismiss() }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun RequestNumberField(label: String, value: String, valid: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
        modifier = Modifier.fillMaxWidth(),
    )
}

internal data class ConversationRequestDraft(
    val temperature: String,
    val topP: String,
    val maxTokens: String,
    val contextMessages: String,
    val contextImages: String,
    val contextVoices: String,
) {
    val isValid: Boolean get() = validOptionalFloat(temperature, 0f..2f) && validOptionalFloat(topP, 0f..1f) &&
        validOptionalPositiveInt(maxTokens) && validNonNegativeInt(contextMessages) &&
        validNonNegativeInt(contextImages) && validNonNegativeInt(contextVoices)

    fun applyTo(assistant: Assistant): Assistant {
        require(isValid)
        return assistant.copy(
            temperature = temperature.toFloatOrNull(), topP = topP.toFloatOrNull(), maxTokens = maxTokens.toIntOrNull(),
            contextMessageSize = contextMessages.toInt(), maxContextImages = contextImages.toInt(), maxContextVoices = contextVoices.toInt(),
        )
    }

    companion object {
        fun from(assistant: Assistant) = ConversationRequestDraft(
            assistant.temperature?.toString().orEmpty(), assistant.topP?.toString().orEmpty(), assistant.maxTokens?.toString().orEmpty(),
            assistant.contextMessageSize.toString(), assistant.maxContextImages.toString(), assistant.maxContextVoices.toString(),
        )
    }
}

internal fun validOptionalFloat(text: String, range: ClosedFloatingPointRange<Float>): Boolean =
    text.isBlank() || text.toFloatOrNull()?.let { it.isFinite() && it in range } == true

internal fun validOptionalPositiveInt(text: String): Boolean = text.isBlank() || text.toIntOrNull()?.let { it > 0 } == true

internal fun validNonNegativeInt(text: String): Boolean = text.toIntOrNull()?.let { it >= 0 } == true
