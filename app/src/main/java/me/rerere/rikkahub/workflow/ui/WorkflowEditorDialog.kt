package me.rerere.rikkahub.workflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowActionErrorPolicy
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Phone-friendly editor for the parts users need to tune most often. Trigger and conditions
 * stay intact; they can still be authored by AI while actions are editable without JSON.
 */
@Composable
internal fun WorkflowEditorDialog(
    definition: WorkflowDefinition,
    onDismiss: () -> Unit,
    onSave: (WorkflowDefinition) -> Unit,
) {
    var name by remember(definition.id) { mutableStateOf(definition.name) }
    var description by remember(definition.id) { mutableStateOf(definition.description.orEmpty()) }
    var cooldown by remember(definition.id) { mutableStateOf(definition.cooldownSeconds.toString()) }
    var dailyCap by remember(definition.id) { mutableStateOf(definition.maxRunsPerDay?.toString().orEmpty()) }
    val actions = remember(definition.id) { mutableStateListOf(*definition.actions.toTypedArray()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑工作流") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(500) }, label = { Text("描述") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cooldown, { cooldown = it.filter(Char::isDigit) }, label = { Text("冷却秒数") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(dailyCap, { dailyCap = it.filter(Char::isDigit) }, label = { Text("每日上限") }, modifier = Modifier.weight(1f))
                }
                Text("动作步骤")
                actions.forEachIndexed { index, action ->
                    EditableActionCard(
                        index = index,
                        action = action,
                        canMoveUp = index > 0,
                        canMoveDown = index < actions.lastIndex,
                        onChange = { actions[index] = it },
                        onMoveUp = { actions.add(index - 1, actions.removeAt(index)) },
                        onMoveDown = { actions.add(index + 1, actions.removeAt(index)) },
                        onDelete = { if (actions.size > 1) actions.removeAt(index) },
                    )
                    HorizontalDivider()
                }
                TextButton(onClick = {
                    actions += WorkflowAction(tool = "", args = JsonObject(emptyMap()))
                }) { Text("＋ 添加动作") }
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cooldownValue = cooldown.toIntOrNull() ?: 0
                val capValue = dailyCap.toIntOrNull()
                when {
                    name.isBlank() -> error = "名称不能为空"
                    actions.isEmpty() -> error = "至少需要一个动作"
                    actions.any { it.tool.isBlank() } -> error = "工具名称不能为空"
                    actions.any { it.retryCount !in 0..5 } -> error = "重试次数只能是 0 到 5"
                    else -> onSave(definition.copy(
                        name = name.trim(),
                        description = description.trim().ifEmpty { null },
                        cooldownSeconds = cooldownValue.coerceIn(0, 86_400),
                        maxRunsPerDay = capValue?.coerceIn(1, 1000),
                        actions = actions.toList(),
                    ))
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EditableActionCard(
    index: Int,
    action: WorkflowAction,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (WorkflowAction) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var argsText by remember(action) { mutableStateOf(action.args.toString()) }
    var argsError by remember(action) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Text("步骤 ${index + 1}")
        OutlinedTextField(
            value = action.tool,
            onValueChange = { onChange(action.copy(tool = it.trim())) },
            label = { Text("工具名称") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = argsText,
            onValueChange = {
                argsText = it
                val parsed = runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
                argsError = parsed == null
                if (parsed != null) onChange(action.copy(args = parsed))
            },
            label = { Text("参数 JSON（支持 {{变量名}}）") },
            isError = argsError,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = action.outputVariable.orEmpty(),
            onValueChange = { value ->
                onChange(action.copy(outputVariable = value.trim().ifEmpty { null }))
            },
            label = { Text("把输出保存为变量") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                val next = if (action.onError == WorkflowActionErrorPolicy.STOP) {
                    WorkflowActionErrorPolicy.CONTINUE
                } else WorkflowActionErrorPolicy.STOP
                onChange(action.copy(onError = next))
            }) { Text(if (action.onError == WorkflowActionErrorPolicy.STOP) "失败：停止" else "失败：继续") }
            TextButton(onClick = { onChange(action.copy(retryCount = (action.retryCount + 1) % 6)) }) {
                Text("重试 ${action.retryCount} 次")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = canMoveUp, onClick = onMoveUp) { Text("上移") }
            TextButton(enabled = canMoveDown, onClick = onMoveDown) { Text("下移") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}
