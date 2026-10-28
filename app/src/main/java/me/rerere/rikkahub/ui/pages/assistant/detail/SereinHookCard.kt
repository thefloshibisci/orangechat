package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.serein.*
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun SereinHookCard(assistant: Assistant) {
    val settings: SettingsStore = koinInject()
    val repository: SereinHookRepository = koinInject()
    val client: SereinHookClient = koinInject()
    val database: AppDatabase = koinInject()
    val context = LocalContext.current
    val state by settings.settingsFlow.collectAsStateWithLifecycle()
    val counts by remember(state.sereinHook.uploadDestination()) {
        database.sereinUploadDao().counts(state.sereinHook.uploadDestination())
    }.collectAsStateWithLifecycle(SereinUploadCounts())
    val uploadError by remember(state.sereinHook.uploadDestination()) {
        database.sereinUploadDao().latestError(state.sereinHook.uploadDestination())
    }.collectAsStateWithLifecycle(null)
    val recallStatus by remember(assistant.id, state.sereinHook.fingerprint()) {
        database.sereinHookDao().latestStatus(assistant.id.toString(), state.sereinHook.fingerprint())
    }.collectAsStateWithLifecycle(null)
    val observationPending by remember(state.sereinHook.uploadDestination()) {
        database.sereinObservationDao().pending(state.sereinHook.uploadDestination())
    }.collectAsStateWithLifecycle(0)
    val observationError by remember(state.sereinHook.uploadDestination()) {
        database.sereinObservationDao().latestError(state.sereinHook.uploadDestination())
    }.collectAsStateWithLifecycle(null)
    SereinHookControls(assistant, state.sereinHook, onSave = { next ->
        repository.clearLocalRecallState()
        settings.update { current ->
            check(current.sereinHook.assistantId.isBlank() || current.sereinHook.assistantId == assistant.id.toString())
            current.copy(sereinHook = next, assistants = current.assistants.map {
                if (next.enabled && it.id == assistant.id) it.copy(enableLocalSceneTools = false) else it
            })
        }
    }, counts = counts, recallStatus = recallStatus, uploadError = uploadError,
        observationPending = observationPending, observationError = observationError, onRetry = {
        withContext(Dispatchers.IO) { database.sereinUploadDao().retry(state.sereinHook.uploadDestination()) }
        SereinUploadScheduler.runNow(context, restart = true)
    }, onTest = { config ->
        val start = System.nanoTime()
        val result = withTimeoutOrNull(config.waitMs) {
            client.recall(config, "Serein connection test: synthetic memory check", "orangechat-connection-test", emptyList())
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        sereinConnectionLabel(result, elapsed)
    })
}

@Composable
internal fun SereinHookControls(
    assistant: Assistant, config: SereinHookConfig,
    onSave: suspend (SereinHookConfig) -> Unit,
    onTest: suspend (SereinHookConfig) -> String,
    counts: SereinUploadCounts = SereinUploadCounts(),
    recallStatus: SereinRecallStatus? = null,
    uploadError: String? = null,
    observationPending: Int = 0,
    observationError: String? = null,
    onRetry: suspend () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var enableRecallOnSave by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmingUpload by remember { mutableStateOf(false) }
    val owner = assistant.id.toString()
    val mine = config.assistantId == owner
    val available = mine || config.assistantId.isBlank()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Serein 自动召回", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(modifier = Modifier.testTag("serein-recall"), checked = mine && config.enabled, enabled = available && !busy, onCheckedChange = { enabled ->
                    if (enabled) { enableRecallOnSave = true; editing = true }
                    else scope.launch {
                        busy = true
                        try { onSave(config.copy(enabled = false, generation = Uuid.random().toString())) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "设置未保存，请重试。" }
                        finally { busy = false }
                    }
                })
            }
            Text(if (available) "回复前查找相关记忆，最多等待 ${config.waitMs / 1000} 秒；超时照常聊天。"
                else "已绑定另一位助手，请在上方切换到绑定的助手。", style = MaterialTheme.typography.bodySmall)
            Text("OB 保留按需查询；本地档案与云端上传分别控制。", style = MaterialTheme.typography.bodySmall)
            if (mine) {
                Text("最近召回：${sereinRecallLabel(recallStatus)}", style = MaterialTheme.typography.bodySmall)
                recallStatus?.let {
                    Text("检索时间：${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(it.createdAt))}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("新聊天的召回结果会后台同步到塞壬网页「召回观察 → Hook 注入」，包含触发原句（最多 2000 字）、候选信息和交付状态。", style = MaterialTheme.typography.bodySmall)
            if (mine) {
                Text("Hook 观察待同步 $observationPending 条", style = MaterialTheme.typography.bodySmall)
                observationError?.let { Text("观察同步：${sereinFailureLabel(it)}", style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上传新聊天", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(modifier = Modifier.testTag("serein-upload"), checked = mine && config.uploadEnabled,
                    enabled = available && mine && config.rootUrl() != null && config.gatewayKey.isNotBlank() && !busy,
                    onCheckedChange = { enabled ->
                        if (enabled) confirmingUpload = true
                        else scope.launch {
                            busy = true
                            try { onSave(config.copy(uploadEnabled = false)) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { error = "设置未保存，请重试。" }
                            finally { busy = false }
                        }
                    })
            }
            Text("开启后，新产生且已完成的聊天文字会在后台上传，断网后自动重试；不影响回复等待时间。请先配置连接。",
                style = MaterialTheme.typography.bodySmall)
            Text("不补传历史，不上传思考过程、工具结果和召回内容。已上传内容的编辑、删除暂不同步到云端。",
                style = MaterialTheme.typography.bodySmall)
            if (mine) {
                Text("待上传 ${counts.pending} · 已接收 ${counts.sent} · 未上传 ${counts.blocked}", style = MaterialTheme.typography.bodySmall)
                if (counts.retrying > 0) Text("${counts.retrying} 条等待重试，请检查网络、密钥及服务版本。", style = MaterialTheme.typography.bodySmall)
                uploadError?.let { Text("上传原因：${sereinFailureLabel(it)}", style = MaterialTheme.typography.bodySmall) }
                if (!config.uploadEnabled && counts.pending > 0) Text("上传已暂停，待上传内容将在重新开启后继续。", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = config.uploadEnabled && counts.pending > 0 && !busy, onClick = {
                    scope.launch {
                        busy = true
                        try { onRetry() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "重试未启动，请稍后再试。" }
                        finally { busy = false }
                    }
                }) { Text("重试待上传") }
            }
            Text("已接收表示原话已保存；云端整理为记忆还需在 Serein 开启自动整理并配置模型。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(enabled = available && !busy, onClick = { enableRecallOnSave = false; editing = true }) { Text("配置连接") }
        }
    }
    if (confirmingUpload) AlertDialog(
        onDismissRequest = { if (!busy) confirmingUpload = false },
        title = { Text("开启新聊天上传？") },
        text = { Text("会将当前助手从开启后新产生、已完成的用户和助手文字发送到已配置的 Serein 实例。历史聊天不补传，未发送完的队列会保留到下次继续。可以随时关闭。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    onSave(config.copy(uploadEnabled = true,
                        uploadStartedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).toString()))
                    confirmingUpload = false
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "设置未保存，请重试。"; confirmingUpload = false }
                finally { busy = false }
            }
        }) { Text("开启上传") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmingUpload = false }) { Text("取消") } },
    )
    if (editing) {
        var url by remember { mutableStateOf(config.baseUrl) }
        var key by remember { mutableStateOf(config.gatewayKey) }
        var wait by remember { mutableStateOf(config.waitMs) }
        var status by remember { mutableStateOf<String?>(null) }
        var working by remember { mutableStateOf(false) }
        var accepted by remember { mutableStateOf(false) }
        fun draft() = config.copy(enabled = config.enabled || enableRecallOnSave, assistantId = owner, baseUrl = url.trim(), gatewayKey = key.trim(),
            timeoutMs = wait, generation = Uuid.random().toString())
        val valid = draft().rootUrl() != null && key.isNotBlank()
        AlertDialog(onDismissRequest = { if (!working) editing = false }, title = { Text("连接 Serein") },
            text = { Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请使用只供此助手的记忆实例。当前接口会查找该实例的记忆，不能按助手隔离。")
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS 实例根地址") }, singleLine = true, enabled = !working)
                OutlinedTextField(key, { key = it }, label = { Text("Gateway Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !working)
                Text("Gateway Key 是部署时保存的 api-token 文件内容。Zeabur 部署查看 gateway 服务的 /secrets/api-token；只填密钥，不加 Bearer。", style = MaterialTheme.typography.bodySmall)
                Text("根地址填 gateway 的 HTTPS 域名，不加 /v1。网页登录密码和模型 API Key 不适用于这里。", style = MaterialTheme.typography.bodySmall)
                Text("最长等待：${wait / 1000} 秒")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (2L..5L).forEach { seconds -> FilterChip(wait == seconds * 1000,
                        { wait = seconds * 1000 }, label = { Text("${seconds}s") }, enabled = !working) }
                }
                Text("开启后，本轮用户文字会发给 Serein 检索；选中的记忆会发给聊天模型。启用时关闭此助手的本地记忆写入工具，避免两处重复记。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(accepted, { accepted = it }, enabled = !working)
                    Text("此实例用于当前助手，我了解发送范围。", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = valid && accepted && !working, onClick = {
                    scope.launch {
                        working = true
                        try { status = onTest(draft()) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { status = "连接未成功，请检查配置。" }
                        finally { working = false }
                    }
                }) { Text("测试连接") }
                status?.let { Text(it) }
            } }, confirmButton = { TextButton(enabled = valid && accepted && !working, onClick = {
                scope.launch {
                    working = true
                    try { onSave(draft()); editing = false }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status = "设置未保存，请重试。" }
                    finally { working = false }
                }
            }) { Text(if (enableRecallOnSave) "保存并开启" else "保存连接") } }, dismissButton = {
                TextButton(enabled = !working, onClick = { editing = false }) { Text("取消") }
            })
    }
}
