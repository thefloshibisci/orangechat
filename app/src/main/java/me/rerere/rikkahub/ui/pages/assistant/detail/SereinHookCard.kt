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
import me.rerere.rikkahub.ui.components.ui.CardGroup
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardGroup(title = { Text("Serein 云端记忆") }) {
            item(
                headlineContent = { Text("Serein 自动召回") },
                supportingContent = { MemoryNotes(buildList {
                    add(if (available) "助手回复前，先去 Serein 里找和你这句话有关的记忆，找到就一起带上。最多等 ${config.waitMs / 1000} 秒，找不到就照常回复。"
                        else "这项已经给另一位助手用了，请在上面换到那位助手。")
                    if (mine) {
                        add("最近召回：${sereinRecallLabel(recallStatus)}")
                        recallStatus?.let {
                            add("检索时间：${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(it.createdAt))}")
                        }
                    }
                    add("每次找到了什么，会同步到 Serein 网页的「召回观察」里，方便你回头看。")
                    if (mine && observationPending > 0) add("还有 $observationPending 条没同步")
                    if (mine) observationError?.let { add("同步出错：${sereinFailureLabel(it)}") }
                }) },
                trailingContent = {
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
                },
            )
            item(
                headlineContent = { Text("上传新聊天") },
                supportingContent = { MemoryNotes(buildList {
                    add("把以后的聊天文字传到 Serein，让它慢慢整理成记忆。在后台传，不耽误回复；断网了会自己补传。")
                    add("只传打开之后的新消息。思考过程、工具结果不传；已经传上去的消息，在这边改了或删了，云端不会跟着变。")
                    add("要在 Serein 里打开「自动整理」并配好模型，传上去的聊天才会变成记忆。")
                    if (mine) {
                        add("待上传 ${counts.pending} · 已接收 ${counts.sent} · 未上传 ${counts.blocked}")
                        if (counts.retrying > 0) add("${counts.retrying} 条在等重试，看看网络、密钥和服务版本。")
                        uploadError?.let { add("上传原因：${sereinFailureLabel(it)}") }
                        if (!config.uploadEnabled && counts.pending > 0) add("上传暂停了，重新打开后接着传。")
                    }
                }) },
                trailingContent = {
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
                },
            )
            if (mine && counts.pending > 0) item(
                enabled = config.uploadEnabled && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try { onRetry() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "重试未启动，请稍后再试。" }
                        finally { busy = false }
                    }
                },
                headlineContent = { Text("重试待上传") },
            )
            item(
                enabled = available && !busy,
                onClick = { enableRecallOnSave = false; editing = true },
                headlineContent = { Text("配置连接") },
                supportingContent = { Text("填 Serein 的地址和密钥。上面两项都要先连上才能用。") },
            )
        }
        error?.let { Text(it, Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.error) }
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

/** Explanations under a memory setting, one short paragraph each. */
@Composable
internal fun MemoryNotes(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text(it) }
    }
}
