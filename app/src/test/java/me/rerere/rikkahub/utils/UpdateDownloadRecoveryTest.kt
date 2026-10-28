package me.rerere.rikkahub.utils

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.UUID

/** Exercises the real system-download observer, without HTTP calls or replacing its dispatcher. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class UpdateDownloadRecoveryTest {
    private lateinit var context: Context
    private lateinit var manager: DownloadManager
    private lateinit var shadow: ShadowDownloadManager
    private lateinit var preferences: SharedPreferences
    private val scopes = mutableListOf<AppScope>()
    private val files = mutableListOf<File>()
    private val checkers = mutableListOf<UpdateChecker>()
    private val observedRequests = linkedMapOf<Long, ShadowDownloadManager.ShadowRequest>()

    @Before fun open() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        shadow = Shadow.extract(manager)
        ShadowDownloadManager.reset()
        preferences = context.getSharedPreferences("app_update_download", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @After fun close() = runBlocking {
        if (checkers.any { it.downloadState.value is UpdateDownloadState.Failed }) {
            System.err.println(downloadDiagnostics())
        }
        scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
        preferences.edit().clear().commit()
        files.forEach { it.delete() }
        ShadowDownloadManager.reset()
    }

    @Test fun verifiedMetadataStartsOnAccelerationAndRepeatedTapDoesNotEnqueueAgain() {
        val download = download()
        val route = updateDownloadRoutes(download).first()
        val checker = checker()

        checker.downloadUpdate(context, download)
        val id = currentId()
        checker.downloadUpdate(context, download)

        assertTrue(id >= 0)
        assertEquals(1, shadow.requestCount)
        assertEquals(route.url, request(id).uri.toString())
        assertEquals(route.id, preferences.getString("route", null))
        assertEquals(setOf(route.id), preferences.getStringSet("attempted", null))
        // Persist the canonical publisher metadata, rather than trusting a mirror as the source.
        assertEquals(download, Json.decodeFromString<UpdateDownload>(preferences.getString("item", null)!!))
        assertEquals(download, (checker.downloadState.value as UpdateDownloadState.Downloading).download)
    }

    @Test fun failedRoutesAreTriedOnceAndOnlyTheirOwnRecordsAreRemoved() = runBlocking {
        val unrelated = manager.enqueue(DownloadManager.Request("https://example.invalid/other.bin".toUri()))
        val download = download()
        val routes = updateDownloadRoutes(download)
        val checker = checker()
        checker.downloadUpdate(context, download)

        val attemptedIds = mutableSetOf<Long>()
        routes.forEachIndexed { index, route ->
            val id = currentId()
            assertTrue(attemptedIds.add(id))
            assertEquals(route.url, request(id).uri.toString())
            request(id).status = DownloadManager.STATUS_FAILED
            if (index < routes.lastIndex) {
                awaitNewId(id)
                assertNull(shadow.getRequest(id))
            } else {
                awaitCondition { checker.downloadState.value is UpdateDownloadState.Failed }
            }
        }

        assertEquals(routes.size, attemptedIds.size)
        assertEquals(-1L, currentId())
        assertEquals(1, shadow.requestCount)
        assertNotNull(shadow.getRequest(unrelated))
    }

    @Test fun missingDigestNeverSendsTheDownloadToAnAccelerationService() = runBlocking {
        val download = download().copy(digest = null)
        val checker = checker()
        checker.downloadUpdate(context, download)
        val id = currentId()
        assertEquals(download.url, request(id).uri.toString())
        request(id).status = DownloadManager.STATUS_FAILED

        awaitCondition { checker.downloadState.value is UpdateDownloadState.Failed }
        assertEquals(downloadDiagnostics(), 0, shadow.requestCount)
        assertEquals(-1L, currentId())
    }

    @Test fun manualOfficialDownloadDoesNotFallBackToThirdPartyTransport() = runBlocking {
        val download = download()
        val checker = checker()
        checker.downloadUpdate(context, download, preferDirect = true)
        val id = currentId()
        assertEquals(download.url, request(id).uri.toString())
        assertTrue(preferences.getBoolean("directOnly", false))
        request(id).status = DownloadManager.STATUS_FAILED

        awaitCondition { checker.downloadState.value is UpdateDownloadState.Failed }
        assertEquals(downloadDiagnostics(), 0, shadow.requestCount)
    }

    @Test fun processRecreationObservesTheExistingRouteWithoutEnqueuingAnotherTask() = runBlocking {
        val download = download()
        val originalScope = scope()
        val original = checker(originalScope)
        original.downloadUpdate(context, download)
        val id = currentId()
        request(id).status = DownloadManager.STATUS_RUNNING
        originalScope.coroutineContext[Job]!!.cancelAndJoin()

        val restored = checker()
        awaitCondition { restored.downloadState.value is UpdateDownloadState.Downloading }
        assertEquals(id, currentId())
        assertEquals(1, shadow.requestCount)
        val route = updateDownloadRoutes(download).first()
        assertEquals(route.url, request(id).uri.toString())
        assertEquals(route.label, (restored.downloadState.value as UpdateDownloadState.Downloading).routeLabel)
    }

    @Test fun legacySavedOfficialTaskContinuesWithAnUntriedAccelerationRouteAfterFailure() = runBlocking {
        val download = download()
        val id = manager.enqueue(DownloadManager.Request(download.url.toUri())
            .setDestinationUri(fixtureFile(download.name).toURI().toString().toUri()))
        preferences.edit().putLong("id", id).putString("item", Json.encodeToString(download)).commit()
        request(id).status = DownloadManager.STATUS_FAILED

        checker()
        val nextId = awaitNewId(id)
        val mirror = updateDownloadRoutes(download).first { it.id != "direct" }
        assertEquals(mirror.url, request(nextId).uri.toString())
        assertEquals(setOf("direct", mirror.id), preferences.getStringSet("attempted", null))
        assertNull(shadow.getRequest(id))
    }

    @Test fun runningWatchdogResetsOnForwardProgressAndThenSwitchesAfterAStall() = runBlocking {
        val download = download()
        val checker = checker()
        checker.downloadUpdate(context, download)
        val id = currentId()
        request(id).apply {
            status = DownloadManager.STATUS_RUNNING
            bytesSoFar = 1
            totalSize = download.sizeBytes
        }
        awaitCondition { (checker.downloadState.value as? UpdateDownloadState.Downloading)?.received == 1L }
        ShadowSystemClock.advanceBy(Duration.ofMillis(20_000))
        request(id).bytesSoFar = 2
        awaitCondition { (checker.downloadState.value as? UpdateDownloadState.Downloading)?.received == 2L }
        ShadowSystemClock.advanceBy(Duration.ofMillis(6_000))
        delay(900) // Cross the first sample's deadline, while the newer progress is still fresh.
        assertEquals(id, currentId())
        assertEquals(1, shadow.requestCount)

        ShadowSystemClock.advanceBy(Duration.ofMillis(UPDATE_DOWNLOAD_STALL_MS - 6_000 + 1))
        val nextId = awaitNewId(id)
        assertEquals(updateDownloadRoutes(download)[1].url, request(nextId).uri.toString())
        assertNull(shadow.getRequest(id))
    }

    @Test fun wrongRepositoryAndWrongPackageChannelNeverEnqueueAnUpdate() {
        val good = download()
        val wrongChannelName = if ("liquidglass" in good.name.lowercase()) {
            "OrangeChat-99.0.0-universal-release.apk"
        } else {
            "OrangeChat-99.0.0-universal-liquidglass.apk"
        }
        val checker = checker()
        listOf(
            good.copy(url = good.url.replace(ProjectLinks.REPOSITORY, "someone/another-project")),
            good.copy(name = wrongChannelName, url = "${ProjectLinks.RELEASES}/download/99.0.0/$wrongChannelName"),
        ).forEach {
            checker.downloadUpdate(context, it)
            assertTrue(checker.downloadState.value is UpdateDownloadState.Failed)
        }
        assertEquals(0, shadow.requestCount)
    }

    @Test fun restoredTaskWithMismatchedUriCannotRemoveAnotherDownloadOrItsFile() = runBlocking {
        val download = download()
        val otherFile = fixtureFile("unrelated.apk").apply { writeText("keep this other download") }
        val otherId = manager.enqueue(DownloadManager.Request("https://example.invalid/other.apk".toUri())
            .setDestinationUri(otherFile.toURI().toString().toUri()))
        val route = updateDownloadRoutes(download).first()
        preferences.edit().putLong("id", otherId).putString("item", Json.encodeToString(download))
            .putString("route", route.id).putString("path", otherFile.absolutePath).commit()

        val checker = checker()
        awaitCondition { checker.downloadState.value is UpdateDownloadState.Failed }

        assertNotNull(shadow.getRequest(otherId))
        assertEquals("keep this other download", otherFile.readText())
        assertEquals(1, shadow.requestCount)
        assertEquals(-1L, currentId())
    }

    @Test fun completedResponsesWithWrongDigestNeverBecomeInstallableAndAreDeleted() = runBlocking {
        val invalidBytes = byteArrayOf(1, 2, 3, 4)
        val download = download().copy(sizeBytes = invalidBytes.size.toLong())
        val routes = updateDownloadRoutes(download)
        val checker = checker()
        checker.downloadUpdate(context, download)
        val rejectedFiles = mutableListOf<File>()
        routes.forEachIndexed { index, _ ->
            val id = currentId()
            val file = File(java.net.URI(request(id).destination.toString())).also { files += it; rejectedFiles += it }
            file.writeBytes(invalidBytes)
            request(id).status = DownloadManager.STATUS_SUCCESSFUL
            if (index < routes.lastIndex) awaitNewId(id)
            else awaitCondition { checker.downloadState.value is UpdateDownloadState.Failed }
            assertFalse(checker.downloadState.value is UpdateDownloadState.Ready)
        }
        assertTrue(rejectedFiles.all { !it.exists() })
        assertEquals(0, shadow.requestCount)
    }

    private fun scope() = AppScope().also { scopes += it }
    private fun checker(activeScope: AppScope = scope()) =
        UpdateChecker(OkHttpClient(), context, activeScope).also { checkers += it }
    private fun currentId() = preferences.getLong("id", -1L)
    private fun request(id: Long): ShadowDownloadManager.ShadowRequest =
        Shadow.extract<ShadowDownloadManager.ShadowRequest>(
            checkNotNull(shadow.getRequest(id)) { "Missing system download $id" }
        ).also { observedRequests[id] = it }

    private suspend fun awaitNewId(previous: Long): Long {
        awaitCondition { currentId() >= 0 && currentId() != previous }
        return currentId()
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) delay(20) }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(downloadDiagnostics(), timeout)
        }
    }

    private fun downloadDiagnostics(): String = buildString {
        append("states=").append(checkers.map { it.downloadState.value })
        append("; preferences=").append(preferences.all)
        append("; updatesDirectory=").append(
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").canonicalPath
        )
        observedRequests.forEach { (id, request) ->
            append("; request[").append(id).append("]=").append(request.uri)
            append(" destination=").append(request.destination)
            append(" path=").append(request.destination?.path)
            append(" canonical=").append(request.destination?.path?.let { File(it).canonicalPath })
            append(" status=").append(request.status)
        }
    }

    private fun fixtureFile(name: String): File {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
        check(directory.isDirectory || directory.mkdirs())
        return File(directory, "${UUID.randomUUID()}-$name").also { files += it }
    }

    private fun download(): UpdateDownload {
        val channel = when (context.packageName) {
            "me.rerere.orangechat.liquidglass" -> "liquidglass"
            "me.rerere.orangechat.debug" -> "debug"
            "me.rerere.orangechat" -> "release"
            else -> error("Unexpected test package ${context.packageName}")
        }
        val name = "OrangeChat-99.0.0-universal-$channel.apk"
        return UpdateDownload(name, "${ProjectLinks.RELEASES}/download/99.0.0/$name", "100 bytes", 100, "sha256:" + "0".repeat(64))
    }
}
