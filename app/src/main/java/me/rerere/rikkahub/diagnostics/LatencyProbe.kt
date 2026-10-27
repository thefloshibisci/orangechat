package me.rerere.rikkahub.diagnostics

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import java.util.concurrent.ConcurrentHashMap

/**
 * 首字延迟打点。每次发送记录一条时间线，只记第一轮模型请求（工具续轮不覆盖）。
 * 结果按助手消息 id 存在内存里，气泡下方显示；同时打 Logcat（tag=LatencyProbe）。
 */
object LatencyProbe {
    private const val TAG = "LatencyProbe"

    private class Run(val start: Long) {
        val marks = LinkedHashMap<String, Long>()
        var messageId: String? = null
    }

    private val runs = ConcurrentHashMap<String, Run>()

    /** 助手消息 id -> 显示文本 */
    val results = mutableStateMapOf<String, String>()

    fun start(conversationId: String) {
        runs[conversationId] = Run(SystemClock.elapsedRealtime())
        latest = conversationId
    }

    fun mark(conversationId: String?, label: String) {
        val run = conversationId?.let { runs[it] } ?: return
        synchronized(run) { run.marks.putIfAbsent(label, SystemClock.elapsedRealtime()) }
    }

    @Volatile private var latest: String? = null

    /** 网络层拿不到会话 id，记到最近一次发送上。 */
    fun markLatest(label: String) = mark(latest, label)

    fun bindMessage(conversationId: String?, messageId: String) {
        val run = conversationId?.let { runs[it] } ?: return
        synchronized(run) { if (run.messageId == null) run.messageId = messageId }
    }

    /** 气泡第一次带着文字被绘制时调用。 */
    fun rendered(messageId: String) {
        val entry = runs.entries.firstOrNull { it.value.messageId == messageId } ?: return
        val run = entry.value
        synchronized(run) {
            if (run.marks.containsKey(RENDERED)) return
            run.marks[RENDERED] = SystemClock.elapsedRealtime()
        }
        publish(entry.key)
    }

    fun publish(conversationId: String) {
        val run = runs[conversationId] ?: return
        val id = run.messageId ?: return
        val text = synchronized(run) {
            var prev = run.start
            run.marks.entries.joinToString(" · ") { (label, t) ->
                val seg = "$label ${"%.1f".format((t - prev) / 1000f)}s"
                prev = t
                seg
            } + " | 总 ${"%.1f".format((prev - run.start) / 1000f)}s"
        }
        Log.i(TAG, "[$conversationId] $text")
        results[id] = text
    }

    const val QUEUED = "排队"
    const val SAVED = "存消息"
    const val PREPARED = "准备"
    const val TOOLS = "工具"
    const val REQUEST_BUILT = "拼请求"
    const val FIRST_CHUNK = "首包"
    const val FIRST_TEXT = "首字"
    const val TRANSFORMED = "转换"
    const val RENDERED = "上屏"
}
