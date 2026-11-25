/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.service.ChatService
import org.json.JSONArray
import org.json.JSONObject
import kotlin.uuid.Uuid

private const val HANDLERS_TAG = "PluginWebViewPage"

internal const val MAX_CHAT_IMAGE_TRANSFERS = 4
internal const val MAX_CHAT_IMAGE_TRANSFER_CHARS = 16_000_000

internal fun parseBridgeConversationId(value: String?): Uuid =
    value?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: throw IllegalArgumentException("invalid conversationId")

internal fun buildReadingAssistantSystemPrompt(contextJson: String): String = buildString {
    append("\u4F60\u662F\u4E00\u4E2A\u9605\u8BFB\u52A9\u624B\u3002\u8BF7\u6839\u636E\u7528\u6237\u7684\u95EE\u9898\u7ED9\u51FA\u6709\u6DF1\u5EA6\u7684\u3001\u6E29\u67D4\u7684\u56DE\u7B54\u3002")
    if (contextJson.isNotBlank() && contextJson != "{}") {
        try {
            val ctx = JSONObject(contextJson)
            append("\n\n\u5F53\u524D\u9605\u8BFB\u4E0A\u4E0B\u6587\uFF1A")
            if (ctx.has("book")) append("\n\u4E66\u540D\uFF1A").append(ctx.getString("book"))
            if (ctx.has("chapter")) append("\n\u7AE0\u8282\uFF1A\u7B2C").append(ctx.getString("chapter")).append("\u7AE0")
            if (ctx.has("page")) append("\n\u9875\u7801\uFF1A\u7B2C").append(ctx.getString("page")).append("\u9875")
            if (ctx.has("annotations")) append("\n\u5DF2\u6709\u6279\u6CE8\uFF1A").append(ctx.getString("annotations"))
            if (ctx.has("content")) append("\n\u9875\u9762\u5185\u5BB9\uFF1A").append(ctx.getString("content"))
            if (ctx.has("quote")) append("\n\u5F15\u7528\u539F\u6587\uFF1A").append(ctx.getString("quote"))
            if (ctx.has("note")) append("\n\u7528\u6237\u6279\u6CE8\uFF1A").append(ctx.getString("note"))
        } catch (e: Exception) {
            Log.w(HANDLERS_TAG, "Failed to parse context JSON", e)
        }
    }
}

internal class PluginBridgeImageTransferBuffer(
    private val maxTransfers: Int = MAX_CHAT_IMAGE_TRANSFERS,
    private val maxChars: Int = MAX_CHAT_IMAGE_TRANSFER_CHARS,
) {
    private val transfers = mutableMapOf<String, StringBuilder>()

    fun clear() {
        synchronized(transfers) {
            transfers.clear()
        }
    }

    fun appendChunk(transferId: String, chunk: String, reset: Boolean) {
        require(transferId.isNotBlank()) { "transferId is required" }
        synchronized(transfers) {
            val builder = if (reset) {
                require(transferId in transfers || transfers.size < maxTransfers) {
                    "Too many concurrent image transfers"
                }
                StringBuilder().also { transfers[transferId] = it }
            } else {
                checkNotNull(transfers[transferId]) { "Image transfer has not started" }
            }
            require(builder.length.toLong() + chunk.length.toLong() <= maxChars) {
                "Image transfer exceeds size limit"
            }
            builder.append(chunk)
        }
    }

    fun consume(transferId: String): String =
        synchronized(transfers) {
            checkNotNull(transfers.remove(transferId)) { "Image transfer not found" }.toString()
        }
}

