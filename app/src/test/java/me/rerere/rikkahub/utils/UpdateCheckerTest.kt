package me.rerere.rikkahub.utils

import android.app.Application
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class UpdateCheckerTest {
    @Test fun `checks prompt once per version and manual check reopens without downloading`() = runBlocking {
        val calls = AtomicInteger()
        val channel = when (BuildConfig.APPLICATION_ID) {
            "me.rerere.orangechat.liquidglass" -> "Liquidglass"
            "me.rerere.orangechat.debug" -> "Debug"
            else -> "Release"
        }
        val name = "OrangeChat-$channel-99.0.0-universal.apk"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body("""{"tag_name":"99.0.0","published_at":"2026-10-27T00:00:00Z","body":"notes","assets":[{"name":"$name","size":123,"browser_download_url":"${ProjectLinks.RELEASES}/download/99.0.0/$name"}]}""".toResponseBody())
                .build()
        }.build()
        val checker = UpdateChecker(client, RuntimeEnvironment.getApplication(), AppScope())
        val states = checker.checkUpdate().toList()
        assertTrue(states.first() is UiState.Loading)
        assertTrue(states.last() is UiState.Success)
        assertEquals("99.0.0", checker.prompt.value?.version)
        assertEquals(UpdateDownloadState.Idle, checker.downloadState.value)
        checker.dismissPrompt()
        checker.checkUpdate().toList()
        assertNull(checker.prompt.value)
        assertEquals(1, calls.get())
        checker.checkUpdate(force = true).toList()
        assertEquals("99.0.0", checker.prompt.value?.version)
        assertEquals(2, calls.get())
        assertEquals(UpdateDownloadState.Idle, checker.downloadState.value)
    }

    @Test fun `identity validation rejects other channels signers downgrades and missing signatures`() {
        verifyUpdateIdentity("a", "a", 1, 2, setOf("signer"), setOf("signer"))
        val invalid = listOf(
            { verifyUpdateIdentity("a", "b", 1, 2, setOf("signer"), setOf("signer")) },
            { verifyUpdateIdentity("a", "a", 2, 2, setOf("signer"), setOf("signer")) },
            { verifyUpdateIdentity("a", "a", 3, 2, setOf("signer"), setOf("signer")) },
            { verifyUpdateIdentity("a", "a", 1, 2, setOf("signer"), setOf("other")) },
            { verifyUpdateIdentity("a", "a", 1, 2, emptySet(), emptySet()) },
        )
        invalid.forEach { assertNotNull(runCatching(it).exceptionOrNull()) }
    }

    @Test fun `old downloaded release cannot be installed under a newer prompt`() {
        val previous = UpdateDownload("old.apk", "https://example.invalid/old.apk", "1 MB")
        val newer = UpdateDownload("new.apk", "https://example.invalid/new.apk", "2 MB")
        val info = UpdateInfo("99.0.0", "", "", listOf(newer))
        val ready = UpdateDownloadState.Ready(previous, "/cache/old.apk")
        assertEquals(UpdateDownloadState.Idle, updateDownloadForPrompt(info, ready))
        assertEquals(ready, updateDownloadForPrompt(info.copy(downloads = listOf(previous)), ready))
        val pending = UpdateDownloadState.Downloading(previous, 5, 100)
        assertEquals(pending, updateDownloadForPrompt(info, pending))
    }

    @Test fun `abi selection excludes incompatible architecture and unnamed variants`() {
        assertTrue(projectUpdateAssetMatchesAbi("OrangeChat-release-arm64.apk", listOf("arm64-v8a")))
        assertTrue(projectUpdateAssetMatchesAbi("app-arm64-v8a-release.apk", listOf("arm64-v8a")))
        assertTrue(projectUpdateAssetMatchesAbi("app-universal-release.apk", listOf("x86_64")))
        assertFalse(projectUpdateAssetMatchesAbi("app-x86_64-release.apk", listOf("arm64-v8a")))
        assertFalse(projectUpdateAssetMatchesAbi("app-arm64-release.apk", listOf("armeabi-v7a")))
        assertFalse(projectUpdateAssetMatchesAbi("app-release.apk", listOf("arm64-v8a")))
        val name = "OrangeChat-Liquidglass-99.0.0-arm64.apk"
        val release = GithubRelease("99.0.0", "2026-10-27T00:00:00Z", assets = listOf(
            GithubAsset(name, 123, "${ProjectLinks.RELEASES}/download/99.0.0/$name", "sha256:abc"),
        ))
        val download = selectProjectUpdate(release, "me.rerere.orangechat.liquidglass", listOf("arm64-v8a")).downloads.single()
        assertEquals(123L, download.sizeBytes)
        assertEquals("sha256:abc", download.digest)
        assertNotNull(runCatching { selectProjectUpdate(release, "me.rerere.orangechat.liquidglass", listOf("x86")) }.exceptionOrNull())
    }
}
