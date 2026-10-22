package me.rerere.rikkahub.data.sync

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import me.rerere.rikkahub.BuildConfig

/** Local startup markers only: never record archive contents or message text. */
internal object StartupRecoveryDiagnostics {
    private fun preferences(context: Context) = context.getSharedPreferences("startup_recovery_diagnostics", Context.MODE_PRIVATE)

    fun beginLaunch(context: Context) {
        val prefs = preferences(context)
        prefs.edit().putString("previous", prefs.getString("current", "无记录"))
            .putString("current", "Application 启动").apply()
    }

    fun record(context: Context, phase: String) {
        preferences(context).edit().putString("current", phase).apply()
    }

    /** Run off Main; Android may read system exit traces from disk. Nothing is uploaded. */
    fun report(context: Context): String = buildString {
        appendLine("OrangeChat RecoveryEntry-v2 / ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android API ${Build.VERSION.SDK_INT}")
        val prefs = preferences(context)
        appendLine("上次启动最后阶段：${prefs.getString("previous", "无记录")}")
        appendLine("本次阶段：${prefs.getString("current", "无记录")}")
        appendLine("存在待恢复任务：${ChatHistoryBackup.hasPending(context)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val manager = context.getSystemService(ActivityManager::class.java)
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 3).forEach { exit ->
                    val reason = when (exit.reason) {
                        ApplicationExitInfo.REASON_ANR -> "ANR"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                        else -> "OTHER(${exit.reason})"
                    }
                    appendLine("系统退出记录：time=${exit.timestamp}, reason=$reason, status=${exit.status}, pssKiB=${exit.pss}")
                    if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                        exit.traceInputStream?.bufferedReader()?.use { reader ->
                            val buffer = CharArray(24_000)
                            val count = reader.read(buffer)
                            if (count > 0) appendLine(String(buffer, 0, count))
                        }
                    }
                }
            }.onFailure { appendLine("系统退出记录不可用：${it.javaClass.simpleName}") }
        } else appendLine("此系统版本不提供进程退出记录")
    }
}
