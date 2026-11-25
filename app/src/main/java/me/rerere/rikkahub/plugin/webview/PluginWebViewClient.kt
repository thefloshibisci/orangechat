/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.localtasks.LocalTaskBridgeRequestParser
import me.rerere.rikkahub.plugin.localtasks.LocalTaskMediaSource
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.effectivePluginConfig
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.service.ChatService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

private const val CLIENT_TAG = "PluginWebViewPage"

internal class PluginWebViewClient(
    private val pluginInfo: PluginInfo,
    androidContext: Context,
    private val dataStore: PluginDataStore,
    pluginLoader: PluginLoader,
    pluginManager: PluginManager,
    private val pluginRepository: PluginRepository,
    conversationRepository: ConversationRepository,
    settingsStore: SettingsStore,
    aiCall: PluginAiCall,
    private val localData: LocalDataWebBridge,
    private val onTaskReview: (String, (Boolean) -> Unit) -> Unit,
    onToolConsent: (String, (Boolean) -> Unit) -> Unit,
    private val onLocalConsent: ((Boolean) -> Unit) -> Unit,
    private val onLocalPickImages: ((List<Uri>?) -> Unit) -> Unit,
    private val onLocalBackup: (Boolean, (Uri?) -> Unit) -> Unit,
    chatService: ChatService,
    filesManager: FilesManager,
    private val scope: CoroutineScope,
    private val onPickImage: (callbackId: String) -> Unit,
    private val onPickImages: (callbackId: String) -> Unit,
    private val onPickFile: (callbackId: String) -> Unit,
    private val onPickBinaryFile: (callbackId: String) -> Unit,
    private val onImportAudioFile: (callbackId: String) -> Unit,
    private val onSaveFileAs: (callbackId: String, fileName: String, base64Data: String) -> Unit,
    private val onClose: () -> Unit,
    private val onStartTimer: (webView: WebView, seconds: Int) -> Unit,
    private val onStopTimer: () -> Unit,
    private val onShowOverlay: (webView: WebView, html: String) -> Unit,
    private val onHideOverlay: () -> Unit,
) : WebViewClient() {
    private val json = Json { ignoreUnknownKeys = true }
    private val chatSync = PluginBridgeChatSync(
        conversationRepository = conversationRepository,
        settingsStore = settingsStore,
        chatService = chatService,
        filesManager = filesManager,
    )
    private val toolExecutor = PluginBridgeToolExecutor(
        pluginInfo = pluginInfo,
        androidContext = androidContext,
        pluginLoader = pluginLoader,
        pluginManager = pluginManager,
        aiCall = aiCall,
        localData = localData,
        onToolConsent = onToolConsent,
    )
    @Volatile private var destroyed = false
    @Volatile private var trustedPage = false
    @Volatile private var pageEpoch = 0L
    private var pageToken = UUID.randomUUID().toString()

    fun markDestroyed() {
        destroyed = true
        chatSync.clear()
    }

    private fun postJavascript(webView: WebView, script: String) {
        if (destroyed) return
        webView.post {
            if (!destroyed) {
                runCatching { webView.evaluateJavascript(script, null) }
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        trustedPage = false
        pageEpoch++
        pageToken = UUID.randomUUID().toString()
        super.onPageStarted(view, url, favicon)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (isLocalDataMediaRequest(url.toString())) {
            val referer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
            val trustedReferer = referer == null || isTrustedLocalPluginPage(referer, pluginInfo.directory)
            return if (trustedPage && trustedReferer && request.method == "GET") {
                localData.openMedia(url.toString())
            } else deniedMedia()
        }
        // Prevent another plugin's local file from being embedded under this page's media authority.
        if (url.scheme.equals("file", ignoreCase = true) && !isTrustedLocalPluginPage(url.toString(), pluginInfo.directory)) {
            return deniedMedia()
        }
        return super.shouldInterceptRequest(view, request)
    }

    fun nativeBackup(webView: WebView, restore: Boolean) {
        if (!isTrustedLocalPluginPage(webView.url, pluginInfo.directory)) return
        handleLocalData(webView, if (restore) "localDataRestoreBackup" else "localDataExportBackup", emptyMap())
    }

    private fun handleLocalData(webView: WebView, method: String, params: Map<String, String>) {
        val epoch = pageEpoch
        fun active() = !destroyed && epoch == pageEpoch && isTrustedLocalPluginPage(webView.url, pluginInfo.directory)
        fun reply(result: String) {
            if (destroyed) return
            webView.post {
                if (active()) {
                    if (params["callbackId"] == null) {
                        val success = runCatching { JSONObject(result).optBoolean("ok") }.getOrDefault(false)
                        Toast.makeText(
                            webView.context,
                            if (success) "操作完成" else "操作未完成，请检查权限或备份文件",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else runCatching {
                        webView.evaluateJavascript(pluginBridgeJsonJavascript(params["callbackId"], result), null)
                    }
                }
            }
        }
        fun fail(code: String, message: String) = reply(localDataFailure(code, message).toString())
        val action: () -> Unit = action@{
            if (!active()) return@action
            when (method) {
                "localData", "localDataHostInfo" -> scope.launch(Dispatchers.IO) {
                    try {
                        reply(
                            if (method == "localDataHostInfo") localData.hostInfo().toString()
                            else localData.call(params["requestJson"].orEmpty()),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        fail("unavailable", "Local data request failed")
                    }
                }

                "localDataTask" -> scope.launch {
                    try {
                        val raw = params["requestJson"].orEmpty()
                        val request = LocalTaskBridgeRequestParser.parse(raw)
                        if (request.method == LocalTaskBridgeRequestParser.METHOD_REVIEW_RESOLVE) {
                            val root = JSONObject(raw).getJSONObject("params")
                            val review = localData.tasks().pendingReviews().find { it.id == root.optString("reviewId") }
                            if (review == null) {
                                fail("not_found", "Review does not exist")
                                return@launch
                            }
                            val requestedApproval = root.optBoolean("approve")
                            if (requestedApproval && review.mediaSource == LocalTaskMediaSource.GENERATED) {
                                fail("capability_denied", "生成图片审核请使用原生待审列表预览后批准")
                                return@launch
                            }
                            val body = if (root.has("body")) root.getString("body") else review.body
                            val approved = suspendCancellableCoroutine<Boolean> { continuation ->
                                onTaskReview(
                                    "${review.assistantName} · ${review.kind}\n\n$body\n\n" +
                                        if (requestedApproval) "确认后将应用以上内容。" else "确认后将拒绝此内容。",
                                ) { value ->
                                    if (continuation.isActive) continuation.resumeWith(Result.success(value))
                                }
                            }
                            if (!approved || !active()) {
                                fail("capability_denied", "Review was not confirmed")
                                return@launch
                            }
                        }
                        reply(withContext(Dispatchers.IO) { localData.taskCall(raw) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        fail("invalid_request", "Task request failed")
                    }
                }

                "localDataPickImages" -> {
                    if ("localdata.media" !in localData.grantedCapabilities()) {
                        fail("capability_denied", "Media permission is required")
                    } else onLocalPickImages { uris ->
                        if (uris.isNullOrEmpty()) fail("cancelled", "Image selection cancelled")
                        else if (active()) scope.launch(Dispatchers.IO) {
                            try {
                                reply(localData.importImages(uris).toString())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                fail("unavailable", "Image import failed")
                            }
                        }
                    }
                }

                "localDataExportBackup", "localDataRestoreBackup" -> {
                    if ("localdata.backup" !in localData.grantedCapabilities()) {
                        fail("capability_denied", "Backup permission is required")
                    } else onLocalBackup(method == "localDataRestoreBackup") { uri ->
                        if (uri == null) fail("cancelled", "File selection cancelled")
                        else if (active()) scope.launch(Dispatchers.IO) {
                            val temporary = File.createTempFile("localdata-backup-", ".zip", webView.context.cacheDir)
                            try {
                                if (method == "localDataRestoreBackup") {
                                    webView.context.contentResolver.openInputStream(uri)?.use { input ->
                                        temporary.outputStream().use { output ->
                                            val buffer = ByteArray(8192)
                                            var total = 0L
                                            while (true) {
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                total += count
                                                check(total <= 256L * 1024 * 1024)
                                                output.write(buffer, 0, count)
                                            }
                                        }
                                    } ?: error("Cannot open backup")
                                    localData.restoreBackup(temporary)
                                } else {
                                    localData.exportBackup(temporary)
                                    webView.context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                                        temporary.inputStream().use { it.copyTo(output) }
                                    } ?: error("Cannot save backup")
                                }
                                reply(localDataSuccess(JSONObject().put("completed", true)).toString())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                fail("unavailable", "Backup failed or is incompatible")
                            } finally {
                                temporary.delete()
                            }
                        }
                    }
                }
            }
        }
        if (localData.needsConsent()) onLocalConsent { approved ->
            if (approved) action() else fail("capability_denied", "Permission was declined")
        } else action()
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val requestUri = request?.url ?: return false
        val url = requestUri.toString()
        if (url.startsWith("bridge://")) {
            if (!destroyed && view != null && request.isForMainFrame &&
                isTrustedLocalPluginPage(view.url, pluginInfo.directory) &&
                requestUri.getQueryParameter("pageToken") == pageToken
            ) {
                handleBridgeCall(view, url)
                if (!destroyed) {
                    view.evaluateJavascript("window.__bridgeAccepted && window.__bridgeAccepted();", null)
                }
            }
            return true
        }
        val scheme = requestUri.scheme?.lowercase(Locale.ROOT)
        if (scheme == "file") {
            val trusted = isTrustedLocalPluginPage(url, pluginInfo.directory)
            if (!trusted) {
                Log.w(CLIENT_TAG, "Blocked navigation outside plugin directory: $url")
            }
            return !trusted
        }
        if (url == "about:blank") {
            return false
        }
        if (scheme == "http" || scheme == "https") {
            try {
                val intent = Intent(Intent.ACTION_VIEW, requestUri)
                view?.context?.startActivity(intent)
            } catch (e: Exception) {
                Log.w(CLIENT_TAG, "Cannot open external URL: $url", e)
            }
        } else {
            Log.w(CLIENT_TAG, "Blocked external URL scheme: $scheme")
        }
        return true
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        trustedPage = isTrustedLocalPluginPage(url, pluginInfo.directory) &&
            isTrustedLocalPluginPage(view?.url, pluginInfo.directory)
        if (trustedPage) {
            view?.evaluateJavascript(bridgeJavascript.replace("__ORANGE_PAGE_TOKEN__", pageToken), null)
            val theme = JSONObject(localData.themeTokens())
            view?.evaluateJavascript(
                "(function(t){Object.keys(t).forEach(function(k){document.documentElement.style.setProperty(k,t[k]);});})($theme);",
                null,
            )
        }
    }

    private fun handleBridgeCall(webView: WebView, url: String) {
        if (!isTrustedLocalPluginPage(webView.url, pluginInfo.directory)) return
        val uri = Uri.parse(url)
        val method = uri.host ?: return
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }

        PluginBridgeFileAndMedia.handleFileAction(method, params, dataStore)?.let { script ->
            postJavascript(webView, script)
            return
        }
        PluginBridgeFileAndMedia.handleMusicAction(webView.context, method, params, dataStore)?.let { script ->
            postJavascript(webView, script)
            return
        }

        when (method) {
            "localData", "localDataHostInfo", "localDataPickImages", "localDataTask" ->
                handleLocalData(webView, method, params)

            "getPluginConfig" -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val savedConfig = pluginRepository.getPluginConfig(pluginInfo.manifest.id)
                        val mergedConfig = effectivePluginConfig(pluginInfo.manifest.config, savedConfig)
                        val result = json.encodeToString(JsonObject.serializer(), JsonObject(mergedConfig))
                        postJavascript(webView, pluginBridgeJsonJavascript(params["callbackId"], result))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(CLIENT_TAG, "Failed to get plugin config", e)
                        postJavascript(webView, pluginBridgeResultJavascript(params["callbackId"], JSONObject()))
                    }
                }
            }

            "getData" -> {
                val key = params["key"] ?: ""
                val value = dataStore.getData(key)
                val result = if (value != null) {
                    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
                } else "null"
                postJavascript(webView, pluginBridgeJsonJavascript(params["callbackId"], result))
            }

            "setData" -> {
                val key = params["key"] ?: ""
                val value = params["value"] ?: ""
                dataStore.setData(key, value)
                postJavascript(webView, pluginBridgeResultJavascript(params["callbackId"], true))
            }

            "deleteData" -> {
                val key = params["key"] ?: ""
                dataStore.deleteData(key)
                postJavascript(webView, pluginBridgeResultJavascript(params["callbackId"], true))
            }

            "listData" -> {
                val keys = dataStore.listData()
                postJavascript(webView, pluginBridgeResultJavascript(params["callbackId"], JSONArray(keys)))
            }

            "pickImage" -> onPickImage(params["callbackId"] ?: "")
            "pickImages" -> onPickImages(params["callbackId"] ?: "")
            "pickFile" -> onPickFile(params["callbackId"] ?: "")
            "pickBinaryFile" -> onPickBinaryFile(params["callbackId"] ?: "")
            "importAudioFile" -> onImportAudioFile(params["callbackId"] ?: "")

            "close" -> onClose()

            "callTool" -> {
                val toolName = params["toolName"] ?: ""
                val toolParams = params["params"] ?: "{}"
                scope.launch(Dispatchers.Main) {
                    try {
                        val result = toolExecutor.callPluginTool(toolName, toolParams)
                        postJavascript(webView, pluginBridgeJsonJavascript(params["callbackId"], result))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val errorResult = JSONObject().put("success", false).put("error", e.message ?: "Unknown error")
                        postJavascript(webView, pluginBridgeResultJavascript(params["callbackId"], errorResult))
                    }
                }
            }

            "callAI" -> {
                val callbackId = params["callbackId"] ?: ""
                val prompt = params["prompt"] ?: ""
                val contextJson = params["context"] ?: "{}"
                scope.launch(Dispatchers.IO) {
                    val aiResult = toolExecutor.callAI(prompt, contextJson)
                    postJavascript(webView, pluginAiResultJavascript(callbackId, aiResult))
                }
            }

            "listConversations" -> handleConversationBridge(webView, params) { chatSync.listConversationsJson() }

            "chatImageChunk" -> handleConversationBridge(webView, params) {
                chatSync.appendImageChunk(
                    transferId = params["transferId"].orEmpty(),
                    chunk = params["chunk"].orEmpty(),
                    reset = params["reset"] == "true",
                )
                JSONObject().put("success", true)
            }

            "sendChatMessage" -> handleConversationBridge(webView, params) { chatSync.sendChatMessage(params) }

            "notifyHook" -> {
                val callbackId = params["callbackId"] ?: ""
                val hookName = params["hookName"] ?: ""
                val hookContextJson = params["context"] ?: "{}"
                scope.launch(Dispatchers.IO) {
                    try {
                        val hookResult = toolExecutor.handleHookTrigger(
                            webView,
                            hookName,
                            hookContextJson,
                            ::postJavascript,
                        )
                        postJavascript(webView, pluginBridgeJsonJavascript(callbackId, hookResult))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(CLIENT_TAG, "notifyHook failed", e)
                        val errorResult = JSONObject().put("success", false).put("error", e.message ?: "Unknown error")
                        postJavascript(webView, pluginBridgeResultJavascript(callbackId, errorResult))
                    }
                }
            }

            "startTimer" -> {
                val callbackId = params["callbackId"] ?: ""
                if (!hasPluginPermission(pluginInfo.manifest.permissions, "overlay")) {
                    postBridgeJson(
                        webView,
                        callbackId,
                        JSONObject().put("success", false).put("error", "Permission 'overlay' is required"),
                    )
                    return
                }
                val seconds = params["seconds"]?.toIntOrNull() ?: (25 * 60)
                onStartTimer(webView, seconds)
                postJavascript(webView, pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true)))
            }

            "stopTimer" -> {
                val callbackId = params["callbackId"] ?: ""
                onStopTimer()
                postJavascript(webView, pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true)))
            }

            "getTimerState" -> {
                val callbackId = params["callbackId"] ?: ""
                val running = PomodoroTimerService.isRunning()
                val remaining = PomodoroTimerService.getRemainingSeconds()
                postJavascript(
                    webView,
                    pluginBridgeResultJavascript(callbackId, JSONObject().put("running", running).put("remaining", remaining)),
                )
            }

            "showOverlay" -> {
                val callbackId = params["callbackId"] ?: ""
                if (!hasPluginPermission(pluginInfo.manifest.permissions, "overlay")) {
                    postBridgeJson(
                        webView,
                        callbackId,
                        JSONObject().put("success", false).put("error", "Permission 'overlay' is required"),
                    )
                    return
                }
                val html = params["html"] ?: ""
                onShowOverlay(webView, html)
                postJavascript(webView, pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true)))
            }

            "saveImageAsset" -> {
                val callbackId = params["callbackId"].orEmpty()
                scope.launch {
                    try {
                        val asset = withContext(Dispatchers.IO) {
                            PluginImageSave.load(dataStore, params["assetKey"].orEmpty())
                        }
                        onSaveFileAs(callbackId, asset.fileName, asset.encoded)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        postBridgeJson(
                            webView,
                            callbackId,
                            JSONObject().put("success", false).put("error", "Image asset unavailable"),
                        )
                    }
                }
            }

            "saveFileAs" -> {
                val callbackId = params["callbackId"] ?: ""
                val fileName = params["fileName"] ?: "untitled.json"
                val base64Data = params["data"] ?: ""
                onSaveFileAs(callbackId, fileName, base64Data)
            }

            "hideOverlay" -> {
                val callbackId = params["callbackId"] ?: ""
                onHideOverlay()
                postJavascript(webView, pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true)))
            }
        }
    }

    private fun handleConversationBridge(
        webView: WebView,
        params: Map<String, String>,
        block: suspend () -> JSONObject,
    ) {
        val callbackId = params["callbackId"].orEmpty()
        if (!pluginInfo.manifest.permissions.contains("conversation_sync")) {
            postBridgeJson(
                webView,
                callbackId,
                JSONObject()
                    .put("success", false)
                    .put("error", "Permission denied: conversation_sync permission not declared"),
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { block() }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.e(CLIENT_TAG, "Conversation sync bridge failed", error)
                JSONObject()
                    .put("success", false)
                    .put("error", error.message ?: "Unknown error")
            }
            postBridgeJson(webView, callbackId, result)
        }
    }

    private fun postBridgeJson(webView: WebView, callbackId: String, value: JSONObject) {
        postJavascript(webView, pluginBridgeResultJavascript(callbackId, value))
    }
}
