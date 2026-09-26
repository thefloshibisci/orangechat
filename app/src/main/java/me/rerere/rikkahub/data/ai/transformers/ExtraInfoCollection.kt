package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.data.datastore.Settings

/** Preserve the separate proactive app-usage authorization without changing saved settings. */
internal fun Settings.forProactiveExtraInfo(): Settings = copy(systemToolsSetting = systemToolsSetting.copy(
    extraInfoInjectionEnabled = systemToolsSetting.extraInfoInjectionEnabled &&
        systemToolsSetting.extraInfoInProactiveEnabled,
    // ProactiveMessageService adds time once, using the same master/source/background gates.
    timeContextInjectionEnabled = false,
    currentScreenAppContextInjectionEnabled = systemToolsSetting.currentScreenAppContextInjectionEnabled &&
        proactiveMessageSetting.allowProactiveAppUsage,
    recentAppUsageContextInjectionEnabled = systemToolsSetting.recentAppUsageContextInjectionEnabled &&
        proactiveMessageSetting.allowProactiveAppUsage,
    // A foreground OCR opt-in must never become permission for unattended screen capture.
    screenTextContextInjectionEnabled = false,
))

internal data class ExtraInfoCollectionResult(val status: String, val text: String? = null)

internal enum class ExtraInfoIssue(val label: String) {
    ACCESSIBILITY("无障碍服务未连接，请为当前安装的版本授权"),
    WINDOW("当前窗口不可读取"),
    OCR_UNSUPPORTED("屏幕 OCR 需要 Android 11 或更高版本"),
    SCREEN_PROTECTED("系统禁止截取此屏幕"),
    SCREEN_BUSY("截图频率受限，请稍后重试"),
    SCREEN_FAILED("系统截图失败"),
    OCR_INITIALIZATION("OCR 引擎初始化失败"),
    OCR_UNAVAILABLE("本地 OCR 模型暂不可用"),
    OCR_IMAGE("OCR 无法处理此截图"),
    OCR_RECOGNITION("OCR 文字识别失败"),
    NOTIFICATION_PERMISSION("当前版本未获通知访问权限"),
    NOTIFICATION_DISCONNECTED("通知权限已开，但监听服务尚未连接"),
    AMAP_KEY("未配置高德 Web 服务 Key"),
    ADDRESS("地址查询未成功，请检查高德 Key、配额及网络"),
    LOCATION_PERMISSION("未授予位置权限"),
    BACKGROUND_SCREEN("后台请求不采集屏幕文字"),
}

internal class ExtraInfoUnavailable(val issue: ExtraInfoIssue, val diagnosticCode: Int? = null) : Exception(issue.name) {
    fun status(prefix: String = "unavailable"): String =
        "$prefix:${issue.name}" + (diagnosticCode?.let { ":$it" } ?: "")
}

private fun String.issueSummary(): String {
    val label = ExtraInfoIssue.entries.firstOrNull {
        it.name == substringAfter(':').substringBefore(':')
    }?.label ?: "不可读取"
    val code = substringAfterLast(':').toIntOrNull()
    return label + (code?.let { "（错误码 $it）" } ?: "")
}

internal fun ExtraInfoCollectionResult.summary(): String = when {
    status == "success" -> "已读取"
    status == "empty" -> "读取完成，结果为空"
    status == "timeout" -> "读取超时"
    status.startsWith("fallback:") -> "已读取（无障碍可见文字；${status.issueSummary()}）"
    status.startsWith("unavailable:") -> status.issueSummary()
    else -> "读取失败"
}

internal fun ExtraInfoCollectionResult.contextText(): String = text ?: "未取得数据：${summary()}。不能据此推断设备内容。"

internal data class ExtraInfoDiagnostic(val name: String, val summary: String)
internal data class ExtraInfoDiagnosticSnapshot(
    val timestamp: Long = 0,
    val proactive: Boolean = false,
    val items: List<ExtraInfoDiagnostic> = emptyList(),
)

/** Status only: never retain screen, location, notification content or raw errors here. */
internal object ExtraInfoDiagnostics {
    private val mutable = MutableStateFlow(ExtraInfoDiagnosticSnapshot())
    val latest = mutable.asStateFlow()
    fun publish(proactive: Boolean, results: List<Pair<String, ExtraInfoCollectionResult>>) {
        mutable.value = ExtraInfoDiagnosticSnapshot(System.currentTimeMillis(), proactive,
            results.map { (name, result) -> ExtraInfoDiagnostic(name, result.summary()) })
    }
}

/** Optional sources may fail independently; cancellation must still stop the parent request. */
internal suspend fun collectExtraInfoItem(
    timeoutMillis: Long,
    collect: suspend () -> String,
): ExtraInfoCollectionResult = collectExtraInfoResult(timeoutMillis) {
    val text = collect().takeIf { it.isNotBlank() }
    ExtraInfoCollectionResult(if (text == null) "empty" else "success", text)
}

internal suspend fun collectExtraInfoResult(
    timeoutMillis: Long,
    collect: suspend () -> ExtraInfoCollectionResult,
): ExtraInfoCollectionResult = withTimeoutOrNull(timeoutMillis) {
    try {
        collect()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ExtraInfoUnavailable) {
        ExtraInfoCollectionResult(error.status())
    } catch (error: Exception) {
        // Never include exception messages: providers may embed screen text, URLs or credentials.
        ExtraInfoCollectionResult("failed:${error.javaClass.simpleName}")
    }
} ?: ExtraInfoCollectionResult("timeout")

/** Only recognition failures may fall back; denied screenshots and cancellation must not. */
internal suspend fun collectScreenTextWithFallback(
    recognize: suspend () -> String,
    visibleText: suspend () -> String,
): ExtraInfoCollectionResult {
    try {
        val text = recognize().trim().take(4_000).takeIf { it.isNotEmpty() }
        return ExtraInfoCollectionResult(if (text == null) "empty" else "success", text)
    } catch (error: ExtraInfoUnavailable) {
        if (error.issue !in setOf(ExtraInfoIssue.OCR_INITIALIZATION, ExtraInfoIssue.OCR_UNAVAILABLE,
                ExtraInfoIssue.OCR_IMAGE, ExtraInfoIssue.OCR_RECOGNITION)) throw error
        val text = try {
            visibleText().trim().take(4_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
        if (text.isEmpty()) throw error
        return ExtraInfoCollectionResult(
            status = error.status("fallback"),
            text = "${error.issue.label}；以下改用同一窗口的可见界面文字，可能不包含图片内文字：\n$text".take(4_000),
        )
    }
}
