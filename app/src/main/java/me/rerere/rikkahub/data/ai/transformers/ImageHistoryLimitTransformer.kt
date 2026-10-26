/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage

/** Shared request-only media policy for ordinary and proactive generation. */
val historyMediaTransformers: List<InputMessageTransformer> =
    listOf(ImageHistoryLimitTransformer, VoiceMessageTransformer)

/**
 * 支持图片输入的模型会把历史里每张上传图片重新编码进请求。
 * 按助手的 maxContextImages 只保留最近几张原图（0 不限制），更早的换成文字占位；只影响本次请求，不改存储。
 * 不支持图片的模型由 OcrTransformer 处理，这里不动。
 */
object ImageHistoryLimitTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (!ctx.model.inputModalities.contains(Modality.IMAGE)) return messages
        return limitRecentUploadImages(messages, ctx.assistant.maxContextImages)
    }
}
