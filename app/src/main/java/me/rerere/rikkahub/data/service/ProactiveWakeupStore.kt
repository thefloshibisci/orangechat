package me.rerere.rikkahub.data.service

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import java.time.ZoneId
import java.util.UUID

@Serializable
internal data class ProactiveWakeup(
    val assistantId: String,
    val conversationId: String,
    val timeMillis: Long,
    val reason: String,
    val token: String = UUID.randomUUID().toString(),
)

@Serializable
internal enum class ProactiveWakeupStatus { Pending, Running, Sent, Failed, Cancelled, Skipped, Interrupted }

@Serializable
internal data class ProactiveWakeupRecord(
    val appointment: ProactiveWakeup,
    val status: ProactiveWakeupStatus,
    val outcome: String = "",
    val updatedAt: Long = 0,
    val processId: String = "",
)

internal fun proactiveWakeupOutcome(outcome: String): String = when (outcome) {
    "sent" -> "已发送到会话"
    "appointment_assistant_removed" -> "角色已删除"
    "appointment_conversation_removed" -> "原会话已删除或已变更角色"
    "no_user_message" -> "原会话没有用户消息"
    "no_model" -> "未配置聊天模型"
    "no_provider" -> "找不到此会话模型的服务商"
    "empty_response" -> "模型没有返回可发送的正文"
    "failed" -> "主动生成或工具请求出错；可在 App 日志查看本轮记录，未自动重发"
    "follow_up_limit" -> "已达连续追问上限，等待你回来"
    "user_returned", "cancelled" -> "本次唤醒已中断，未自动重发"
    "appointment_disabled" -> "主动消息或 AI 约定唤醒已关闭，未发送"
    "appointment_not_due" -> "发送前重新检查时，约定时间尚未到达，未发送"
    "disabled_or_quiet_hours" -> "已关闭主动消息或进入免打扰，未发送"
    "pass" -> "AI 检查后选择这次不发消息"
    "wait" -> "AI 决定稍后再判断，本次没有发送正文"
    "stop" -> "AI 决定等待你再次开口"
    "process_interrupted" -> "应用中断，发送结果未确认；未自动重发"
    else -> "唤醒未完成，可重新约定"
}

/** Persist a bounded diagnosis. Provider error bodies may contain prompts, signed URLs or credentials. */
private fun proactiveFailureReason(failure: Throwable): String {
    val causes = generateSequence(failure) { it.cause }.take(8).toList()
    for (cause in causes.asReversed()) {
        val status = providerResponseStatus.find(cause.message.orEmpty().take(40))?.groupValues?.get(1)
        if (status != null) return "服务商返回 HTTP $status"
        when (cause) {
            is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> return "请求超时"
            is java.net.UnknownHostException -> return "域名解析失败"
            is java.net.ConnectException -> return "无法建立连接"
            is javax.net.ssl.SSLException -> return "安全连接失败"
            is java.io.EOFException -> return "连接提前结束"
            is me.rerere.ai.util.HttpException -> return "服务商返回错误"
        }
    }
    return failure.javaClass.simpleName.take(80).ifBlank { "未知异常" }
}

private val providerResponseStatus = Regex("""^Failed to get response: ([45][0-9]{2})(?:\s|$)""")

/** An opted-in appointment uses its agreed instant, independent of ordinary active hours. */
internal fun nextProactiveWakeup(
    appointments: List<ProactiveWakeup>,
    setting: ProactiveMessageSetting,
): ProactiveWakeup? {
    if (!setting.enabled || !setting.allowAiScheduledWakeup) return null
    return appointments.minWithOrNull(compareBy(ProactiveWakeup::timeMillis, ProactiveWakeup::conversationId))
}

/** Rechecked after generation too, since consent or active hours can change during a request. */
internal fun canRunProactiveWakeup(
    appointment: ProactiveWakeup?,
    setting: ProactiveMessageSetting,
    now: Long,
    zone: ZoneId,
    manual: Boolean = false,
): Boolean = when {
    !setting.enabled -> false
    appointment != null -> setting.allowAiScheduledWakeup && appointment.timeMillis <= now
    else -> manual || isProactiveActiveTime(setting, now, zone)
}

internal fun proactiveTriggerTime(
    appointment: ProactiveWakeup?,
    fallbackTime: Long,
    setting: ProactiveMessageSetting,
    now: Long,
    zone: ZoneId,
): Long = when {
    appointment == null -> nextProactiveActiveTime(setting, fallbackTime, zone)
    // A blocked, overdue appointment gets a bounded retry instead of a tight alarm loop.
    appointment.timeMillis <= now -> now + 60_000L
    else -> appointment.timeMillis
}

