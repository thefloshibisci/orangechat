package me.rerere.rikkahub.data.listen

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ListenCoreTest {
    private val song = ListenSong("123", "共听", "歌手", durationMs = 180000)

    @Test fun restoringQueueNeverClaimsAudioIsAlreadyPlaying() {
        val saved = ListenState(queue = listOf(ListenQueueItem("a", song)), currentIndex = 0,
            playing = true, loading = true, lyrics = listOf(ListenLyric(0, "歌词")), error = "old", positionMs = 32000)
        val restored = Json.decodeFromString<ListenState>(Json.encodeToString(saved)).forRestore()
        assertEquals(32000L, restored.positionMs)
        assertFalse(restored.playing)
        assertFalse(restored.loading)
        assertTrue(restored.lyrics.isEmpty())
        assertNull(restored.error)
    }

    @Test fun repeatOnlyAffectsNaturalEndAndShuffleDoesNotRepeatCurrent() {
        val queue = (0..2).map { ListenQueueItem("$it", song) }
        val state = ListenState(queue = queue, currentIndex = 1, mode = "repeat")
        assertEquals(1, nextListenIndex(state, automatic = true))
        assertEquals(2, nextListenIndex(state, automatic = false))
        repeat(30) { assertNotEquals(1, nextListenIndex(state.copy(mode = "shuffle"))) }
        assertEquals(-1, nextListenIndex(ListenState()))
    }

    @Test fun assistantPointingSongWhileStoppedWaitsForUserAndCannotCrossChats() = fixture { repo ->
        repo.saveConfiguration("https://music.example.test", "test-token")
        repo.authorize("chat-a", "assistant-a", true)
        repo.playForAssistant(song, "chat-a", "assistant-a", "这一首给你")
        assertEquals("chat-a", repo.state.value.conversationId)
        assertEquals("assistant", repo.state.value.current?.origin)
        assertFalse(repo.state.value.playing)
        assertFalse(repo.state.value.loading)
        assertEquals("", repo.nowPlayingContext("chat-a", "assistant-a"))
        assertTrue(runCatching { repo.enqueueForAssistant(song, "chat-b", "assistant-a") }.isFailure)
        repo.authorize("chat-a", "assistant-a", false)
        assertTrue(runCatching { repo.playForAssistant(song, "chat-a", "assistant-a") }.isFailure)
        assertEquals(1, repo.state.value.queue.size)
    }

    @Test fun explicitUserSwitchStartsFreshQueueAndRevokesPreviousCompanion() = fixture { repo ->
        repo.saveConfiguration("https://music.example.test", "test-token")
        repo.authorize("chat-a", "assistant-a", true)
        repo.enqueueForAssistant(song, "chat-a", "assistant-a", message = "private message")
        repo.enqueue(song.copy(id = "456"), conversationId = "chat-b")
        assertEquals("chat-b", repo.state.value.conversationId)
        assertEquals(listOf("456"), repo.state.value.queue.map { it.song.id })
        assertFalse(repo.isAuthorized("chat-a", "assistant-a"))
        assertFalse(repo.isAuthorized("chat-b", "assistant-a"))
    }

    @Test fun enablingAnotherChatCannotSilentlyExposeOrTransferExistingQueue() = fixture { repo ->
        repo.saveConfiguration("https://music.example.test", "test-token")
        repo.authorize("chat-a", "assistant-a", true)
        repo.enqueueForAssistant(song, "chat-a", "assistant-a")
        assertTrue(runCatching { repo.authorize("chat-b", "assistant-b", true) }.isFailure)
        assertTrue(repo.isAuthorized("chat-a", "assistant-a"))
        assertFalse(repo.isAuthorized("chat-b", "assistant-b"))
        // A stale switch in another chat cannot revoke the currently active binding.
        repo.authorize("chat-b", "assistant-b", false)
        assertTrue(repo.isAuthorized("chat-a", "assistant-a"))
        assertEquals("chat-a", repo.state.value.conversationId)
        repo.clear()
        repo.authorize("chat-b", "assistant-b", true)
        assertTrue(repo.isAuthorized("chat-b", "assistant-b"))
        assertEquals("chat-b", repo.state.value.conversationId)
    }

    @Test fun removingAnotherQueueItemKeepsSelectedIdentityAndConfigChangesDropQueue() = fixture { repo ->
        repo.saveConfiguration("https://music.example.test", "test-token")
        repo.authorize("chat-a", "assistant-a", true)
        repo.enqueueForAssistant(song.copy(id = "111"), "chat-a", "assistant-a")
        repo.playForAssistant(song, "chat-a", "assistant-a")
        val selected = repo.state.value.current!!.id
        val other = repo.state.value.queue.first { it.id != selected }.id
        repo.remove(other)
        assertEquals(selected, repo.state.value.current!!.id)
        repo.saveConfiguration("https://another.example.test", "new-token")
        assertTrue(repo.state.value.queue.isEmpty())
        assertFalse(repo.isAuthorized("chat-a", "assistant-a"))
        assertFalse(repo.config.value.toString().contains("new-token"))
    }

    @Test fun endpointValidationRejectsCredentialsQueryAndCleartext() = fixture { repo ->
        for (url in listOf("http://music.example.test", "https://user:password@music.example.test", "https://music.example.test?token=secret")) {
            assertTrue(runCatching { repo.saveConfiguration(url, "test-token") }.isFailure)
        }
        assertTrue(runCatching { repo.saveConfiguration("https://music.example.test", "bad\nheader") }.isFailure)
    }

    @Test fun delayedOldSourceCannotReplaceNewAssistantSelection() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = delayedSourceClient(started, release)
        fixture(client) { repo ->
            repo.saveConfiguration("https://music.example.test", "test-token")
            repo.authorize("chat-a", "assistant-a", true)
            coroutineScope {
                val previous = async(Dispatchers.Default) { runCatching { repo.play(song, conversationId = "chat-a") } }
                try {
                    assertTrue("old URL lookup started", started.await(5, TimeUnit.SECONDS))
                    repo.playForAssistant(song.copy(id = "456"), "chat-a", "assistant-a")
                } finally { release.countDown() }
                previous.await().getOrThrow()
            }
            assertEquals("456", repo.state.value.current?.song?.id)
            assertFalse(repo.state.value.playing)
            assertFalse(repo.state.value.loading)
        }
    }

    @Test fun changingConfigurationDuringUrlLookupCannotStartOldAudio() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture(delayedSourceClient(started, release)) { repo ->
            repo.saveConfiguration("https://music.example.test", "test-token")
            coroutineScope {
                val previous = async(Dispatchers.Default) { runCatching { repo.play(song, conversationId = "chat-a") } }
                try {
                    assertTrue("old URL lookup started", started.await(5, TimeUnit.SECONDS))
                    repo.saveConfiguration("https://other.example.test", "new-token")
                } finally { release.countDown() }
                assertTrue(previous.await().isFailure)
            }
            assertTrue(repo.state.value.queue.isEmpty())
            assertFalse(repo.state.value.playing)
            assertFalse(repo.state.value.loading)
        }
    }

    @Test fun sourceClientUsesBearerAndNormalizedSongContract() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("gongdu_listen", Context.MODE_PRIVATE).edit().clear().commit()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer private-token", chain.request().header("Authorization"))
            assertEquals("/api/listen/search", chain.request().url.encodedPath)
            assertEquals("歌名", chain.request().url.queryParameter("q"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"ok":true,"songs":[{"id":"123","title":"共听","artist":"歌手"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = ListenRepository(context, client)
        try {
            repo.saveConfiguration("https://music.example.test", "private-token")
            assertEquals("共听", repo.search("歌名").single().title)
        } finally {
            repo.close()
            Dispatchers.resetMain()
        }
    }

    private fun delayedSourceClient(started: CountDownLatch, release: CountDownLatch): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/api/listen/url", chain.request().url.encodedPath)
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "source release timeout" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"url":"https://cdn.example.test/track.mp3"}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()

    private fun fixture(
        client: OkHttpClient = OkHttpClient.Builder().addInterceptor { error("Unexpected network call") }.build(),
        block: suspend (ListenRepository) -> Unit,
    ) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("gongdu_listen", Context.MODE_PRIVATE).edit().clear().commit()
        val repo = ListenRepository(context, client)
        try { block(repo) } finally { repo.close(); Dispatchers.resetMain() }
    }
}
