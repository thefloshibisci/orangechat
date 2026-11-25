/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSubmitResult
import me.rerere.rikkahub.plugin.localtasks.LocalAssistantPolicy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import me.rerere.rikkahub.ui.theme.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.SafeFileResolver
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.repository.PluginRepository
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "PluginWebViewPage"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginWebViewPage(
    pluginId: String,
    htmlEntryPath: String,
    pluginManager: PluginManager,
    onNavigateBack: () -> Unit,
    conversationId: String = "",
) {
    val context = LocalContext.current
    val pluginLoader = koinInject<PluginLoader>()
    val pluginRepository = koinInject<PluginRepository>()
    val conversationRepository = koinInject<ConversationRepository>()
    val settingsStore = koinInject<SettingsStore>()
    val usageRecorder = koinInject<UsageRecorder>()
    val chatService = koinInject<ChatService>()
    val filesManager = koinInject<FilesManager>()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pluginWebViewClient by remember { mutableStateOf<PluginWebViewClient?>(null) }
    var pendingImageCallback by remember { mutableStateOf<String?>(null) }
    var pendingImagesCallback by remember { mutableStateOf<String?>(null) }
    var pendingFileCallback by remember { mutableStateOf<String?>(null) }
    var pendingBinaryFileCallback by remember { mutableStateOf<String?>(null) }
    var pendingImportAudioCallback by remember { mutableStateOf<String?>(null) }
    var webViewFileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // State for saveFileAs (SAF CreateDocument)
    var pendingSaveCallbackId by remember { mutableStateOf<String?>(null) }
    var pendingSaveBase64Data by remember { mutableStateOf<String?>(null) }

    val plugins by pluginManager.plugins.collectAsStateWithLifecycle()
    val pluginInfo = plugins.find { it.manifest.id == pluginId }

    val dataStore = remember(pluginId) {
        PluginDataStore(context, pluginId)
    }
    val scope = rememberCoroutineScope()
    val cssTokens = me.rerere.rikkahub.ui.theme.WebThemeTokens.current()
    val currentCssTokens = androidx.compose.runtime.rememberUpdatedState(cssTokens)
    val localDataBridge = remember(pluginInfo?.manifest) {
        pluginInfo?.let { LocalDataWebBridge(context, it, settingsStore, usageRecorder, filesManager) { currentCssTokens.value } }
    }
    DisposableEffect(localDataBridge) { onDispose { localDataBridge?.close() } }
    var taskSettings by remember { mutableStateOf<LocalTaskSettings?>(null) }
    var nativeReviews by remember { mutableStateOf<List<me.rerere.rikkahub.plugin.localtasks.LocalTaskPendingActionDto>?>(null) }
    var publishSettings by remember(pluginId) { mutableStateOf<LocalTaskSettings?>(null) }
    var taskReview by remember { mutableStateOf<Pair<String, (Boolean) -> Unit>?>(null) }
    var nativeReview by remember { mutableStateOf<me.rerere.rikkahub.plugin.localtasks.LocalTaskPendingActionDto?>(null) }
    var nativeReviewThumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    var nativeReviewBody by remember { mutableStateOf("") }
    val hostSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    if (taskSettings != null) {
        val draft = taskSettings!!
        fun saveSettings(): LocalTaskSettings? = try {
            val bridge = requireNotNull(localDataBridge)
            val mediaGranted = "localdata.media" in bridge.grantedCapabilities()
            bridge.tasks().updateSettings(draft.copy(
                allowImages = draft.allowImages && mediaGranted,
                allowGeneratedImages = draft.allowGeneratedImages && draft.allowImages && mediaGranted,
            )).also { taskSettings = null }
        } catch (error: Exception) {
            Toast.makeText(context, "设置保存失败", Toast.LENGTH_LONG).show()
            null
        }
        AlertDialog(onDismissRequest = { taskSettings = null },
            title = { Text("本机助手互动设置") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("仅显式开始才调用模型，使用各助手实际模型。离页可继续至本轮结束；进程退出不保证继续。")
                Row { Checkbox(draft.enabled, { taskSettings = draft.copy(enabled = it, globalStop = !it) }); Text("启用互动") }
                Row { Checkbox(draft.commentUserPosts, { taskSettings = draft.copy(commentUserPosts = it) }); Text("评论用户动态") }
                Row { Checkbox(draft.commentAssistantPosts, { taskSettings = draft.copy(commentAssistantPosts = it) }); Text("评论助手动态") }
                Row { Checkbox(draft.autoLike, { taskSettings = draft.copy(autoLike = it) }); Text("允许点赞") }
                Row { Checkbox(draft.requireReview, { taskSettings = draft.copy(requireReview = it) }); Text("发布动态或评论前审核") }
                val mediaGranted = "localdata.media" in (localDataBridge?.grantedCapabilities() ?: emptySet())
                Row { Checkbox(draft.allowImages && mediaGranted, {
                    taskSettings = draft.copy(allowImages = it && mediaGranted,
                        allowGeneratedImages = it && mediaGranted && draft.allowGeneratedImages)
                }); Text("允许助手使用用户选择的图片") }
                Row { Checkbox(draft.allowGeneratedImages && draft.allowImages && mediaGranted, {
                    taskSettings = draft.copy(allowGeneratedImages = it && mediaGranted && draft.allowImages)
                }, enabled = mediaGranted && draft.allowImages); Text("允许按需生成图片（使用模型调用预算）") }
                if (!mediaGranted) Text("先在宿主授权 localdata.media 才能启用图片。")
                OutlinedTextField(draft.maxRounds.toString(), { it.toIntOrNull()?.let { n -> taskSettings = draft.copy(maxRounds = n.coerceIn(0, 10)) } }, label = { Text("最多轮次（0 停止）") })
                OutlinedTextField(draft.maxRepliesPerAssistantPerPost.toString(), { it.toIntOrNull()?.let { n -> taskSettings = draft.copy(maxRepliesPerAssistantPerPost = n.coerceIn(0, 5)) } }, label = { Text("每助手每条最多回复") })
                OutlinedTextField(draft.maxModelCallsPerPost.toString(), { it.toIntOrNull()?.let { n -> taskSettings = draft.copy(maxModelCallsPerPost = n.coerceIn(1, 50)) } }, label = { Text("每条模型调用上限") })
                OutlinedTextField(draft.dailyModelCallBudget.toString(), { it.toIntOrNull()?.let { n -> taskSettings = draft.copy(dailyModelCallBudget = n.coerceIn(0, 10000)) } }, label = { Text("每日模型调用预算") })
                OutlinedTextField(draft.maxOutputChars.toString(), { it.toIntOrNull()?.let { n -> taskSettings = draft.copy(maxOutputChars = n.coerceIn(40, 2000)) } }, label = { Text("回复字数上限") })
                OutlinedTextField((draft.intervalMs / 1000).toString(), { it.toLongOrNull()?.let { n -> taskSettings = draft.copy(intervalMs = n.coerceIn(0, 3600) * 1000) } }, label = { Text("调用间隔（秒）") })
                Text("参与助手（默认仅草稿；互评可发表评论/点赞）")
                hostSettings.assistants.forEach { assistant ->
                    val id = assistant.id.toString()
                    Row { Checkbox(id in draft.participants, { checked ->
                        taskSettings = draft.copy(participants = if (checked) (draft.participants + id).distinct() else draft.participants - id)
                    }); Text(assistant.name.ifBlank { "助手" }, maxLines = 1) }
                    if (id in draft.participants) {
                        val policy = draft.effectivePolicy(id)
                        TextButton(onClick = {
                            val next = if (policy == LocalAssistantPolicy.DRAFT_ONLY) LocalAssistantPolicy.AUTO_PUBLISH else LocalAssistantPolicy.DRAFT_ONLY
                            taskSettings = draft.copy(assistantPolicies = draft.assistantPolicies + (id to next))
                        }) { Text(if (policy == LocalAssistantPolicy.AUTO_PUBLISH) "发布策略：允许自动发布" else "发布策略：仅草稿") }
                        val replyLimit = draft.replyLimitFor(id)
                        OutlinedTextField(replyLimit.toString(), { it.toIntOrNull()?.let { n -> taskSettings = draft.copy(
                            assistantReplyLimits = draft.assistantReplyLimits + (id to n.coerceIn(0, 5))) } }, label = { Text("此助手回复上限") })
                    }
                }
                Text("自动发布另需插件已获 localdata.publish 权限。生成图片须单独原生授权，并仍受基础媒体权限与模型预算限制。")
                TextButton(
                    enabled = draft.enabled && !draft.globalStop && hostSettings.assistants.any {
                        draft.effectivePolicy(it.id.toString()) != LocalAssistantPolicy.DISABLED
                    },
                    onClick = { publishSettings = saveSettings() },
                ) { Text("保存设置并让助手发帖") }
            } },
            confirmButton = { TextButton(onClick = { saveSettings() }) { Text("保存") } },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val bridge = localDataBridge
                        if (bridge != null) {
                            runCatching { nativeReviews = bridge.tasks().pendingReviews() }
                                .onFailure { Toast.makeText(context, "无法读取审核队列", Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("待审核") }
                    TextButton(onClick = { localDataBridge?.tasks()?.stopAll(); taskSettings = null }) {
                        Text("停止全部")
                    }
                }
            })
    }
    publishSettings?.let { settings ->
        val bridge = localDataBridge ?: return@let
        val assistants = hostSettings.assistants.filter {
            settings.effectivePolicy(it.id.toString()) != LocalAssistantPolicy.DISABLED
        }
        var selectedId by remember(assistants) { mutableStateOf(assistants.firstOrNull()?.id) }
        var selectedUris by remember { mutableStateOf(emptyList<Uri>()) }
        var submitting by remember { mutableStateOf(false) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
            if (uris.isNotEmpty()) selectedUris = uris
        }
        AlertDialog(
            onDismissRequest = { if (!submitting) publishSettings = null },
            title = { Text("助手发帖") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                assistants.forEach { assistant ->
                    Row {
                        androidx.compose.material3.RadioButton(selectedId == assistant.id, { selectedId = assistant.id }, enabled = !submitting)
                        Text(assistant.name.ifBlank { "助手" })
                    }
                }
                Text("助手将按已保存的发布策略生成动态。")
                if (settings.allowImages && "localdata.media" in bridge.grantedCapabilities()) {
                    TextButton(enabled = !submitting, onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(if (selectedUris.isEmpty()) "选择图片" else "已选 ${selectedUris.size} 张，重新选择") }
                    if (selectedUris.isNotEmpty()) {
                        TextButton(enabled = !submitting, onClick = { selectedUris = emptyList() }) { Text("清除图片") }
                    }
                }
            } },
            confirmButton = { TextButton(enabled = selectedId != null && !submitting, onClick = {
                val assistantId = selectedId ?: return@TextButton
                val uris = selectedUris
                submitting = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val mediaIds = if (uris.isEmpty()) emptyList() else bridge.importSelectedImages(uris).map { it.resourceId }
                            bridge.tasks().startPublish(assistantId.toString(), mediaIds)
                        }
                        when (result) {
                            is LocalTaskSubmitResult.Accepted -> {
                                publishSettings = null
                                Toast.makeText(context, "已加入助手发帖队列", Toast.LENGTH_LONG).show()
                            }
                            is LocalTaskSubmitResult.Rejected -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Toast.makeText(context, "无法开始发帖：${error.localizedMessage.orEmpty()}", Toast.LENGTH_LONG).show()
                    } finally {
                        submitting = false
                    }
                }
            }) { Text("开始") } },
            dismissButton = { TextButton(enabled = !submitting, onClick = { publishSettings = null }) { Text("取消") } },
        )
    }
    if (nativeReviews != null) {
        AlertDialog(onDismissRequest = { nativeReviews = null },
            title = { Text("待审核内容（${nativeReviews!!.size}）") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (nativeReviews!!.isEmpty()) Text("没有待审核内容")
                nativeReviews!!.forEach { review ->
                    TextButton(onClick = {
                        nativeReview = review
                        nativeReviewBody = review.body
                        nativeReviewThumbnail = null
                    }) {
                        Text("${review.assistantName} · ${review.kind}" +
                            (if (review.mediaSource == me.rerere.rikkahub.plugin.localtasks.LocalTaskMediaSource.GENERATED) " · 助手生成图" else "") +
                            "\n${review.body.take(180)}")
                    }
                }
            } }, confirmButton = { TextButton(onClick = { nativeReviews = null }) { Text("关闭") } })
    }
    if (nativeReview != null) {
        val item = nativeReview!!
        var resolving by remember(item.id) { mutableStateOf(false) }
        val generatedMedia = item.mediaSource == me.rerere.rikkahub.plugin.localtasks.LocalTaskMediaSource.GENERATED
        fun resolve(approve: Boolean) {
            val bridge = localDataBridge ?: return
            if (resolving) return
            resolving = true
            val editedBody = nativeReviewBody.takeIf { approve && it != item.body }
            scope.launch {
                try {
                    val (result, remaining) = withContext(Dispatchers.IO) {
                        val tasks = bridge.tasks()
                        tasks.resolveReview(item.id, approve, editedBody) to tasks.pendingReviews()
                    }
                    nativeReviews = remaining
                    if (remaining.none { it.id == item.id }) nativeReview = null
                    if ((approve && !result.applied) || result.state == "not_found") {
                        Toast.makeText(context, result.detail ?: "审核操作未完成", Toast.LENGTH_LONG).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, e.localizedMessage ?: "审核操作失败", Toast.LENGTH_LONG).show()
                } finally {
                    resolving = false
                }
            }
        }
        AlertDialog(onDismissRequest = { if (!resolving) nativeReview = null }, title = { Text("审核助手操作") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                LaunchedEffect(item.id, item.mediaIds.firstOrNull(), item.mediaSource) {
                    nativeReviewThumbnail = null
                    val resourceId = item.mediaIds.firstOrNull()
                    if (item.mediaSource == me.rerere.rikkahub.plugin.localtasks.LocalTaskMediaSource.GENERATED &&
                        resourceId != null && "localdata.media" in (localDataBridge?.grantedCapabilities() ?: emptySet())) {
                        val bitmap = withContext(Dispatchers.IO) {
                            val response = localDataBridge?.openMedia("https://localdata.invalid/media/$resourceId/thumb")
                            response?.data?.use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() }
                        }
                        nativeReviewThumbnail = bitmap
                    }
                }
                Text("助手：${item.assistantName}\n类型：${item.kind}\n动态：${item.postId.orEmpty()}\n点赞：${item.like}\n回复目标：${item.replyToId.orEmpty()}")
                if (item.mediaSource == me.rerere.rikkahub.plugin.localtasks.LocalTaskMediaSource.NATIVE_SELECTION) Text("用户选择的图片")
                if (generatedMedia) Text("助手生成图${if (item.mediaIds.isEmpty()) "（缺少媒体资源）" else ""}")
                nativeReviewThumbnail?.let { Image(it, contentDescription = "助手生成图预览", modifier = Modifier.size(160.dp), contentScale = ContentScale.Fit) }
                if (generatedMedia && nativeReviewThumbnail != null) {
                    Text("请确认预览内容及发布正文后再批准。")
                }
                if (generatedMedia && nativeReviewThumbnail == null) {
                    Text("生成图片预览不可用。为避免在未查看图片时批准，不提供批准操作；仍可拒绝。")
                }
                if (item.body.isNotBlank()) OutlinedTextField(nativeReviewBody, { nativeReviewBody = it }, enabled = !resolving, label = { Text("审核正文，可编辑") })
            } },
            confirmButton = {
                TextButton(enabled = !resolving && (!generatedMedia || nativeReviewThumbnail != null) &&
                    (item.body.isBlank() || nativeReviewBody.isNotBlank()), onClick = { resolve(true) }) { Text("批准并应用") }
            },
            dismissButton = { TextButton(enabled = !resolving, onClick = { resolve(false) }) { Text("拒绝") } })
    }
    if (taskReview != null) {
        AlertDialog(onDismissRequest = { taskReview?.second?.invoke(false); taskReview = null },
            title = { Text("确认处理待审核内容") },
            text = { Text(taskReview!!.first, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { taskReview?.second?.invoke(true); taskReview = null }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { taskReview?.second?.invoke(false); taskReview = null }) { Text("取消") } })
    }
    var toolConsent by remember { mutableStateOf<Pair<String, (Boolean) -> Unit>?>(null) }
    if (toolConsent != null) {
        AlertDialog(onDismissRequest = { toolConsent?.second?.invoke(false); toolConsent = null },
            title = { Text("允许调用其他插件工具？") },
            text = { Text(toolConsent!!.first + "\n工具可能使用其已配置的模型或网络服务。仅导入本次真实工具返回的图片。") },
            confirmButton = { TextButton(onClick = { toolConsent?.second?.invoke(true); toolConsent = null }) { Text("允许并记住") } },
            dismissButton = { TextButton(onClick = { toolConsent?.second?.invoke(false); toolConsent = null }) { Text("拒绝") } })
    }
    val localConsentRequests = remember(pluginId, htmlEntryPath) { mutableStateListOf<(Boolean) -> Unit>() }
    fun resolveLocalConsent(approved: Boolean) {
        if (approved) localDataBridge?.approve()
        val requests = localConsentRequests.toList()
        localConsentRequests.clear()
        requests.forEach { it(approved) }
    }
    var localPickerCallback by remember { mutableStateOf<((List<Uri>?) -> Unit)?>(null) }
    var localBackupCallback by remember { mutableStateOf<((Uri?) -> Unit)?>(null) }
    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        val callback = localPickerCallback
        localPickerCallback = null
        callback?.invoke(uris)
    }
    val localBackupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val callback = localBackupCallback; localBackupCallback = null; callback?.invoke(uri)
    }
    val localBackupRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = localBackupCallback; localBackupCallback = null; callback?.invoke(uri)
    }
    if (localConsentRequests.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { resolveLocalConsent(false) },
            title = { Text("允许 ${pluginInfo?.manifest?.name.orEmpty()} 访问本机插件数据？") },
            text = { Text("仅读取此插件的数据与本机助手目录；写入、评论、点赞及媒体导入会按下列权限开放。媒体仅在系统选择器中选择；备份通过系统文件选择器保存。授权保存在宿主中，插件无法自行更改。\n\n" +
                localDataBridge?.requestedCapabilities().orEmpty().sorted().joinToString(separator = "\n")) },
            confirmButton = { TextButton(onClick = { resolveLocalConsent(true) }) { Text("允许并记住") } },
            dismissButton = { TextButton(onClick = { resolveLocalConsent(false) }) { Text("拒绝") } },
        )
    }

    var overlayController by remember { mutableStateOf<PluginOverlayController?>(null) }

    // Launcher for overlay permission (SYSTEM_ALERT_WINDOW)
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Result is handled by checking Settings.canDrawOverlays when needed
    }

    // Launcher for POST_NOTIFICATIONS permission
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Result is handled; proceed regardless
    }

    // Timer end broadcast receiver
    val timerEndReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == PomodoroTimerService.ACTION_TIMER_END) {
                    webView?.post {
                        webView?.evaluateJavascript(
                            "if(typeof window.onTimerEnd === 'function') { window.onTimerEnd(); }",
                            null
                        )
                    }
                    overlayController?.notifyTimerEnded()
                }
            }
        }
    }

    // Music completion broadcast receiver
    val musicCompletedReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == MusicPlayerService.ACTION_MUSIC_COMPLETED) {
                    Log.i(TAG, "[DEBUG] musicCompletedReceiver received broadcast, webView is null: ${webView == null}")
                    webView?.post {
                        webView?.evaluateJavascript(
                            "if(typeof window.onMusicCompleted === 'function') { window.onMusicCompleted(); } else { console.error('[DEBUG] onMusicCompleted not a function'); }",
                            null
                        )
                    }
                }
            }
        }
    }

    // Register broadcast receivers
    DisposableEffect(Unit) {
        LocalBroadcastManager.getInstance(context).registerReceiver(
            timerEndReceiver,
            IntentFilter(PomodoroTimerService.ACTION_TIMER_END)
        )
        LocalBroadcastManager.getInstance(context).registerReceiver(
            musicCompletedReceiver,
            IntentFilter(MusicPlayerService.ACTION_MUSIC_COMPLETED)
        )
        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(timerEndReceiver)
            LocalBroadcastManager.getInstance(context).unregisterReceiver(musicCompletedReceiver)
        }
    }

    // 文件选择器 - 用于 Bridge.pickFile()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val bridgeCallback = pendingFileCallback
        if (bridgeCallback != null) {
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val content = inputStream?.bufferedReader()?.readText()
                    inputStream?.close()
                    val fileName = uri.lastPathSegment ?: "unknown.txt"
                    webView?.post {
                        webView?.evaluateJavascript(
                            pluginBridgeResultJavascript(bridgeCallback, JSONObject().put("success", true).put("fileName", fileName).put("content", content ?: "")), null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Unknown error"
                    webView?.post {
                        webView?.evaluateJavascript(
                            pluginBridgeResultJavascript(bridgeCallback, JSONObject().put("success", false).put("error", errMsg)), null
                        )
                    }
                }
            } else {
                webView?.post {
                    webView?.evaluateJavascript(
                        pluginBridgeResultJavascript(bridgeCallback, JSONObject().put("success", false).put("error", "User cancelled")), null
                    )
                }
            }
            pendingFileCallback = null
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val callbackId = pendingImageCallback
        if (callbackId != null && uri != null) {
            try {
                val base64 = uriToBase64(context, uri)
                webView?.evaluateJavascript(
                    pluginBridgeJsonJavascript(callbackId, base64),
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process picked image", e)
                webView?.evaluateJavascript(
                    pluginBridgeResultJavascript(callbackId, null),
                    null
                )
            }
        } else if (callbackId != null) {
            webView?.evaluateJavascript(
                pluginBridgeResultJavascript(callbackId, null),
                null
            )
        }
        pendingImageCallback = null
    }

    // Multi-image API returns an array; keep pickImage's existing scalar result compatible.
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        val callbackId = pendingImagesCallback
        pendingImagesCallback = null
        if (callbackId != null) {
            scope.launch {
                try {
                    val javascript = withContext(Dispatchers.IO) {
                        val images = JSONArray(uris.map { uri ->
                            context.contentResolver.openInputStream(uri)?.use {
                                Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
                            } ?: error("Cannot read selected image")
                        })
                        pluginBridgeResultJavascript(callbackId, images)
                    }
                    webView?.evaluateJavascript(javascript, null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to process picked images", error)
                    webView?.evaluateJavascript(pluginBridgeResultJavascript(callbackId, null), null)
                }
            }
        }
    }

    // Launcher for binary file picking (Bridge.pickBinaryFile)
    val binaryFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val callbackId = pendingBinaryFileCallback
        if (callbackId != null) {
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    val base64 = if (bytes != null) Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
                    val fileName = uri.lastPathSegment ?: "unknown"
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    webView?.post {
                        webView?.evaluateJavascript(
                            pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true).put("fileName", fileName).put("mimeType", mimeType).put("base64", base64)), null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Unknown error"
                    webView?.post {
                        webView?.evaluateJavascript(
                            pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false).put("error", errMsg)), null
                        )
                    }
                }
            } else {
                webView?.post {
                    webView?.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false).put("error", "User cancelled")), null
                    )
                }
            }
            pendingBinaryFileCallback = null
        }
    }

    // Launcher for importing audio files (Bridge.importAudioFile) - multi-select with duplicate detection
    val audioFileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<@JvmSuppressWildcards Uri>? ->
        val callbackId = pendingImportAudioCallback
        if (callbackId != null) {
            if (uris.isNullOrEmpty()) {
                // null or empty list -> user cancelled
                webView?.post {
                    webView?.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false).put("error", "User cancelled")), null
                    )
                }
            } else {
                val imported = JSONArray()
                val duplicates = JSONArray()
                val errors = JSONArray()
                val seenInBatch = mutableSetOf<String>()

                // Create music/ subdirectory once
                val musicDir = File(dataStore.getDataDir(), "music")
                if (!musicDir.exists()) {
                    musicDir.mkdirs()
                }

                for (uri in uris) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val bytes = inputStream?.readBytes()
                        inputStream?.close()

                        if (bytes == null) {
                            val errName = uri.lastPathSegment ?: "unknown"
                            errors.put(JSONObject().put("fileName", errName).put("error", "Failed to read file"))
                            continue
                        }

                        // Get original file name from URI (reuse existing path/colon logic)
                        var fileName = uri.lastPathSegment ?: "unknown.mp3"
                        val lastSlash = fileName.lastIndexOf('/')
                        val lastColon = fileName.lastIndexOf(':')
                        val cutIndex = maxOf(lastSlash, lastColon)
                        if (cutIndex >= 0) {
                            fileName = fileName.substring(cutIndex + 1)
                        }

                        // Duplicate detection: existing in music/ OR already seen in this batch
                        val targetFile = dataStore.resolveDataFile("music/$fileName")
                            ?: throw SecurityException("Invalid file name")
                        if (targetFile.exists() || seenInBatch.contains(fileName)) {
                            duplicates.put(fileName)
                        } else {
                            targetFile.writeBytes(bytes)
                            seenInBatch.add(fileName)

                            val filePath = targetFile.absolutePath
                            imported.put(JSONObject().put("filePath", filePath).put("fileName", fileName))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "importAudioFile: failed to process uri=$uri, err=${e.message}", e)
                        val errName = uri.lastPathSegment ?: "unknown"
                        errors.put(JSONObject().put("fileName", errName).put("error", e.message ?: "Unknown error"))
                    }
                }

                webView?.post {
                    webView?.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true).put("imported", imported).put("duplicates", duplicates).put("errors", errors)), null
                    )
                }
            }
            pendingImportAudioCallback = null
        }
    }

    // Launcher for saveFileAs (SAF CreateDocument - user picks save location)
    val saveFileAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val callbackId = pendingSaveCallbackId
        val base64Data = pendingSaveBase64Data
        if (callbackId != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    PluginImageSave.write(base64Data, uri == null) {
                        context.contentResolver.openOutputStream(requireNotNull(uri), "wt")
                    }
                }
                if (result.optBoolean("success")) result.put("fileName", uri?.lastPathSegment ?: "saved_file")
                webView?.evaluateJavascript(
                    pluginBridgeJsonJavascript(callbackId, result.toString()),
                    null,
                )
                pendingSaveCallbackId = null
                pendingSaveBase64Data = null
            }
        }
    }

    // Launcher for native HTML <input type="file"> file chooser
    val htmlFileChooserLauncher = rememberLauncherForActivityResult(
        contract = PluginHtmlFileChooser()
    ) { uris ->
        val callback = webViewFileChooserCallback
        webViewFileChooserCallback = null
        callback?.onReceiveValue(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = pluginInfo?.manifest?.name ?: "插件管理",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (pluginInfo?.manifest?.permissions?.contains("ai_chat") == true) {
                            Text(if (conversationId.isBlank()) "AI：助手默认模型" else "AI：当前会话模型",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    if (pluginInfo?.manifest?.permissions?.contains("localdata.read") == true) {
                        // One compact settings entry replaces the old 待审/互动/助手发帖/停止 row.
                        // Pending reviews and stop-all are inside the native settings dialog.
                        TextButton(onClick = {
                            val bridge = localDataBridge ?: return@TextButton
                            val open: () -> Unit = { runCatching { taskSettings = bridge.tasks().settings() }.onFailure {
                                Toast.makeText(context, "无法读取任务设置", Toast.LENGTH_LONG).show()
                            } }
                            if (bridge.needsConsent()) {
                                localConsentRequests.add { approved -> if (approved) open() }
                            } else open()
                        }) { Text("助手设置") }
                    }
                    if (pluginInfo?.manifest?.permissions?.contains("localdata.backup") == true) {
                        TextButton(onClick = {
                            val view = webView ?: return@TextButton
                            (view.webViewClient as? PluginWebViewClient)?.nativeBackup(view, false)
                        }) { Text("备份") }
                        TextButton(onClick = {
                            val view = webView ?: return@TextButton
                            (view.webViewClient as? PluginWebViewClient)?.nativeBackup(view, true)
                        }) { Text("恢复") }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "\u8FD4\u56DE")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (pluginInfo == null) {
                Text(
                    text = "\u63D2\u4EF6\u4E0D\u5B58\u5728",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val aiCall = PluginAiCall(
                            permissions = pluginInfo.manifest.permissions,
                            loadSettings = { settingsStore.settingsFlow.first() },
                            loadConversation = if (conversationId.isBlank()) null else {
                                { conversationRepository.getConversationById(Uuid.parse(conversationId)) }
                            },
                            generateText = { providerSetting, messages, params ->
                                val manager: ProviderManager = org.koin.java.KoinJavaComponent.get(ProviderManager::class.java)
                                usageRecorder.wrap(
                                    manager.getProviderByType(providerSetting), UsageTag(UsageSource.PLUGIN),
                                )
                                    .generateText(providerSetting, messages, params)
                                    .choices.firstOrNull()?.message?.parts
                                    ?.filterIsInstance<UIMessagePart.Text>()?.firstOrNull()?.text.orEmpty()
                            },
                            onError = { Log.e(TAG, "AI generation failed", it) },
                        )
                        WebView(ctx).apply {
                            settings.apply {
                                @SuppressLint("SetJavaScriptEnabled")
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                databaseEnabled = true
                            }

                            val client = PluginWebViewClient(
                                pluginInfo = pluginInfo,
                                androidContext = ctx.applicationContext,
                                dataStore = dataStore,
                                pluginLoader = pluginLoader,
                                pluginManager = pluginManager,
                                pluginRepository = pluginRepository,
                                conversationRepository = conversationRepository,
                                settingsStore = settingsStore,
                                aiCall = aiCall,
                                localData = requireNotNull(localDataBridge),
                                onTaskReview = { description, callback ->
                                    if (taskReview != null) callback(false) else taskReview = description to callback
                                },
                                onToolConsent = { description, callback ->
                                    if (toolConsent != null) callback(false) else toolConsent = description to callback
                                },
                                onLocalConsent = { request -> localConsentRequests.add(request) },
                                onLocalPickImages = { callback ->
                                    if (localPickerCallback != null) callback(null) else {
                                        localPickerCallback = callback
                                        try { localPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                        catch (_: Exception) { localPickerCallback = null; callback(null) }
                                    }
                                },
                                onLocalBackup = { restore, callback ->
                                    if (localBackupCallback != null) callback(null) else {
                                        localBackupCallback = callback
                                        try {
                                            if (restore) localBackupRestore.launch(arrayOf("application/zip", "application/octet-stream"))
                                            else localBackupExport.launch("orangechat-localdata-backup.zip")
                                        } catch (_: Exception) { localBackupCallback = null; callback(null) }
                                    }
                                },
                                chatService = chatService,
                                filesManager = filesManager,
                                scope = scope,
                                onPickImage = { callbackId ->
                                    pendingImageCallback = callbackId
                                    pickImageLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onPickImages = { callbackId ->
                                    if (pendingImagesCallback != null) {
                                        webView?.evaluateJavascript(pluginBridgeResultJavascript(callbackId, null), null)
                                    } else {
                                        pendingImagesCallback = callbackId
                                        try {
                                            pickImagesLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        } catch (error: Exception) {
                                            pendingImagesCallback = null
                                            Log.e(TAG, "Cannot open multi-image picker", error)
                                            webView?.evaluateJavascript(pluginBridgeResultJavascript(callbackId, null), null)
                                        }
                                    }
                                },
                                onPickFile = { callbackId ->
                                    pendingFileCallback = callbackId
                                    filePickerLauncher.launch(arrayOf("text/plain", "text/markdown", "application/octet-stream"))
                                },
                                onPickBinaryFile = { callbackId ->
                                    pendingBinaryFileCallback = callbackId
                                    binaryFilePickerLauncher.launch(arrayOf("*/*"))
                                },
                                onImportAudioFile = { callbackId ->
                                    pendingImportAudioCallback = callbackId
                                    audioFileImportLauncher.launch(arrayOf("audio/*"))
                                },
                                onSaveFileAs = { callbackId, fileName, base64Data ->
                                    if (pendingSaveCallbackId != null) {
                                        webView?.evaluateJavascript(
                                            pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false).put("error", "Another save is in progress")), null
                                        )
                                    } else {
                                        pendingSaveCallbackId = callbackId
                                        pendingSaveBase64Data = base64Data
                                        try {
                                            saveFileAsLauncher.launch(fileName)
                                        } catch (_: Exception) {
                                            pendingSaveCallbackId = null
                                            pendingSaveBase64Data = null
                                            webView?.evaluateJavascript(
                                                pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false).put("error", "Cannot open save picker")), null
                                            )
                                        }
                                    }
                                },
                                onClose = onNavigateBack,
                                onStartTimer = { wv, seconds ->
                                    // Check overlay permission
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        overlayPermissionLauncher.launch(intent)
                                        wv.post {
                                            wv.evaluateJavascript(
                                                pluginBridgeResultJavascript(null, JSONObject().put("success", false).put("error", "Overlay permission required. Please grant it in settings and try again.")), null
                                            )
                                        }
                                        return@PluginWebViewClient
                                    }
                                    // Check notification permission
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    PomodoroTimerService.start(context, seconds)
                                    wv.post {
                                        // Find the callbackId from the pending bridge call
                                        // We use a simplified approach - the callback is handled in handleBridgeCall
                                    }
                                },
                                onStopTimer = {
                                    PomodoroTimerService.stop(context)
                                },
                                onShowOverlay = { wv, html ->
                                    val controller = overlayController ?: PluginOverlayController(
                                        context = ctx,
                                        dataStore = dataStore,
                                        aiCall = aiCall,
                                        scope = scope,
                                    ).also { overlayController = it }
                                    controller.showOverlay(wv, html)
                                },
                                onHideOverlay = {
                                    overlayController?.hideOverlay()
                                }
                            )
                            pluginWebViewClient = client
                            webViewClient = client

                            // 处理 HTML <input type="file"> 文件选择
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    // Cancel any previous callback
                                    webViewFileChooserCallback?.onReceiveValue(null)
                                    webViewFileChooserCallback = filePathCallback

                                    try {
                                        htmlFileChooserLauncher.launch(PluginHtmlFileRequest(
                                            acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray(),
                                            mode = fileChooserParams?.mode,
                                        ))
                                    } catch (error: Exception) {
                                        webViewFileChooserCallback = null
                                        filePathCallback?.onReceiveValue(null)
                                        Log.e(TAG, "Cannot open HTML file picker", error)
                                    }
                                    return true
                                }
                            }

                            // 禁用原生长按选择菜单 - 通过CSS/JS控制，不再用原生拦截
                            // （原生setOnLongClickListener会阻止批注模式的文字选择）

                            val htmlFile = SafeFileResolver.resolveInside(pluginInfo.directory, htmlEntryPath)
                            if (htmlFile?.isFile == true) {
                                loadUrl("file://${htmlFile.absolutePath}")
                            } else {
                                val escapedPath = android.text.TextUtils.htmlEncode(htmlEntryPath)
                                loadData(
                                    "<html><body><h2>\u9875\u9762\u6587\u4EF6\u4E0D\u5B58\u5728</h2><p>$escapedPath</p></body></html>",
                                    "text/html",
                                    "UTF-8"
                                )
                            }

                            webView = this@apply
                        }
                    },
                    update = { wv ->
                        webView = wv
                    }
                )
            }
        }
    }

    DisposableEffect(pluginId, htmlEntryPath) {
        onDispose {
            pendingImageCallback = null
            pendingImagesCallback = null
            pendingFileCallback = null
            pendingBinaryFileCallback = null
            pendingImportAudioCallback = null
            pendingSaveCallbackId = null
            pendingSaveBase64Data = null
            localPickerCallback = null
            localBackupCallback = null
            localConsentRequests.clear()
            toolConsent?.second?.invoke(false)
            toolConsent = null
            taskReview?.second?.invoke(false)
            taskReview = null
            taskSettings = null
            webViewFileChooserCallback?.onReceiveValue(null)
            webViewFileChooserCallback = null
            // Mark bridge client destroyed before tearing down WebView
            pluginWebViewClient?.markDestroyed()
            pluginWebViewClient = null
            // Clean up main WebView
            webView?.destroy()
            // Clean up overlay WebView
            overlayController?.dispose()
            overlayController = null
            // Stop timer service
            PomodoroTimerService.stop(context)
        }
    }
}

internal fun hasPluginPermission(permissions: Collection<String>, permission: String): Boolean =
    permissions.any { it.trim() == permission }
