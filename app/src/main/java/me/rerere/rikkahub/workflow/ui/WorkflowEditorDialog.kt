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
    val initialInterval = remember(initial.id) { Regex("^@every\\s+(\\d+)([mhd])$").matchEntire((initial.trigger as? TriggerSpec.TimeCron)?.cron?.trim().orEmpty()) }
    var interval by remember(initial.id) { mutableStateOf(initialInterval?.groupValues?.get(1) ?: "30") }
    var intervalUnit by remember(initial.id) { mutableStateOf(IntervalUnit.entries.firstOrNull { it.suffix == initialInterval?.groupValues?.get(2) } ?: IntervalUnit.MINUTES) }
    var cron by remember(initial.id) { mutableStateOf((initial.trigger as? TriggerSpec.TimeCron)?.cron ?: "0 9 * * 1-5") }
    var battery by remember(initial.id) { mutableStateOf(when (val trigger = initial.trigger) { is TriggerSpec.BatteryBelow -> trigger.thresholdPercent; is TriggerSpec.BatteryAbove -> trigger.thresholdPercent; else -> 20 }.toString()) }
    var choice by remember(initial.id) { mutableStateOf(initial.trigger.editorChoice()) }
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
            if (initial.trigger is TriggerSpec.TimeCron && oneLineTriggerSummary(initial) == "定时配置无效，请编辑修复") {
                Text("原定时配置无效，已停止自动执行；请修正后保存。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(enabled = !saving, onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(choice?.title ?: "已保留原触发器（暂不支持编辑）") }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) { TriggerChoice.entries.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { choice = item; menuExpanded = false }) } }
            when (choice) {
                TriggerChoice.TIME -> OutlinedTextField(time, { time = it }, label = { Text("时间（HH:mm）") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                TriggerChoice.INTERVAL -> {
                    OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("每隔多久运行一次") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntervalUnit.entries.forEach { unit -> FilterChip(selected = intervalUnit == unit, onClick = { intervalUnit = unit }, enabled = !saving, label = { Text(unit.title) }) }
                    }
                }
                TriggerChoice.CRON -> OutlinedTextField(cron, { cron = it }, label = { Text("Cron（分 时 日 月 周）") }, supportingText = { Text("例如 0 9 * * 1-5：周一到周五 09:00") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                TriggerChoice.BATTERY_LOW, TriggerChoice.BATTERY_HIGH -> OutlinedTextField(battery, { battery = it.filter(Char::isDigit) }, label = { Text("电量百分比（1-100）") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                TriggerChoice.WIFI_ON, TriggerChoice.WIFI_OFF -> OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID（留空表示任意 WiFi）") }, supportingText = { Text("指定 WiFi 名称需要定位权限及系统定位开启。") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
            if (choice == TriggerChoice.INTERVAL) {
                Text("系统可能因省电策略延后执行；间隔从保存后开始，最低 15 分钟，不补跑错过的次数。", style = MaterialTheme.typography.bodySmall)
            } else if (choice in listOf(TriggerChoice.TIME, TriggerChoice.CRON)) {
                Text("系统可能因省电策略延后执行；错过的时间不会逐次补跑。", style = MaterialTheme.typography.bodySmall)
            } else if (choice == null && (initial.trigger is TriggerSpec.BluetoothDeviceConnected || initial.trigger is TriggerSpec.BluetoothDeviceDisconnected)) {
                Text("原蓝牙触发器已保留；此版本未提供蓝牙授权，建议改选其他触发器。", style = MaterialTheme.typography.bodySmall)
            } else if (choice != TriggerChoice.MANUAL && choice != TriggerChoice.BOOT) {
                Text("此类事件需应用进程仍在运行；后台被系统关闭后，重新打开应用才会继续监听。", style = MaterialTheme.typography.bodySmall)
            }
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
            val next = initial.copy(name = name.trim(), description = description.trim().ifBlank { null }, enabled = enabled, trigger = choice?.toTrigger(time, ssid, initial.trigger, interval, intervalUnit, cron, battery) ?: initial.trigger, actions = parsed, cooldownSeconds = cd, maxRunsPerDay = cap)
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
