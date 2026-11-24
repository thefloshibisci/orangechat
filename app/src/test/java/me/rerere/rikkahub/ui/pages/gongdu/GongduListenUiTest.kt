package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.listen.*
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
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
        compose.onNodeWithTag("listen-player-disc").assertWidthIsAtLeast(280.dp)
        assertPlayerEdgeControlsAligned()
        snapshot("listen-empty-light")
    }

    @Test fun centralPickerTintsIdlePlayerAndPreservesCoverAfterCancelOrDecodeFailure() {
        val display = RecordDisplayState()
        val idleTint = display.current.palette.recordTint
        var state by mutableStateOf(ListenState())
        compose.setContent { Themed(false) {
            ListenPlayer(state, Modifier.fillMaxSize(), {}, {}, {}, {}, {}, {}, {}, {}, recordDisplay = display)
        } }
        fun pick(result: Int, uri: Uri? = null) {
            compose.onNodeWithTag("listen-disc-cover").assertWidthIsAtLeast(48.dp).performClick()
            val launch = requireNotNull(shadowOf(compose.activity).nextStartedActivityForResult)
            compose.runOnIdle {
                assertTrue(compose.activity.activityResultRegistry.dispatchResult(
                    launch.requestCode, result, uri?.let { Intent().setData(it) },
                ))
            }
        }
        val custom = coverFile("picked-cover", 0xFFB65B73.toInt())
        fun shown(description: String) = compose.onAllNodesWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        fun coverErrorShown() = compose.onAllNodesWithText("没能打开这张图片，请重新选择")
            .fetchSemanticsNodes().isNotEmpty()
        pick(Activity.RESULT_OK, Uri.parse(custom.toURI().toString()))
        compose.waitUntil(10000) { shown("自选唱片封面") || coverErrorShown() }
        assertFalse("valid selection decoded", display.coverError)
        compose.runOnIdle {
            assertNotEquals(idleTint, display.current.palette.recordTint)
            assertTrue(requireNotNull(display.current.bitmap).width <= 512)
        }
        compose.onNodeWithContentDescription("找歌并播放").assertIsDisplayed()
        snapshot("listen-custom-light")
        val local = display.current
        pick(Activity.RESULT_CANCELED)
        assertEquals(local, display.current)
        pick(Activity.RESULT_OK, Uri.parse(File(custom.parentFile, "missing.png").toURI().toString()))
        compose.waitUntil(10000) { coverErrorShown() }
        compose.onNodeWithText("没能打开这张图片，请重新选择").assertIsDisplayed()
        assertEquals(local, display.current)

        val album = coverFile("current-album", 0xFF479FB9.toInt()).absolutePath
        compose.runOnIdle {
            state = ListenState(queue = listOf(ListenQueueItem("test", ListenSong("123", "与你听海", "海岸来信", cover = album))),
                currentIndex = 0, playing = true, durationMs = 236000, positionMs = 85000,
                lyrics = listOf(ListenLyric(85000, "让时间停在这片海"), ListenLyric(90000, "与你相伴，不必赶路")))
        }
        compose.waitUntil(10000) { shown("专辑封面") }
        compose.onNodeWithTag("listen-disc-cover").assertDoesNotExist()
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
        snapshot("listen-playing-light")
        compose.runOnIdle { state = ListenState() }
        compose.waitUntil(10000) { shown("自选唱片封面") }
        assertEquals(local, display.current)
        compose.onNodeWithTag("listen-disc-cover").assertIsDisplayed()
    }

    @Test fun islandDialogPaintsBothEdgesAndTrackMetadataCannotMoveTheDisc() {
        val file = System.getProperty("listen.reference.cover")?.let(::File) ?: coverFile("island", 0xff479fb9.toInt())
        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
        val display = RecordDisplayState().apply {
            current = RecordArt(file.absolutePath, bitmap, me.rerere.rikkahub.ui.components.message.listen.extractMusicPalette(bitmap))
        }
        val song = ListenSong("one", "海浪、海鸥与你", "AnRain安林", cover = file.absolutePath)
        var state by mutableStateOf(ListenState(queue = listOf(ListenQueueItem("one", song)), currentIndex = 0))
        var notesOpened = false
        compose.setContent {
            ListenIslandDialog(onDismiss = {}) {
                Themed(false) {
                    ListenPlayer(state, Modifier.fillMaxSize(), {}, {}, {}, {}, {}, {}, {}, { notesOpened = true },
                        recordDisplay = display, useSystemInsets = true)
                }
            }
        }
        val disc = compose.onNodeWithTag("listen-player-disc")
        val original = disc.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val host = compose.activity.window.decorView
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            assertEquals(host.height, view.height)
            assertEquals(host.width, view.width)
            val pixels = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(pixels))
            assertEquals(0xffeef2f7.toInt(), pixels.getPixel(view.width / 2, view.height - 1))
            assertNotEquals(0xffeef2f7.toInt(), pixels.getPixel(view.width / 2, 0))
            assertEquals(255, android.graphics.Color.alpha(pixels.getPixel(view.width / 2, 0)))
            pixels.recycle()
            state = state.copy(queue = listOf(ListenQueueItem("two", song.copy(id = "two"))),
                lyrics = listOf(ListenLyric(0, "这一句很长".repeat(12), "翻译换成两行".repeat(10))))
        }
        assertEquals(original, disc.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state = state.copy(lyrics = listOf(ListenLyric(0, "纯音乐，请欣赏"))) }
        assertEquals(original, disc.fetchSemanticsNode().boundsInRoot)
        snapshot("listen-island", dialog = true)
        compose.onNodeWithContentDescription("播放器选项").performClick()
        compose.onNodeWithTag("listen-player-menu").assertIsDisplayed()
        compose.runOnIdle {
            val manager = requireNotNull(ShadowDialog.getLatestDialog().window).windowManager
            val windows = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(manager).views
            val popup = windows.last { it.javaClass.simpleName.contains("PopupLayout") }
            val image = Bitmap.createBitmap(popup.width, popup.height, Bitmap.Config.ARGB_8888)
            popup.draw(Canvas(image))
            System.getProperty("listen.player.screenshots")?.let { directory ->
                File(directory, "listen-menu.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            image.recycle()
        }
        compose.onNodeWithText("写手记").performClick()
        assertTrue(notesOpened)
        compose.onNodeWithTag("listen-player-menu").assertDoesNotExist()
    }

    @Test fun lyricPickerStaysOpaqueWithTransparentInterfaceSurfacesInLightAndDark() {
        var night by mutableStateOf(false)
        var panelColor = 0
        val lyrics = listOf(ListenLyric(0, "窗外的风轻轻吹过"), ListenLyric(3000, "这一刻，想和你一起听"))
        compose.setContent { Themed(night) {
            val scheme = MaterialTheme.colorScheme
            panelColor = scheme.surface.toArgb()
            MaterialTheme(colorScheme = scheme.copy(
                surface = scheme.surface.copy(alpha = .4f),
                surfaceContainerHigh = scheme.surfaceContainerHigh.copy(alpha = .4f),
            )) {
                ListenLyricsPicker(lyrics, onDismiss = {}, onShare = {})
            }
        } }
        for (dark in listOf(false, true)) {
            compose.runOnIdle { night = dark }
            compose.onNodeWithText(lyrics.first().text).assertIsDisplayed()
            snapshot(if (dark) "picker-dark" else "picker-light", dialog = true)
            compose.runOnIdle {
                val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                val center = image.height / 2
                val paper = (image.width / 4 until image.width * 3 / 4).count { image.getPixel(it, center) == panelColor }
                assertTrue("Lyrics must sit on opaque paper", paper > image.width / 4)
                image.recycle()
            }
        }
    }

    @Test fun lyricPickerKeepsSelectionOnRejectedShareAndClosesOnSuccessOrCancel() {
        val lyrics = listOf(ListenLyric(0, "第一句测试歌词"), ListenLyric(3000, "第二句测试歌词"))
        var visible by mutableStateOf(true)
        var accept by mutableStateOf(false)
        val attempts = mutableListOf<List<String>>()
        compose.setContent { Themed(false) {
            if (visible) ListenLyricsPicker(lyrics, onDismiss = { visible = false }) { lines ->
                attempts.add(lines)
                if (accept) visible = false
            }
        } }

        compose.onNodeWithText("第二句测试歌词").performClick()
        compose.onNodeWithText("已选 1 / 8").assertIsDisplayed()
        compose.onNodeWithText("分享到当前聊天").performClick()
        compose.onNodeWithText("已选 1 / 8").assertIsDisplayed()
        assertEquals(listOf(listOf("第二句测试歌词")), attempts)
        compose.runOnIdle { accept = true }
        compose.onNodeWithText("分享到当前聊天").performClick()
        compose.onNodeWithText("选几句，递给对方").assertDoesNotExist()
        assertEquals(listOf(listOf("第二句测试歌词"), listOf("第二句测试歌词")), attempts)

        compose.runOnIdle { visible = true }
        compose.onNodeWithText("已选 0 / 8").assertIsDisplayed()
        compose.onNodeWithText("第一句测试歌词").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("选几句，递给对方").assertDoesNotExist()
        assertEquals(2, attempts.size)
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
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed().performClick()
        assertEquals(1, toggles)
        val transport = compose.onNodeWithTag("listen-transport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("listen-lyrics").assertDoesNotExist()
        compose.onNodeWithTag("listen-disc-lyrics").performClick()
        assertEquals(transport, compose.onNodeWithTag("listen-transport").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("listen-lyrics").performScrollToNode(hasText("第二行合成歌词"))
        compose.onNodeWithText("第二行合成歌词").performClick()
        assertEquals(30_000L, seek)
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一首").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放进度").assertIsDisplayed()
        snapshot("listen-lyrics-dark-large")
        compose.onNodeWithContentDescription("返回唱片").performClick()
        compose.onNodeWithTag("listen-disc-lyrics").assertIsDisplayed()
        assertEquals(transport, compose.onNodeWithTag("listen-transport").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun sharePreviewUsesRealSongAndOnlyExplicitlyAttachedLines() {
        val song = ListenSong("123", "真歌曲标题", "真歌手", cover = coverFile("share-album", 0xFFC47990.toInt()).absolutePath)
        val state = ListenState(queue = listOf(ListenQueueItem("test", song)), currentIndex = 0, positionMs = 2000,
            lyrics = listOf(ListenLyric(0, "已经唱过的词"), ListenLyric(2000, "当前唱到的词"), ListenLyric(3000, "后面的一句")))
        val shared = mutableListOf<ListenShare?>()
        val display = RecordDisplayState()
        var sentSong: ListenSong? = null
        compose.setContent { Themed(false) {
            ListenPlayer(state, Modifier.fillMaxSize(), {}, {}, {}, {}, {}, { shared += parseListenShare(listenShareText(song)) }, {}, {},
                recordDisplay = display, onShareWithLines = { selectedSong, lines ->
                    sentSong = selectedSong
                    shared += parseListenShare(listenShareText(selectedSong, lines))
                })
        } }
        compose.waitUntil(10000) { display.current.bitmap != null }
        compose.onNodeWithText("这首给你").performClick()
        compose.onNodeWithText("分享预览").assertIsDisplayed()
        assertTrue(shared.isEmpty())
        compose.onNodeWithText("附上此刻的歌词").performClick()
        snapshot("listen-share-preview", dialog = true)
        compose.onNodeWithText("分享到当前聊天").performClick()
        assertEquals(listOf("当前唱到的词", "后面的一句"), shared.single()?.lines)
        assertEquals(song, sentSong)
        assertEquals(song.copy(cover = ""), shared.single()?.song)
        compose.onNodeWithText("这首给你").performClick()
        compose.onNodeWithText("分享到当前聊天").performClick()
        assertEquals(emptyList<String>(), shared.last()?.lines)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun longLyricsKeepTransportOnScreenAndCycleModeOnCompactScreen() {
        var mode = ""
        var queue = 0
        val song = ListenSong("123", "合成测试歌曲", "合成测试歌手", durationMs = 180_000)
        val state = ListenState(queue = listOf(ListenQueueItem("queue-1", song)), currentIndex = 0, playing = true,
            positionMs = 90_000, durationMs = 180_000,
            lyrics = (0 until 60).map { ListenLyric(it * 3_000L, "第${it + 1}句合成歌词") })
        compose.setContent { Themed(false) {
            ListenPlayer(state, Modifier.fillMaxSize(), {}, {}, {}, {}, {}, {}, {}, {}, { mode = it }, { queue++ })
        } }
        compose.waitForIdle()
        compose.onNodeWithText("第31句合成歌词").assertIsDisplayed()
        assertPlayerEdgeControlsAligned()
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一首").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放模式：顺序播放").assertIsDisplayed().performClick()
        assertEquals("shuffle", mode)
        compose.onNodeWithContentDescription("待播列表").assertIsDisplayed().performClick()
        assertEquals(1, queue)
        snapshot("listen-player-compact-light")
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
        compose.onNodeWithTag("listen-companion").performScrollTo().assertIsOff().performClick()
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
        compose.onNodeWithTag("listen-companion").performScrollTo().assertIsOn().assertIsEnabled().performClick()
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

    private fun assertPlayerEdgeControlsAligned() {
        val player = compose.onNodeWithTag("listen-player").getUnclippedBoundsInRoot()
        val back = compose.onNodeWithContentDescription("返回共度")
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).getUnclippedBoundsInRoot()
        val menu = compose.onNodeWithContentDescription("播放器选项")
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).getUnclippedBoundsInRoot()
        val mode = compose.onNodeWithContentDescription("播放模式：顺序播放").getUnclippedBoundsInRoot()
        val queue = compose.onNodeWithContentDescription("待播列表").getUnclippedBoundsInRoot()
        assertEquals(8f, (back.left - player.left).value, .5f)
        assertEquals(8f, (player.right - menu.right).value, .5f)
        assertEquals(back.left.value, mode.left.value, .5f)
        assertEquals(menu.right.value, queue.right.value, .5f)
        compose.onNodeWithContentDescription("返回共度", useUnmergedTree = true).assertWidthIsEqualTo(28.dp)
        compose.onNodeWithContentDescription("播放器选项", useUnmergedTree = true).assertWidthIsEqualTo(24.dp)
    }

    @Composable
    private fun Themed(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDarkMode provides dark, LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme { ListenTheme { Surface { Box(Modifier.fillMaxSize()) { content() } } } }
        }
    }

    private fun coverFile(name: String, color: Int) = File(compose.activity.cacheDir, "$name.png").apply {
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun snapshot(name: String, dialog: Boolean = false) {
        val dir = System.getProperty("listen.player.screenshots", "build/reports/tests/gongdu-listen-screenshots")
        val output = File(dir, "$name.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = if (dialog) requireNotNull(ShadowDialog.getLatestDialog()?.window).decorView
                else compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
