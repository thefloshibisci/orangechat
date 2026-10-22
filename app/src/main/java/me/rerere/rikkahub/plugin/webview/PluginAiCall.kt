/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel

/** The shared boundary for page, overlay and hook text generation. */
internal class PluginAiCall(
    private val permissions: List<String>,
    private val loadSettings: suspend () -> Settings,
    private val generateText: suspend (ProviderSetting, List<UIMessage>, TextGenerationParams) -> String,
    private val onError: (Exception) -> Unit = {},
) {
    suspend fun call(prompt: String, systemPrompt: () -> String): String {
        try {
            currentCoroutineContext().ensureActive()
            // Reject before accessing user configuration or resolving a provider.
            if ("ai_chat" !in permissions) {
                return errorResult("Permission denied: ai_chat permission not declared in manifest")
            }
            if (prompt.isBlank()) return errorResult("prompt is required")

            val settings = loadSettings()
            val model = settings.getCurrentChatModel()
                ?: return errorResult("No chat model configured")
            val provider = model.findProvider(settings.providers)
                ?: return errorResult("Provider not found for model")
            val messages = listOf(
                UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(systemPrompt()))),
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(prompt))),
            )
            currentCoroutineContext().ensureActive()
            val text = generateText(
                provider,
                messages,
                TextGenerationParams(model = model, tools = emptyList(), temperature = 0.7f),
            )
            currentCoroutineContext().ensureActive()
            return buildJsonObject {
                put("success", true)
                put("text", text)
            }.toString()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError(error)
            return errorResult(error.message ?: "AI generation failed")
        }
    }

    private fun errorResult(message: String): String = buildJsonObject {
        put("success", false)
        put("error", message)
    }.toString()
}

internal fun pluginAiResultJavascript(callbackId: String, result: String): String =
    pluginBridgeJsonJavascript(callbackId, result)
