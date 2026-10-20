/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // Discovered from Codex; optional for old configurations and manually added models.
    val codexReasoningEfforts: List<String> = emptyList(),
    val audioInputMode: AudioInputMode = AudioInputMode.AUTO,
)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}




@Serializable
enum class AudioInputMode { AUTO, AUDIO, ASR }

/** Only transports with implemented audio payloads may bypass transcription. */
fun Model.supportsDirectAudio(provider: ProviderSetting?): Boolean {
    if (type != ModelType.CHAT || audioInputMode == AudioInputMode.ASR) return false
    val transportSupportsAudio = provider is ProviderSetting.Google ||
        (provider is ProviderSetting.OpenAI && !provider.useResponseApi)
    if (!transportSupportsAudio) return false
    if (audioInputMode == AudioInputMode.AUDIO) return true
    if (listOf("embedding", "image", "tts", "live").any { it in modelId.lowercase() }) return false
    return Regex("(^|[^a-z0-9])gemini[-_]").containsMatchIn(modelId.lowercase())
}