/** One durable record per conversation; compare-and-remove protects a newer replacement. */
internal class ProactiveWakeupStore(
    context: Context,
    private val processId: String = currentProcessId,
) {
    private val prefs = context.getSharedPreferences("proactive_wakeups", Context.MODE_PRIVATE)

    fun all(): List<ProactiveWakeup> = synchronized(lock) { read() }

    fun snapshot(): List<ProactiveWakeupRecord> = synchronized(lock) {
        read().map { ProactiveWakeupRecord(it, ProactiveWakeupStatus.Pending) } + readHistory()
    }

    fun put(appointment: ProactiveWakeup) = synchronized(lock) {
        val previous = read()
        val replaced = previous.filter { it.conversationId == appointment.conversationId }
            .map { record(it, ProactiveWakeupStatus.Cancelled, "已由新约定替换") }
        write(previous.filterNot { it.conversationId == appointment.conversationId } + appointment,
            readHistory() + replaced)
    }

    fun cancel(conversationId: String, token: String? = null): Boolean = synchronized(lock) {
        val previous = read()
        val cancelled = previous.filter { it.conversationId == conversationId && (token == null || it.token == token) }
        if (cancelled.isEmpty()) false else {
            write(previous - cancelled.toSet(), readHistory() + cancelled.map {
                record(it, ProactiveWakeupStatus.Cancelled, "已取消")
            })
            true
        }
    }

    fun consume(appointment: ProactiveWakeup): Boolean = synchronized(lock) {
        val previous = read()
        val updated = previous.filterNot {
            it.conversationId == appointment.conversationId && it.token == appointment.token
        }
        if (previous.size == updated.size) false else {
            write(updated, readHistory() + record(appointment, ProactiveWakeupStatus.Running))
            true
        }
    }

    /** Final outcomes never overwrite another appointment or a newer terminal record. */
    fun finish(
        appointment: ProactiveWakeup,
        outcome: String,
        failureStage: String = "执行约定",
        failure: Throwable? = null,
    ) = synchronized(lock) {
        val history = readHistory()
        if (history.any { it.appointment.token == appointment.token && it.status == ProactiveWakeupStatus.Running }) {
            write(read(), history.filterNot { it.appointment.token == appointment.token } +
                record(appointment,
                    when (outcome) {
                        "sent" -> ProactiveWakeupStatus.Sent
                        "pass", "wait", "stop", "follow_up_limit", "disabled_or_quiet_hours",
                        "appointment_disabled", "appointment_not_due" -> ProactiveWakeupStatus.Skipped
                        "user_returned", "cancelled" -> ProactiveWakeupStatus.Interrupted
                        else -> ProactiveWakeupStatus.Failed
                    },
                    if (outcome == "failed" && failure != null) {
                        "${failureStage}时出错：${proactiveFailureReason(failure)}"
                    } else proactiveWakeupOutcome(outcome)))
        }
    }

    fun clear() = synchronized(lock) {
        write(emptyList(), readHistory() + read().map {
            record(it, ProactiveWakeupStatus.Cancelled, "已关闭 AI 约定主动唤醒")
        })
    }

    private fun read(): List<ProactiveWakeup> = json.decodeFromString(prefs.getString("pending", "[]") ?: "[]")

    private fun record(appointment: ProactiveWakeup, status: ProactiveWakeupStatus, outcome: String = "") =
        ProactiveWakeupRecord(appointment, status, outcome, System.currentTimeMillis(), processId)

    private fun readHistory(): List<ProactiveWakeupRecord> {
        val stored: List<ProactiveWakeupRecord> = json.decodeFromString(prefs.getString("history", "[]") ?: "[]")
        val recovered = stored.map {
            if (it.status == ProactiveWakeupStatus.Running && it.processId != processId) {
                record(it.appointment, ProactiveWakeupStatus.Interrupted, proactiveWakeupOutcome("process_interrupted"))
            } else it
        }
        if (stored != recovered) write(read(), recovered)
        return boundedHistory(recovered)
    }

    private fun boundedHistory(history: List<ProactiveWakeupRecord>) =
        history.filter { it.status == ProactiveWakeupStatus.Running } +
            history.filter { it.status != ProactiveWakeupStatus.Running }.takeLast(10)

    private fun write(appointments: List<ProactiveWakeup>, history: List<ProactiveWakeupRecord>) {
        // Queue removal and history transition must survive a crash together.
        check(prefs.edit()
            .putString("pending", json.encodeToString(appointments))
            .putString("history", json.encodeToString(boundedHistory(history))).commit()) {
            "Could not persist proactive appointment"
        }
    }

    private companion object {
        val lock = Any()
        val currentProcessId = UUID.randomUUID().toString()
        val json = Json { ignoreUnknownKeys = true }
    }
}
