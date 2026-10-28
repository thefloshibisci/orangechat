package me.rerere.rikkahub.utils

import android.app.DownloadManager

internal data class UpdateDownloadRoute(
    val id: String,
    val label: String,
    val url: String,
)

/** Alternate transports never change the release asset or relax its source validation. */
internal fun updateDownloadRoutes(download: UpdateDownload): List<UpdateDownloadRoute> {
    if (!isTrustedProjectDownload(download.url, download.name)) return emptyList()
    val direct = UpdateDownloadRoute("direct", "GitHub直连", download.url)
    // A third-party transport is allowed only when official metadata pins the exact file.
    if (download.digest?.matches(Regex("sha256:[0-9a-fA-F]{64}")) != true) return listOf(direct)
    return listOf(
        UpdateDownloadRoute("gh-proxy", "加速线路1", "https://gh-proxy.com/${download.url}"),
        direct,
    )
}

internal fun downloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请清理后重新下载"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载位置暂不可用，请稍后重试"
    DownloadManager.ERROR_FILE_ERROR -> "无法保存安装包，请检查存储空间后重试"
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "下载文件已存在，请重新尝试"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "当前下载线路跳转异常，请重试或切换线路"
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "当前下载线路响应异常，请重试或切换线路"
    DownloadManager.ERROR_HTTP_DATA_ERROR -> "下载连接中断，请重试或切换线路"
    DownloadManager.ERROR_CANNOT_RESUME -> "下载已中断，请重新下载"
    in 400..599 -> "当前下载线路暂不可用（HTTP $reason），请重试或切换线路"
    else -> "下载未完成，请重试或切换线路"
}

internal const val UPDATE_DOWNLOAD_STALL_MS = 25_000L

internal fun isUpdateDownloadWaitingForNetwork(status: Int, reason: Int): Boolean =
    status == DownloadManager.STATUS_PAUSED && reason in setOf(
        DownloadManager.PAUSED_WAITING_FOR_NETWORK,
        DownloadManager.PAUSED_QUEUED_FOR_WIFI,
    )

/** The observer handles paused/offline downloads separately and records forward progress. */
internal fun isUpdateDownloadStalled(
    status: Int,
    received: Long,
    lastReceived: Long,
    nowMs: Long,
    lastProgressMs: Long,
): Boolean = (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING) &&
    received <= lastReceived && nowMs >= lastProgressMs && nowMs - lastProgressMs >= UPDATE_DOWNLOAD_STALL_MS
