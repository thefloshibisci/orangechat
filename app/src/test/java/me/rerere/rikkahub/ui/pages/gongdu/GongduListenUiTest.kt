package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.data.listen.*
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlinx.coroutines.CompletableDeferred

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GongduListenUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptyRoomHasRealActionAndNoInventedSong() {
        var finds = 0
        compose.setContent { Themed(false) {
            ListenPlayer(ListenState(), Modifier.fillMaxSize(), { finds++ }, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("把一首歌，分成两份").assertIsDisplayed()
        compose.onNodeWithText("挑一首歌").performClick()
        assertEquals(1, finds)
        compose.onNodeWithContentDescription("播放").assertDoesNotExist()
        snapshot("listen-empty-light")
    }

    @Test fun darkLargeTextPlayerKeepsActionsAndSeeksSelectedLyric() {
        var toggles = 0
        var seek = -1L
        val song = ListenSong("123", "一首很长名字的合成测试歌曲", "合成测试歌手", durationMs = 180_000)
        val state = ListenState(
            queue = listOf(ListenQueueItem("queue-1", song, "assistant", "这首送给今晚的你")),
            currentIndex = 0, playing = true, positionMs = 20_000, durationMs = 180_000,
            lyrics = listOf(ListenLyric(0, "第一行合成歌词"), ListenLyric(30_000, "第二行合成歌词")),
        )
        compose.setContent { Themed(true, 1.3f) {
            ListenPlayer(state, Modifier.fillMaxSize(), {}, { toggles++ }, {}, {}, { seek = it }, {}, {}, {})
        } }
        compose.onNodeWithText(song.title).assertIsDisplayed()
        snapshot("listen-player-dark-large")
        compose.onNodeWithTag("listen-player").performScrollToNode(hasContentDescription("暂停"))
        compose.onNodeWithContentDescription("暂停").performClick()
        assertEquals(1, toggles)
        compose.onNodeWithTag("listen-player").performScrollToNode(hasText("第二行合成歌词"))
        compose.onNodeWithText("第二行合成歌词").performClick()
        assertEquals(30_000L, seek)
        snapshot("listen-lyrics-dark-large")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun connectionSeparatesAccountAndAssistantConsentOnCompactScreen() {
        var saved = false
        var authorized: Boolean? = null
        var qrRequested = false
        val config = ListenConfig("https://music.example.com", "test-token")
        compose.setContent { Themed(false) {
            ListenConnection(config, "chat-1", "assistant-1", false,
                ListenAccount(false), null, "", { url, token ->
                    saved = url == config.baseUrl && token == config.token
                }, { authorized = it }, {}, { qrRequested = true }, {}, {})
        } }
        compose.onNodeWithText("保存并连接").performScrollTo().performClick()
        assertTrue(saved)
        compose.onNodeWithContentDescription("让当前助手陪我听").performScrollTo().assertIsOff().performClick()
        assertEquals(true, authorized)
        compose.onNodeWithText("扫码登录").performScrollTo().performClick()
        assertTrue(qrRequested)
        snapshot("listen-connection-compact")
    }

    @Test fun listeningDoorIsAvailable() {
        assertTrue(GongduRooms.single { it.id == "listen" }.enabled)
        assertTrue(GongduRooms.single { it.id == "read" }.enabled)
        assertEquals("0:00", listenTime(-100))
        assertEquals("3:07", listenTime(187_000))
    }

    @Test fun companionConsentCanBeRevokedDuringNetworkWork() {
        var authorized: Boolean? = null
        val config = ListenConfig("https://music.example.com", "test-token", "chat-1", "assistant-1")
        compose.setContent { Themed(false) {
            ListenConnection(config, "chat-1", "assistant-1", true, ListenAccount(false), null, "",
                { _, _ -> }, { authorized = it }, {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("让当前助手陪我听").performScrollTo().assertIsOn().assertIsEnabled().performClick()
        assertEquals(false, authorized)
        compose.onNodeWithText("保存并连接").assertIsNotEnabled()
    }

    @Test fun pauseWorksWhileAnUnrelatedNetworkRequestIsPending() {
        val request = CompletableDeferred<Unit>()
        var pauses = 0
        lateinit var actions: ListenRoomActions
        val state = ListenState(queue = listOf(ListenQueueItem("queue-1", ListenSong("123", "测试歌曲", "测试歌手"))),
            currentIndex = 0, playing = true)
        compose.setContent { Themed(false) {
            val scope = rememberCoroutineScope()
            actions = remember { ListenRoomActions(scope, {}, { throw it }) }
            LaunchedEffect(Unit) { actions.action { request.await() } }
            ListenPlayer(state, Modifier.fillMaxSize(), {}, { actions.transport { pauses++ } }, {}, {}, {}, {}, {}, {})
        } }
        compose.runOnIdle { assertTrue(actions.busy) }
        compose.onNodeWithTag("listen-player").performScrollToNode(hasContentDescription("暂停"))
        compose.onNodeWithContentDescription("暂停").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, pauses); assertTrue(actions.busy); request.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(actions.busy) }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun noteEditorKeepsSeparatorsAndMultipleEntries() {
        var notes by mutableStateOf(ListenNotes())
        compose.setContent { Themed(false, 1.3f) {
            ListenNotesEditor(ListenSong("123", "合成测试歌曲", "测试歌手"), notes, true,
                { notes = it }, {}, {})
        } }
        val lines = compose.onNodeWithTag("listen-note-lines")
        lines.performScrollTo().performTextInput("第一句\n")
        lines.assertTextContains("第一句\n")
        lines.performTextInput("第二句")
        compose.runOnIdle { assertEquals(listOf("第一句", "第二句"), notes.favoriteLines) }
        val tags = compose.onNodeWithTag("listen-note-tags")
        tags.performScrollTo().performTextInput("夜晚，")
        tags.assertTextContains("夜晚，")
        tags.performTextInput("安静")
        compose.runOnIdle { assertEquals(listOf("夜晚", "安静"), notes.tags) }
        snapshot("listen-notes-compact-large")
        compose.onNodeWithText("保存手记").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("带回聊天").performScrollTo().assertIsEnabled()
    }

    @Test fun failedNotesOfferRetryWithoutSavingEmptyContent() {
        var retries = 0
        compose.setContent { Themed(false) {
            ListenNotesEditor(ListenSong("123", "合成测试歌曲", "测试歌手"), ListenNotes(), false,
                {}, {}, {}, onRetry = { retries++ })
        } }
        compose.onNodeWithText("重试读取手记").performScrollTo().performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("保存手记").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("带回聊天").performScrollTo().assertIsNotEnabled()
    }

    @Composable
    private fun Themed(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDarkMode provides dark, LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme { ListenTheme { Surface { Box(Modifier.fillMaxSize()) { content() } } } }
        }
    }

    private fun snapshot(name: String) {
        val output = File("build/reports/tests/gongdu-listen-screenshots/$name.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
