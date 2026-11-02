package me.rerere.rikkahub.data.gongdu

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext

private const val VIDEO_REFERENCE = "gongdu_video_reference"

/** Keep the tool reference with the saved video without exposing internal arguments in chat UI. */
internal fun gongduVideoReference(feed: GongduFeed): JsonObject = buildJsonObject {
    put(VIDEO_REFERENCE, buildJsonObject {
        put("feed_id", feed.id)
        put("xsec_token", feed.token)
    })
}

/** Supply the same note's reusable arguments only to an assistant allowed to read Xiaohongshu. */
object GongduVideoReferenceTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val server = ctx.settings.gongdu.serverId ?: return messages
        if (server !in ctx.assistant.mcpServers) return messages
        fun adapt(part: UIMessagePart): UIMessagePart = when (part) {
            is UIMessagePart.Tool -> part.copy(output = part.output.map(::adapt))
            is UIMessagePart.Text -> {
                val reference = part.metadata?.get(VIDEO_REFERENCE) as? JsonObject
                if (reference?.str("feed_id").isNullOrBlank() || reference?.str("xsec_token").isNullOrBlank()) part
                else part.copy(
                    text = part.text + "\n[本视频补看引用，可用于 gongdu_read_video：$reference]",
                    metadata = part.metadata?.let { JsonObject(it - VIDEO_REFERENCE) },
                )
            }
            else -> part
        }
        return messages.map { it.copy(parts = it.parts.map(::adapt)) }
    }
}
