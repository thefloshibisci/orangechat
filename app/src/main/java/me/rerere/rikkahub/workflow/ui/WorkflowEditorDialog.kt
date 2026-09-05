package me.rerere.rikkahub.workflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.workflow.model.*
import org.koin.androidx.compose.koinViewModel

private enum class TriggerChoice(val title: String) {
    MANUAL("手动运行"), TIME("每天定时"), WIFI_ON("连接 WiFi"), WIFI_OFF("断开 WiFi"),
    BT_ON("连接蓝牙设备"), BT_OFF("断开蓝牙设备"), HEADPHONES_ON("插入耳机"), HEADPHONES_OFF("拔出耳机"),
    POWER_ON("接入电源"), POWER_OFF("断开电源"), SCREEN_ON("屏幕点亮"), SCREEN_OFF("屏幕熄灭"), BOOT("开机完成"),
}
private data class DraftAction(val tool: String, val args: String, val timeout: String)

@Composable
fun WorkflowEditorDialog(initial: WorkflowDefinition, onDismiss: () -> Unit, onSave: (WorkflowDefinition, (Result<Unit>) -> Unit) -> Unit, vm: WorkflowsViewModel = koinViewModel()) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var description by remember(initial.id) { mutableStateOf(initial.description.orEmpty()) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }
    var cooldown by remember(initial.id) { mutableStateOf(initial.cooldownSeconds.toString()) }
    var dailyCap by remember(initial.id) { mutableStateOf(initial.maxRunsPerDay?.toString().orEmpty()) }
    var time by remember(initial.id) { mutableStateOf((initial.trigger as? TriggerSpec.TimeCron)?.timeOfDay ?: "08:00") }
    var ssid by remember(initial.id) { mutableStateOf((initial.trigger as? TriggerSpec.WifiConnected)?.ssid ?: (initial.trigger as? TriggerSpec.WifiDisconnected)?.ssid.orEmpty()) }
    var choice by remember(initial.id) { mutableStateOf(initial.toChoice()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val actions = remember(initial.id) { mutableStateListOf<DraftAction>().apply { addAll(initial.actions.map { DraftAction(it.tool, it.args.toString(), it.timeoutSeconds.toString()) }) } }
    var catalog by remember(initial.id) { mutableStateOf<List<WorkflowsViewModel.ToolCatalogItem>>(emptyList()) }
    LaunchedEffect(initial.id, vm) { catalog = runCatching { vm.toolCatalog(initial.authoringAssistantId) }.getOrDefault(emptyList()) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (initial.actions.isEmpty()) "新建工作流" else "编辑工作流") }, text = {
        Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("描述（可选）") }, enabled = !saving, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("状态", Modifier.weight(1f).padding(top = 10.dp)); FilterChip(enabled = !saving, selected = enabled, onClick = { enabled = !enabled }, label = { Text(if (enabled) "已启用" else "已停用") }) }
            Text("触发器", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(enabled = !saving, onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(choice?.title ?: "已保留原触发器（暂不支持编辑）") }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) { TriggerChoice.entries.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { choice = item; menuExpanded = false }) } }
            when (choice) { TriggerChoice.TIME -> OutlinedTextField(time, { time = it }, label = { Text("时间（HH:mm）") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth()); TriggerChoice.WIFI_ON, TriggerChoice.WIFI_OFF -> OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID（留空表示任意 WiFi）") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth()); else -> Unit }
            Text("执行步骤（${actions.size}/${WorkflowConstants.MAX_ACTIONS}）", style = MaterialTheme.typography.titleSmall)
            if (catalog.isEmpty()) Text("工具目录暂时不可用；已有步骤仍可编辑，保存时会继续校验。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            actions.forEachIndexed { index, action -> ActionDraftRow(index, action, catalog, saving, { actions[index] = it }, { actions.removeAt(index) }, { delta -> val target = index + delta; if (target in actions.indices) { val x = actions.removeAt(index); actions.add(target, x) } }, { if (actions.size < WorkflowConstants.MAX_ACTIONS) actions.add(index + 1, action) }) }
            OutlinedButton(enabled = !saving && actions.size < WorkflowConstants.MAX_ACTIONS, onClick = { actions.add(DraftAction(catalog.firstOrNull()?.name.orEmpty(), "{}", "60")) }) { Text("添加步骤") }
            OutlinedTextField(cooldown, { cooldown = it.filter(Char::isDigit) }, label = { Text("冷却秒数（0 表示不限制）") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(dailyCap, { dailyCap = it.filter(Char::isDigit) }, label = { Text("每日上限（留空表示不限）") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        runCatching {
            require(name.isNotBlank()) { "名称不能为空" }; require(name.length <= WorkflowConstants.MAX_NAME_LENGTH) { "名称不能超过 ${WorkflowConstants.MAX_NAME_LENGTH} 个字符" }; require(description.length <= WorkflowConstants.MAX_DESCRIPTION_LENGTH) { "描述不能超过 ${WorkflowConstants.MAX_DESCRIPTION_LENGTH} 个字符" }; require(actions.isNotEmpty()) { "至少添加一个步骤" }
            val cd = cooldown.toIntOrNull() ?: error("冷却秒数无效"); require(cd in 0..WorkflowConstants.MAX_COOLDOWN_S) { "冷却秒数范围为 0..${WorkflowConstants.MAX_COOLDOWN_S}" }
            val cap = dailyCap.toIntOrNull(); require(dailyCap.isBlank() || cap != null) { "每日上限无效" }; require(cap == null || cap in WorkflowConstants.MAX_RUNS_PER_DAY_FLOOR..WorkflowConstants.MAX_RUNS_PER_DAY_CEIL) { "每日上限范围无效" }
            val parsed = actions.mapIndexed { i, a -> require(a.tool.isNotBlank()) { "第 ${i + 1} 步请选择工具" }; val args = runCatching { Json.parseToJsonElement(a.args) as? JsonObject }.getOrNull() ?: error("第 ${i + 1} 步参数必须是 JSON 对象"); val timeout = a.timeout.toIntOrNull() ?: error("第 ${i + 1} 步超时无效"); require(timeout in WorkflowConstants.MIN_ACTION_TIMEOUT_S..WorkflowConstants.MAX_ACTION_TIMEOUT_S) { "第 ${i + 1} 步超时范围为 1..600 秒" }; WorkflowAction(a.tool.trim(), args, timeout) }
            val next = initial.copy(name = name.trim(), description = description.trim().ifBlank { null }, enabled = enabled, trigger = choice?.toTrigger(time, ssid, initial.trigger) ?: initial.trigger, actions = parsed, cooldownSeconds = cd, maxRunsPerDay = cap)
            // Keep legacy actions editable even when a disabled MCP/plugin is absent from the
            // current catalog; newly selected tools still come from the catalog dropdown.
            val known = (catalog.map { it.name } + initial.actions.map { it.tool }).toSet()
            val result = WorkflowJson.parse(WorkflowJson.encode(next), known); require(result is WorkflowJson.ParseResult.Ok) { (result as WorkflowJson.ParseResult.Err).detail }; next
        }.onSuccess { definition ->
            saving = true; validationError = null
            onSave(definition) { result ->
                result.onSuccess { onDismiss() }.onFailure { saving = false; validationError = it.message ?: "保存失败" }
            }
        }.onFailure { validationError = it.message ?: "保存失败" }
    }) { Text(if (saving) "保存中…" else "保存") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

@Composable private fun ActionDraftRow(index: Int, action: DraftAction, catalog: List<WorkflowsViewModel.ToolCatalogItem>, disabled: Boolean, onChange: (DraftAction) -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit, onCopy: () -> Unit) {
    var expanded by remember(action.tool) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row { Text("第 ${index + 1} 步", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)); TextButton(enabled = !disabled && index > 0, onClick = { onMove(-1) }) { Text("上移") }; TextButton(enabled = !disabled, onClick = { onMove(1) }) { Text("下移") }; TextButton(enabled = !disabled, onClick = onCopy) { Text("复制") }; TextButton(enabled = !disabled, onClick = onDelete) { Text("删除") } }
        if (catalog.isNotEmpty()) { OutlinedButton(enabled = !disabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(catalog.firstOrNull { it.name == action.tool }?.let { toolDisplayName(it.name, it.description) } ?: "工具：${action.tool.ifBlank { "请选择" }}") }; DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { catalog.forEach { tool -> DropdownMenuItem(text = { Text(toolDisplayName(tool.name, tool.description)) }, onClick = { onChange(action.copy(tool = tool.name)); expanded = false }) } } } else OutlinedTextField(action.tool, { onChange(action.copy(tool = it)) }, label = { Text("工具名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(action.args, { onChange(action.copy(args = it)) }, label = { Text("参数 JSON") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        catalog.firstOrNull { it.name == action.tool }?.schema?.let { schema ->
            schema.forEach { (key, spec) ->
                val specObject = spec as? JsonObject ?: return@forEach
                val type = specObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val fieldTitle = specObject["title"]?.jsonPrimitive?.contentOrNull ?: key
                if (type !in setOf("string", "integer", "number", "boolean")) return@forEach
                val current = runCatching { Json.parseToJsonElement(action.args).jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")
                OutlinedTextField(current, { value ->
                    val nextArgs = runCatching { Json.parseToJsonElement(action.args).jsonObject.toMutableMap() }.getOrDefault(mutableMapOf())
                    if (type == "boolean") nextArgs[key] = JsonPrimitive(value.toBoolean()) else if (value.isBlank()) nextArgs.remove(key) else nextArgs[key] = when (type) { "integer" -> JsonPrimitive(value.toIntOrNull() ?: 0); "number" -> JsonPrimitive(value.toDoubleOrNull() ?: 0.0); else -> JsonPrimitive(value) }
                    onChange(action.copy(args = JsonObject(nextArgs).toString()))
                }, label = { Text(fieldTitle) }, supportingText = { specObject["description"]?.jsonPrimitive?.contentOrNull?.let { Text(it) } }, singleLine = true, enabled = !disabled, modifier = Modifier.fillMaxWidth())
            }
        }
        OutlinedTextField(action.timeout, { onChange(action.copy(timeout = it.filter(Char::isDigit))) }, label = { Text("超时（秒，1-600）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}
fun toolDisplayName(name: String, description: String): String {
    val summary = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(56)
    return if (summary.isNullOrBlank()) name else "$name · $summary"
}
private fun WorkflowDefinition.toChoice(): TriggerChoice? = when (trigger) { is TriggerSpec.TimeCron -> if (trigger.timeOfDay != null) TriggerChoice.TIME else null; is TriggerSpec.WifiConnected -> TriggerChoice.WIFI_ON; is TriggerSpec.WifiDisconnected -> TriggerChoice.WIFI_OFF; is TriggerSpec.BluetoothDeviceConnected -> TriggerChoice.BT_ON; is TriggerSpec.BluetoothDeviceDisconnected -> TriggerChoice.BT_OFF; TriggerSpec.HeadphonesPlugged -> TriggerChoice.HEADPHONES_ON; TriggerSpec.HeadphonesUnplugged -> TriggerChoice.HEADPHONES_OFF; TriggerSpec.PowerConnected -> TriggerChoice.POWER_ON; TriggerSpec.PowerDisconnected -> TriggerChoice.POWER_OFF; TriggerSpec.ScreenOn -> TriggerChoice.SCREEN_ON; TriggerSpec.ScreenOff -> TriggerChoice.SCREEN_OFF; TriggerSpec.BootCompleted -> TriggerChoice.BOOT; TriggerSpec.Manual -> TriggerChoice.MANUAL; else -> null }
private fun TriggerChoice.toTrigger(time: String, ssid: String, original: TriggerSpec): TriggerSpec = when (this) {
    TriggerChoice.TIME -> (original as? TriggerSpec.TimeCron)?.takeIf { it.cron == null }?.copy(timeOfDay = time) ?: TriggerSpec.TimeCron(timeOfDay = time)
    TriggerChoice.WIFI_ON -> TriggerSpec.WifiConnected(ssid.ifBlank { null }); TriggerChoice.WIFI_OFF -> TriggerSpec.WifiDisconnected(ssid.ifBlank { null }); TriggerChoice.BT_ON -> TriggerSpec.BluetoothDeviceConnected(); TriggerChoice.BT_OFF -> TriggerSpec.BluetoothDeviceDisconnected(); TriggerChoice.HEADPHONES_ON -> TriggerSpec.HeadphonesPlugged; TriggerChoice.HEADPHONES_OFF -> TriggerSpec.HeadphonesUnplugged; TriggerChoice.POWER_ON -> TriggerSpec.PowerConnected; TriggerChoice.POWER_OFF -> TriggerSpec.PowerDisconnected; TriggerChoice.SCREEN_ON -> TriggerSpec.ScreenOn; TriggerChoice.SCREEN_OFF -> TriggerSpec.ScreenOff; TriggerChoice.BOOT -> TriggerSpec.BootCompleted; TriggerChoice.MANUAL -> TriggerSpec.Manual
}
