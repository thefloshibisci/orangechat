package me.rerere.rikkahub.ui.pages.stats

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import me.rerere.rikkahub.data.db.dao.UsageSummary
import java.text.NumberFormat
import java.time.Clock
import java.time.LocalDate

enum class UsageTimeRange(val label: String) {
    TODAY("今天"), WEEK("近7天"), MONTH("近30天"), ALL("全部"),
}

internal data class UsageWindow(val since: Long, val until: Long, val format: String)

/** Calendar-day ranges use local midnight, including the current day. until is exclusive. */
internal fun UsageTimeRange.window(clock: Clock): UsageWindow {
    val today = LocalDate.now(clock)
    val start = when (this) {
        UsageTimeRange.TODAY -> today
        UsageTimeRange.WEEK -> today.minusDays(6)
        UsageTimeRange.MONTH -> today.minusDays(29)
        UsageTimeRange.ALL -> null
    }
    return UsageWindow(
        since = start?.atStartOfDay(clock.zone)?.toInstant()?.toEpochMilli() ?: 0,
        until = clock.millis().let { if (it == Long.MAX_VALUE) it else it + 1 },
        format = when (this) {
            UsageTimeRange.TODAY -> "%Y-%m-%d %H:00"
            UsageTimeRange.ALL -> "%Y-%m"
            else -> "%Y-%m-%d"
        },
    )
}

data class RequestUsageState(
    val range: UsageTimeRange = UsageTimeRange.TODAY,
    val summary: UsageSummary? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val providerNames: Map<String, String> = emptyMap(),
)

/** Both changing a range and an invalidation cancel the previous read before publishing. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun requestUsageStates(
    ranges: Flow<UsageTimeRange>,
    revisions: Flow<Long>,
    clock: Clock = Clock.systemDefaultZone(),
    query: suspend (UsageWindow) -> UsageSummary,
): Flow<RequestUsageState> = ranges.flatMapLatest { range ->
    revisions.mapLatest {
        try {
            RequestUsageState(range = range, summary = query(range.window(clock)), isLoading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RequestUsageState(range = range, isLoading = false, error = "暂时无法读取请求用量，请重试。")
        }
    }.buffer(0).onStart { emit(RequestUsageState(range = range)) }
}.buffer(0)

internal fun usageNumber(value: Long): String = NumberFormat.getIntegerInstance().format(value)

/** Cached tokens are already included in input, and missing usage is never labelled zero. */
internal fun reportedUsageLabel(requests: Long, unreported: Long, totalTokens: Long): String = when {
    requests > 0 && unreported >= requests -> "未返回用量"
    else -> "已报告 ${usageNumber(totalTokens)} token"
}

internal fun usageSourceLabel(source: String): String = when (source) {
    "CHAT" -> "聊天"
    "COMPRESSION" -> "对话压缩"
    "TITLE" -> "标题生成"
    "SUGGESTION" -> "回复建议"
    "TRANSLATION" -> "翻译"
    "MEMORY" -> "记忆处理"
    "OCR" -> "文字识别"
    "PROACTIVE" -> "主动消息"
    "VOICE_EVENT" -> "语音事件"
    "PLUGIN" -> "插件"
    "CONNECTION_TEST" -> "连接测试"
    else -> "其他"
}