internal class PluginBridgeChatSync(
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val filesManager: FilesManager,
) {
    private val imageTransfers = PluginBridgeImageTransferBuffer()

    fun clear() {
        imageTransfers.clear()
    }

    fun appendImageChunk(transferId: String, chunk: String, reset: Boolean) {
        imageTransfers.appendChunk(transferId = transferId, chunk = chunk, reset = reset)
    }

    fun consumeImageTransfer(transferId: String): String =
        imageTransfers.consume(transferId)

    suspend fun listConversationsJson(): JSONObject {
        val currentAssistant = settingsStore.settingsFlow.first().getCurrentAssistant()
        val items = JSONArray()
        conversationRepository.getConversationsOfAssistant(currentAssistant.id).first()
            .sortedByDescending { it.updateAt }
            .forEach { conversation ->
                items.put(
                    JSONObject()
                        .put("id", conversation.id.toString())
                        .put("title", conversation.title.ifBlank { "未命名聊天" })
                        .put("messageCount", conversation.messageNodes.size)
                        .put("updatedAt", conversation.updateAt.toString()),
                )
            }
        return JSONObject().put("success", true).put("conversations", items)
    }

    suspend fun sendChatMessage(params: Map<String, String>): JSONObject {
        val conversationId = parseBridgeConversationId(params["conversationId"])
        require(conversationRepository.getConversationById(conversationId) != null) {
            "conversation not found"
        }
        val text = params["text"]?.trim().orEmpty()
        require(text.isNotEmpty()) { "text is required" }
        chatService.initializeConversation(conversationId)
        attachmentReferenceGuard.beginWrite().use {
            val content = buildList<UIMessagePart> {
                add(UIMessagePart.Text(text))
                val transferredImage = params["imageTransferId"]?.takeIf { it.isNotBlank() }?.let(::consumeImageTransfer)
                (transferredImage ?: params["image"])?.takeIf { it.isNotBlank() }?.let { image ->
                    require(image.startsWith("data:image/") && image.contains(";base64,")) {
                        "Expected a base64 image"
                    }
                    val mime = image.substringAfter("data:").substringBefore(';')
                    val bytes = Base64.decode(image.substringAfter(";base64,"), Base64.DEFAULT)
                    require(bytes.isNotEmpty()) { "Image is empty" }
                    val asset = filesManager.saveUploadFromBytes(
                        bytes = bytes,
                        displayName = "plugin_image.${mime.substringAfter('/').substringBefore('+')}",
                        mimeType = mime,
                    )
                    add(UIMessagePart.Image(Uri.fromFile(filesManager.getFile(asset)).toString()))
                }
            }
            checkNotNull(
                chatService.submitMessage(
                    conversationId = conversationId,
                    content = content,
                    answer = true,
                    transientSystemPrompt = params["hiddenContext"]?.takeIf { it.isNotBlank() },
                ),
            ) { "Conversation no longer accepts messages" }
        }
        return JSONObject().put("success", true).put("status", "accepted")
    }
}

