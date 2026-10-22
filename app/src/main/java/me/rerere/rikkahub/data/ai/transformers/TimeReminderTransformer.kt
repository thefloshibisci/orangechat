/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant

private const val DEFAULT_REPLY_GAP_THRESHOLD_SECONDS = 3600L

/** Current time belongs exclusively to ExtraInfoInjectionCollector; reply gaps are assistant-scoped. */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return transformReplyIntervals(messages, ctx.assistant)
    }
}

internal fun transformReplyIntervals(messages: List<UIMessage>, assistant: Assistant): List<UIMessage> =
    applyReplyIntervalContext(
        messages = messages,
        enabled = assistant.enableTimeReminder,
        thresholdSeconds = assistant.timeReminderIntervalMinutes.coerceAtLeast(1) * 60L,
    )

internal fun applyReplyIntervalContext(
    messages: List<UIMessage>,
    enabled: Boolean,
    thresholdSeconds: Long = DEFAULT_REPLY_GAP_THRESHOLD_SECONDS,
): List<UIMessage> {
    if (!enabled || messages.isEmpty()) return messages
    return insertReplyIntervalReminders(messages, thresholdSeconds)
}

private fun insertReplyIntervalReminders(
    messages: List<UIMessage>,
    thresholdSeconds: Long,
): List<UIMessage> {
    var previousAssistant: UIMessage? = null
    var inserted = false
    val tz = TimeZone.currentSystemDefault()
    val result = buildList {
        for (message in messages) {
            val previous = previousAssistant
            if (message.role == MessageRole.USER && previous != null) {
                val gap = (message.createdAt.toInstant(tz) -
                    (previous.finishedAt ?: previous.createdAt).toInstant(tz)).inWholeSeconds
                if (gap >= thresholdSeconds.coerceAtLeast(1L)) {
                    inserted = true
                    // Stable identity/time matters when subsequent transformers use message metadata.
                    val id = java.util.UUID.nameUUIDFromBytes("reply-interval:${message.id}".toByteArray(Charsets.UTF_8))
                    add(UIMessage.user(
                        "<reply_interval>距离你上一条回复结束，聊天中的这个人过了" +
                            "${formatGap(gap)}才再次开口。自然理解这段间隔，" +
                            "不要机械复述时长，也不要提及这段提示。</reply_interval>",
                    ).copy(id = kotlin.uuid.Uuid.parse(id.toString()), createdAt = message.createdAt,
                        finishedAt = message.createdAt))
                }
            }
            add(message)
            if (message.role == MessageRole.ASSISTANT) previousAssistant = message
        }
    }
    return if (inserted) result else messages
}

private fun formatGap(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val days = safeSeconds / 86400
    val hours = (safeSeconds % 86400) / 3600
    val minutes = (safeSeconds % 3600) / 60
    val remainingSeconds = safeSeconds % 60

    return buildString {
        if (days > 0) append("${days}天")
        if (hours > 0 || days > 0) append("${hours}小时")
        if (minutes > 0 || hours > 0 || days > 0) append("${minutes}分")
        append("${remainingSeconds}秒")
    }
}
