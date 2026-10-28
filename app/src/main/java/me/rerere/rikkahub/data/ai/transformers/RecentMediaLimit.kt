/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** 助手设置里滑条的上限；0 表示不限制。 */
const val MAX_CONTEXT_IMAGES_RANGE = 120
const val MAX_CONTEXT_VOICES_RANGE = 60

const val OLD_AUDIO_PLACEHOLDER = "[较早的语音，未附带原音频，也没有转写文本]"
const val OLD_IMAGE_PLACEHOLDER = "[较早的图片，为节省内存未附带原图]"

/** 每批裁剪多少个：上限的一半（向上取整），最多 10 个。 */
internal fun trimChunkSize(limit: Int): Int = minOf(10, (limit + 1) / 2).coerceAtLeast(1)

/**
 * 计算要降级多少个最旧的媒体。
 *
 * 超过上限后不是来一个挤一个，而是按批整块裁掉，这样请求前缀要攒够一批新媒体才变一次，
 * 缓存失效的次数少很多。保留数始终落在 (limit - chunk, limit] 之间，limit ≥ 1 时不会归零。
 */
internal fun mediaToDowngrade(total: Int, limit: Int): Int {
    if (limit <= 0 || total <= limit) return 0
    val chunk = trimChunkSize(limit)
    val excess = total - limit
    return ((excess + chunk - 1) / chunk) * chunk
}

/**
 * 只处理消息的顶层 parts；工具结果里的图片由 limitToolImages 单独计数。
 * 只影响本次请求，存储的消息不变。
 */
fun limitRecentParts(
    messages: List<UIMessage>,
    limit: Int,
    isTarget: (UIMessagePart) -> Boolean,
    downgrade: (UIMessagePart) -> UIMessagePart,
): List<UIMessage> {
    val total = messages.sumOf { message -> message.parts.count(isTarget) }
    var remaining = mediaToDowngrade(total, limit)
    if (remaining == 0) return messages
    // 消息按时间正序，前 remaining 个目标就是最旧的那些
    return messages.map { message ->
        if (remaining == 0 || message.parts.none(isTarget)) return@map message
        message.copy(parts = message.parts.map { part ->
            if (remaining > 0 && isTarget(part)) {
                remaining--
                downgrade(part)
            } else {
                part
            }
        })
    }
}

fun isHistoryAudio(part: UIMessagePart): Boolean =
    part is UIMessagePart.VoiceMessage || part is UIMessagePart.Audio

/** 较早的语音：有转写就发转写，没有就发占位说明，避免模型瞎猜。 */
fun downgradeOldAudio(part: UIMessagePart): UIMessagePart = when (part) {
    is UIMessagePart.VoiceMessage ->
        if (part.transcript.isNotBlank()) UIMessagePart.Text(part.transcript)
        else UIMessagePart.Text(OLD_AUDIO_PLACEHOLDER)
    else -> UIMessagePart.Text(OLD_AUDIO_PLACEHOLDER)
}

/** 只限制本地上传的图片（file:），网络图片不经过本地编码，不占内存。 */
fun isHistoryUploadImage(part: UIMessagePart): Boolean =
    part is UIMessagePart.Image && part.url.startsWith("file:")

fun downgradeOldImage(part: UIMessagePart): UIMessagePart = UIMessagePart.Text(OLD_IMAGE_PLACEHOLDER)

fun limitRecentAudio(messages: List<UIMessage>, limit: Int): List<UIMessage> =
    limitRecentParts(messages, limit, ::isHistoryAudio, ::downgradeOldAudio)

fun limitRecentUploadImages(messages: List<UIMessage>, limit: Int): List<UIMessage> =
    limitToolImages(limitRecentParts(messages, limit, ::isHistoryUploadImage, ::downgradeOldImage), limit)

const val OLD_TOOL_IMAGE_PLACEHOLDER = "[较早的工具生成图，为节省内存未附带原图]"

/**
 * 插件/工具返回的本地图片（如绘图工坊原生通道）和上传图片共用同一个上限，单独计数。
 * 生图多的会话里，每次请求都重新编码全部历史生成图，是 OOM 的主要来源。
 * 只影响本次请求；按批裁剪的规则与上传图片一致，保持缓存前缀稳定。
 */
fun limitToolImages(messages: List<UIMessage>, limit: Int): List<UIMessage> {
    fun UIMessagePart.toolImageCount(): Int =
        if (this is UIMessagePart.Tool) output.count(::isHistoryUploadImage) else 0
    val total = messages.sumOf { m -> m.parts.sumOf { it.toolImageCount() } }
    var remaining = mediaToDowngrade(total, limit)
    if (remaining == 0) return messages
    return messages.map { message ->
        if (remaining == 0 || message.parts.none { it.toolImageCount() > 0 }) return@map message
        message.copy(parts = message.parts.map { part ->
            if (remaining == 0 || part !is UIMessagePart.Tool || part.toolImageCount() == 0) return@map part
            part.copy(output = part.output.map { out ->
                if (remaining > 0 && isHistoryUploadImage(out)) {
                    remaining--
                    UIMessagePart.Text(OLD_TOOL_IMAGE_PLACEHOLDER)
                } else out
            })
        })
    }
}