internal class PluginBridgeToolExecutor(
    private val pluginInfo: PluginInfo,
    private val androidContext: Context,
    private val pluginLoader: PluginLoader,
    private val pluginManager: PluginManager,
    private val aiCall: PluginAiCall,
    private val localData: LocalDataWebBridge,
    private val onToolConsent: (String, (Boolean) -> Unit) -> Unit,
) {
    suspend fun callAI(prompt: String, contextJson: String): String =
        aiCall.call(prompt) { buildReadingAssistantSystemPrompt(contextJson) }

    suspend fun handleHookTrigger(
        webView: WebView,
        hookName: String,
        contextJson: String,
        postJavascript: (WebView, String) -> Unit,
    ): String {
        val hookConfig = pluginInfo.manifest.hookConfigs[hookName]
            ?: return JSONObject()
                .put("success", false)
                .put("error", "Hook '$hookName' not configured in manifest")
                .toString()

        when (hookConfig.action) {
            "call_js_function" -> {
                val functionName = hookConfig.function
                    ?: return JSONObject()
                        .put("success", false)
                        .put("error", "function is required for call_js_function action")
                        .toString()

                val jsCode = pluginHookJavascript(functionName, contextJson)
                    ?: return JSONObject()
                        .put("success", false)
                        .put("error", "function must be a JavaScript identifier")
                        .toString()

                if (!hookConfig.autoTrigger) {
                    return JSONObject()
                        .put("success", false)
                        .put("error", "Hook '$hookName' is not auto-triggered")
                        .toString()
                }

                postJavascript(webView, jsCode)
                return JSONObject()
                    .put("success", true)
                    .put("action", "call_js_function")
                    .put("function", functionName)
                    .toString()
            }

            "call_ai" -> {
                val template = hookConfig.promptTemplate
                    ?: return """{"success":false,"error":"promptTemplate is required for call_ai action"}"""

                val contextObj = try {
                    JSONObject(contextJson)
                } catch (_: Exception) {
                    return """{"success":false,"error":"Invalid context JSON"}"""
                }

                var prompt = template
                val variableKeys = listOf("book", "chapter", "page", "quote", "note", "content", "annotations")
                for (key in variableKeys) {
                    if (contextObj.has(key)) {
                        val value = contextObj.getString(key)
                        prompt = prompt.replace("{$key}", value)
                    }
                }

                return callAI(prompt, contextJson)
            }

            else -> return """{"success":false,"error":"Unknown hook action: ${hookConfig.action}"}"""
        }
    }

    suspend fun authorizeTool(target: PluginInfo, toolName: String): Boolean {
        if (target.manifest.id == pluginInfo.manifest.id) return true
        val preferences = androidContext.getSharedPreferences("localdata_host_tool_grants", Context.MODE_PRIVATE)
        val grantKey = "${pluginInfo.manifest.id}|${target.manifest.id}|$toolName"
        if (preferences.getBoolean(grantKey, false)) return true
        val approved = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
            onToolConsent("${pluginInfo.manifest.name} → ${target.manifest.name}: $toolName") { allowed ->
                if (continuation.isActive) continuation.resumeWith(Result.success(allowed))
            }
        }
        if (approved) preferences.edit().putBoolean(grantKey, true).apply()
        return approved
    }

    suspend fun callPluginTool(toolName: String, params: String): String {
        return try {
            pluginManager.awaitInitialization()
            val candidates = pluginManager.plugins.value.filter { plugin ->
                plugin.isEnabled && plugin.loadError == null &&
                    plugin.manifest.tools.any { it.name == toolName }
            }
            val target = candidates.find { it.manifest.id == pluginInfo.manifest.id }
                ?: candidates.firstOrNull()
                ?: return JSONObject().put("success", false).put("error", "Tool not found: $toolName").toString()

            if (!authorizeTool(target, toolName)) {
                return localDataFailure("capability_denied", "Tool permission was declined").toString()
            }
            check(
                pluginManager.plugins.value.any {
                    it.manifest.id == target.manifest.id && it.isEnabled && it.loadError == null
                },
            )
            val jsonParams = kotlinx.serialization.json.Json.parseToJsonElement(params)

            val result = pluginLoader.callTool(
                pluginId = target.manifest.id,
                toolName = toolName,
                params = jsonParams,
            )

            result.fold(
                onSuccess = { jsonElement: kotlinx.serialization.json.JsonElement ->
                    localData.importToolImage(
                        target.manifest.id,
                        kotlinx.serialization.json.Json.encodeToString(
                            kotlinx.serialization.json.JsonElement.serializer(),
                            jsonElement,
                        ),
                    )
                },
                onFailure = { error: Throwable ->
                    JSONObject().put("success", false).put("error", error.message ?: "Plugin call failed").toString()
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", e.message ?: "Plugin call failed").toString()
        }
    }
}

internal object PluginBridgeFileAndMedia {
    fun handleFileAction(
        method: String,
        params: Map<String, String>,
        dataStore: PluginDataStore,
    ): String? {
        val callbackId = params["callbackId"]
        return when (method) {
            "writeFile" -> {
                val fileName = params["fileName"] ?: ""
                val base64Data = params["data"] ?: ""
                try {
                    val file = dataStore.resolveDataFile(fileName)
                        ?: throw SecurityException("Invalid data file path")
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    file.writeBytes(bytes)
                    pluginBridgeResultJavascript(callbackId, file.absolutePath)
                } catch (_: Exception) {
                    pluginBridgeResultJavascript(callbackId, null)
                }
            }

            "readFile" -> {
                val fileName = params["fileName"] ?: ""
                try {
                    val file = dataStore.resolveDataFile(fileName)
                        ?: throw SecurityException("Invalid data file path")
                    if (file.exists()) {
                        val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        pluginBridgeResultJavascript(callbackId, base64)
                    } else {
                        pluginBridgeResultJavascript(callbackId, null)
                    }
                } catch (_: Exception) {
                    pluginBridgeResultJavascript(callbackId, null)
                }
            }

            "listFiles" -> {
                val dirPath = params["dir"] ?: ""
                val baseDir = dataStore.resolveDataFile(dirPath)
                val files = if (baseDir?.exists() == true && baseDir.isDirectory) {
                    baseDir.listFiles()?.map { it.name } ?: emptyList()
                } else emptyList()
                pluginBridgeResultJavascript(callbackId, JSONArray(files))
            }

            "deleteFile" -> {
                val fileName = params["fileName"] ?: ""
                val file = dataStore.resolveDataFile(fileName)
                val result = file?.delete() == true
                pluginBridgeJsonJavascript(callbackId, result.toString())
            }

            else -> null
        }
    }

    fun handleMusicAction(
        context: Context,
        method: String,
        params: Map<String, String>,
        dataStore: PluginDataStore,
    ): String? {
        val callbackId = params["callbackId"]
        return when (method) {
            "musicPlay" -> {
                val filePath = params["filePath"] ?: ""
                val title = params["title"] ?: ""
                val artist = params["artist"] ?: ""
                try {
                    val safeFile = dataStore.resolveDataFile(filePath)?.takeIf { it.isFile }
                        ?: throw SecurityException("Invalid music file path")
                    MusicPlayerService.play(context, safeFile.absolutePath, title, artist)
                    pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true))
                } catch (e: Exception) {
                    pluginBridgeResultJavascript(
                        callbackId,
                        JSONObject().put("success", false).put("error", e.message ?: "Unknown error"),
                    )
                }
            }

            "musicPause" -> {
                try {
                    MusicPlayerService.pause(context)
                    pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true))
                } catch (_: Exception) {
                    pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false))
                }
            }

            "musicResume" -> {
                try {
                    MusicPlayerService.resume(context)
                    pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true))
                } catch (_: Exception) {
                    pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false))
                }
            }

            "musicStop" -> {
                try {
                    MusicPlayerService.stop(context)
                    pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true))
                } catch (_: Exception) {
                    pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false))
                }
            }

            "musicSeek" -> {
                val position = params["position"]?.toIntOrNull() ?: 0
                try {
                    MusicPlayerService.seekTo(context, position)
                    pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true))
                } catch (e: Exception) {
                    Log.e(HANDLERS_TAG, "musicSeek failed, position=$position, err=${e.message}", e)
                    pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false))
                }
            }

            "musicGetProgress" -> {
                try {
                    val position = MusicPlayerService.getCurrentPosition()
                    val duration = MusicPlayerService.getDuration()
                    pluginBridgeResultJavascript(
                        callbackId,
                        JSONObject().put("position", position).put("duration", duration),
                    )
                } catch (e: Exception) {
                    Log.e(HANDLERS_TAG, "musicGetProgress failed, err=${e.message}", e)
                    pluginBridgeResultJavascript(
                        callbackId,
                        JSONObject().put("position", 0).put("duration", 0),
                    )
                }
            }

            else -> null
        }
    }
}
