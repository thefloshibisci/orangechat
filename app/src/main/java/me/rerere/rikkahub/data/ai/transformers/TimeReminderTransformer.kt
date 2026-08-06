/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.toJavaInstant

private const val TIME_GAP_THRESHOLD_SECONDS = 300L // 5 分钟

/**
 * 时间提醒注入转换器
 *
 * 首次用户消息仍会注入当时的时间。
 * 仅检查当前上下文里最新一条用户消息：当它在上一条 assistant 消息完成
 * 5 分钟或更久后发出时，额外注入精确到秒的时间间隔。
 *
 * 只处理最新一次回复间隔，避免把历史中的每段长间隔重复注入，
 * 从而减少额外 token 消耗，也避免模型反复关注已经过去的等待时间。
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableTimeReminder) return messages
        return applyTimeReminder(messages)
    }
}

internal fun applyTimeReminder(messages: List<UIMessage>): List<UIMessage> {
    val firstUserIndex = messages.indexOfFirst { it.role == MessageRole.USER }
    if (firstUserIndex == -1) return messages

    val latestUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    val tz = TimeZone.currentSystemDefault()

    val firstUserReminder = buildTimeReminderMessage(
        gapSeconds = null,
        instant = messages[firstUserIndex].createdAt.toInstant(tz),
    )

    val latestReplyReminder = if (latestUserIndex > firstUserIndex) {
        val latestUser = messages[latestUserIndex]
        val previous = messages.getOrNull(latestUserIndex - 1)

        if (previous?.role == MessageRole.ASSISTANT) {
            val latestUserInstant = latestUser.createdAt.toInstant(tz)
            val previousEndInstant = (previous.finishedAt ?: previous.createdAt).toInstant(tz)
            val gapSeconds = (latestUserInstant - previousEndInstant).inWholeSeconds

            if (gapSeconds >= TIME_GAP_THRESHOLD_SECONDS) {
                buildTimeReminderMessage(gapSeconds, latestUserInstant)
            } else {
                null
            }
        } else {
            null
        }
    } else {
        null
    }

    return buildList(messages.size + 2) {
        messages.forEachIndexed { index, message ->
            if (index == firstUserIndex) {
                add(firstUserReminder)
            }
            if (index == latestUserIndex && latestReplyReminder != null) {
                add(latestReplyReminder)
            }
            add(message)
        }
    }
}

private fun buildTimeReminderMessage(gapSeconds: Long?, instant: Instant): UIMessage {
    val javaInstant = instant.toJavaInstant()
    val dayOfWeek = javaInstant.atZone(ZoneId.systemDefault()).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    val timeStr = javaInstant.toLocalDateTime()
    val content = if (gapSeconds != null) {
        val gapText = formatGap(gapSeconds)
        "<time_reminder>Current time: $dayOfWeek, $timeStr. " +
            "The user replied $gapText after your previous message finished. " +
            "Understand the elapsed time naturally. Do not mechanically repeat the duration " +
            "and do not mention this reminder or any technical implementation.</time_reminder>"
    } else {
        "<time_reminder>Current time: $dayOfWeek, $timeStr</time_reminder>"
    }
    return UIMessage.user(content)
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
