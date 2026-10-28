package me.rerere.rikkahub.utils

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadRoutesTest {
    private val name = "OrangeChat-2.2.6-arm64-liquidglass.apk"
    private val source = "${ProjectLinks.RELEASES}/download/2.2.6/$name"
    private val download = UpdateDownload(name, source, "45.4 MB", digest = "sha256:" + "a1".repeat(32))

    @Test fun `routes preserve official asset with a fixed https mirror and official fallback`() {
        val routes = updateDownloadRoutes(download)
        assertEquals(listOf("gh-proxy", "direct"), routes.map { it.id })
        assertEquals(listOf("加速线路1", "GitHub直连"), routes.map { it.label })
        assertEquals(listOf("https://gh-proxy.com/$source", source),
            routes.map { it.url })
        assertEquals(source, download.url)
    }

    @Test fun `missing or malformed official digest permits direct transport only`() {
        listOf(null, "", "sha256:abc", "sha256:" + "a".repeat(63), "sha256:" + "g".repeat(64),
            "SHA256:" + "a".repeat(64), "sha256:" + "a".repeat(64) + " ").forEach { digest ->
            assertEquals(listOf("direct"), updateDownloadRoutes(download.copy(digest = digest)).map { it.id })
        }
        assertEquals(2, updateDownloadRoutes(download.copy(digest = "sha256:" + "AB".repeat(32))).size)
    }

    @Test fun `other hosts forks assets and prewrapped mirror urls cannot obtain a route`() {
        listOf(
            source.replace("github.com", "github.com.evil.invalid"),
            source.replace("thefloshibisci/orangechat", "another/fork"),
            source.replace("https:", "http:"),
            "$source?redirect=elsewhere",
            "https://gh-proxy.com/$source",
            source.replace(name, "other.apk"),
        ).forEach { url -> assertTrue(updateDownloadRoutes(download.copy(url = url)).isEmpty()) }
    }

    @Test fun `http error explains the unavailable connection without inventing a 590 meaning`() {
        assertEquals("当前下载线路暂不可用（HTTP 590），请重试或切换线路", downloadFailureMessage(590))
        assertTrue(downloadFailureMessage(503).contains("503"))
        assertTrue(downloadFailureMessage(404).contains("404"))
        assertFalse(downloadFailureMessage(590).contains("空间"))
        assertEquals("下载未完成，请重试或切换线路", downloadFailureMessage(12345))
    }

    @Test fun `storage failures have actionable local messages rather than a network diagnosis`() {
        assertEquals("存储空间不足，请清理后重新下载",
            downloadFailureMessage(DownloadManager.ERROR_INSUFFICIENT_SPACE))
        assertEquals("下载位置暂不可用，请稍后重试",
            downloadFailureMessage(DownloadManager.ERROR_DEVICE_NOT_FOUND))
        assertFalse(downloadFailureMessage(DownloadManager.ERROR_FILE_ERROR).contains("线路"))
    }

    @Test fun `stalls require pending or running with no forward progress for the full deadline`() {
        for (status in listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING)) {
            assertFalse(isUpdateDownloadStalled(status, 50, 50, UPDATE_DOWNLOAD_STALL_MS - 1, 0))
            assertTrue(isUpdateDownloadStalled(status, 50, 50, UPDATE_DOWNLOAD_STALL_MS, 0))
            assertFalse(isUpdateDownloadStalled(status, 51, 50, UPDATE_DOWNLOAD_STALL_MS, 0))
            assertFalse(isUpdateDownloadStalled(status, 50, 50, 9, 10))
        }
    }

    @Test fun `paused successful and failed tasks cannot be mistaken for running stalls`() {
        for (status in listOf(DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_SUCCESSFUL,
            DownloadManager.STATUS_FAILED)) {
            assertFalse(isUpdateDownloadStalled(status, 0, 0, UPDATE_DOWNLOAD_STALL_MS * 2, 0))
        }
    }

    @Test fun `network pause excludes retry backoff and does not apply to running tasks`() {
        assertTrue(isUpdateDownloadWaitingForNetwork(
            DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_WAITING_FOR_NETWORK))
        assertTrue(isUpdateDownloadWaitingForNetwork(
            DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_QUEUED_FOR_WIFI))
        assertFalse(isUpdateDownloadWaitingForNetwork(
            DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_WAITING_TO_RETRY))
        assertFalse(isUpdateDownloadWaitingForNetwork(
            DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_UNKNOWN))
        assertFalse(isUpdateDownloadWaitingForNetwork(
            DownloadManager.STATUS_RUNNING, DownloadManager.PAUSED_WAITING_FOR_NETWORK))
        assertFalse(isUpdateDownloadWaitingForNetwork(
            DownloadManager.STATUS_FAILED, DownloadManager.PAUSED_QUEUED_FOR_WIFI))
    }
}
