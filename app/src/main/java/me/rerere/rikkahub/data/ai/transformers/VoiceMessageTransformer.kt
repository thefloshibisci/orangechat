package me.rerere.rikkahub.data.ai.transformers

import android.net.Uri
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.supportsDirectAudio
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.audioMimeType
import me.rerere.ai.util.openAiAudioFormat
import me.rerere.rikkahub.data.datastore.findProvider
import java.io.File

/** Request-only adaptation: stored audio remains available when switching back to a capable model. */
object VoiceMessageTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val provider = ctx.model.findProvider(ctx.settings.providers)
        val directAudio = ctx.model.supportsDirectAudio(provider)
        fun adapt(url: String, transcript: String = ""): UIMessagePart {
            val audio = UIMessagePart.Audio(if (File(url).isAbsolute) Uri.fromFile(File(url)).toString() else url)
            val supportedFormat = directAudio && runCatching {
                when (provider) {
                    is ProviderSetting.OpenAI -> audio.openAiAudioFormat()
                    is ProviderSetting.Google -> audio.audioMimeType()
                    else -> error("Audio transport unavailable")
                }
            }.isSuccess
            return when {
                supportedFormat -> audio
                transcript.isNotBlank() -> UIMessagePart.Text(transcript)
                else -> UIMessagePart.Text(
                    "[音频附件未提供给当前模型：模型或通道不支持此音频，且没有转写文本。原文件仍保留，请勿猜测其内容。]"
                )
            }
        }
        // 直传音频时，每条历史语音都会被 base64 编进请求；按助手设置只保留最近几条原音频，防止 OOM
        val limited = if (directAudio) limitRecentAudio(messages, ctx.assistant.maxContextVoices) else messages
        fun adaptPart(part: UIMessagePart): UIMessagePart = when (part) {
            is UIMessagePart.VoiceMessage -> adapt(part.url, part.transcript)
            is UIMessagePart.Audio -> {
                val adapted = adapt(part.url)
                if (adapted is UIMessagePart.Audio) part.copy(url = adapted.url) else adapted
            }
            is UIMessagePart.Tool -> part.copy(output = part.output.map(::adaptPart))
            else -> part
        }
        return limited.map { message ->
            message.copy(parts = message.parts.map(::adaptPart))
        }
    }
}
