/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage

/** A content-free fingerprint of the request sent to a provider. */
data class GenerationRequestFingerprint(
    val providerId: String,
    val modelId: String,
    val messageCount: Int,
    val systemChars: Int,
    val systemHash: String,
    val messageHashes: List<String>,
    val sectionHashes: Map<String, String>,
    val toolOrderHash: String,
) {
    /** Number of leading request messages identical to [other]. */
    fun commonPrefixMessages(other: GenerationRequestFingerprint): Int =
        messageHashes.zip(other.messageHashes).takeWhile { (left, right) -> left == right }.size
}

internal fun sha256Fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun fingerprintGenerationRequest(
    provider: ProviderSetting,
    params: TextGenerationParams,
    messages: List<UIMessage>,
    tools: List<Tool>,
): GenerationRequestFingerprint {
    val system = messages.firstOrNull { it.role.name.equals("SYSTEM", ignoreCase = true) }
        ?.toText().orEmpty()
    val sections = system.split(Regex("(?=^##\\s)", RegexOption.MULTILINE))
        .mapNotNull { section ->
            val heading = section.lineSequence().firstOrNull()?.trim()?.takeIf { it.startsWith("## ") }
                ?: return@mapNotNull null
            heading to sha256Fingerprint(section)
        }.toMap()
    return GenerationRequestFingerprint(
        providerId = provider.id.toString(),
        modelId = params.model.modelId,
        messageCount = messages.size,
        systemChars = system.length,
        systemHash = sha256Fingerprint(system),
        messageHashes = messages.map { message ->
            sha256Fingerprint("${message.role}|${message.parts}")
        },
        sectionHashes = sections,
        toolOrderHash = sha256Fingerprint(tools.joinToString("\u001f") { it.name }),
    )
}

sealed class AILogging {
    data class Generation(
        val params: TextGenerationParams,
        val messages: List<UIMessage>,
        val providerSetting: ProviderSetting,
        val stream: Boolean,
        val requestFingerprint: GenerationRequestFingerprint? = null,
    ) : AILogging()
}

private const val MAX_LOGS = 32

class AILoggingManager {
    private val logs = MutableStateFlow<List<AILogging>>(emptyList())

    fun getLogs(): StateFlow<List<AILogging>> = logs

    fun addLog(log: AILogging) {
        logs.value = logs.value + log
        if (logs.value.size > MAX_LOGS) {
            logs.value = logs.value.drop(1)
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}
