package me.rerere.rikkahub.ui.components.message

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.BubbleStickerCorner
import me.rerere.rikkahub.data.datastore.BubbleFrameSetting
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.pages.chat.chatBottomFade
import me.rerere.rikkahub.ui.pages.chat.rememberChatBackgroundVisuals
import me.rerere.rikkahub.ui.pages.setting.components.BubbleFrameSettingsCard
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleFrameUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun stickerStaysAboveCornerWithoutStretchingWithBubble() {
        val file = makeFrame()
        var width by mutableStateOf(96.dp)
        var height by mutableStateOf(44.dp)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.White).padding(30.dp)) {
                    BubbleFrame(BubbleFrameSetting(path = file.absolutePath, corner = BubbleStickerCorner.TOP_LEFT), Modifier.testTag("frame")) {
                        Box(Modifier.size(width, height).background(Color.Blue).testTag("content"))
                    }
                }
            }
        }
        compose.waitUntil(5000) { framePixel(2) == android.graphics.Color.RED }
        for (size in listOf(96.dp to 44.dp, 180.dp to 120.dp)) {
            compose.runOnIdle { width = size.first; height = size.second }
            compose.onNodeWithTag("content").assertWidthIsEqualTo(size.first).assertHeightIsEqualTo(size.second)
            compose.onNodeWithTag("frame").assertWidthIsEqualTo(size.first).assertHeightIsEqualTo(size.second + 16.dp)
            assertEquals(android.graphics.Color.RED, framePixel(2))
            assertEquals(android.graphics.Color.BLUE, framePixel(28))
            assertEquals(android.graphics.Color.BLUE, framePixel(40))
        }
        snapshot("custom-bubble-frame.png")
    }

    @Test fun stickerOffsetsKeepBubbleWidthAndReserveOnlyVerticalOverflow() {
        val file = makeFrame()
        var frame by mutableStateOf(BubbleFrameSetting(file.absolutePath, scale = 1.5f,
            corner = BubbleStickerCorner.TOP_LEFT))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(48.dp)) {
                    BubbleFrame(frame, Modifier.testTag("frame")) {
                        Box(Modifier.size(140.dp, 60.dp).testTag("content"))
                    }
                }
            }
        }
        for (corner in BubbleStickerCorner.entries.filter { it != BubbleStickerCorner.AUTO }) {
            compose.runOnIdle { frame = frame.copy(corner = corner, offsetX = 24f, offsetY = 0f) }
            compose.onNodeWithTag("frame").assertWidthIsEqualTo(140.dp).assertHeightIsEqualTo(84.dp)
            val outer = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
            val inner = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
            assertEquals(outer.left, inner.left, 0.1f)
            assertEquals(outer.right, inner.right, 0.1f)
        }
        compose.runOnIdle { frame = frame.copy(corner = BubbleStickerCorner.TOP_LEFT, offsetY = -6f) }
        compose.onNodeWithTag("frame").assertHeightIsEqualTo(90.dp)
        compose.runOnIdle { frame = frame.copy(offsetY = 24f) }
        compose.onNodeWithTag("frame").assertHeightIsEqualTo(60.dp)
    }

    @Test fun settingsResetAndClearRetainIndependentSidesAndRoundTrip() {
        val file = makeFrame()
        var settings = DisplaySetting(userBubbleFrame = BubbleFrameSetting(file.absolutePath, 5f, -4f, 1.2f),
            assistantBubbleFrame = BubbleFrameSetting(file.absolutePath, -3f, 2f, 1.1f))
        val assistant = settings.assistantBubbleFrame
        compose.setContent {
            var frame by remember { mutableStateOf(settings.userBubbleFrame) }
            MaterialTheme {
                CompositionLocalProvider(LocalDisplaySettings provides settings) {
                    androidx.compose.foundation.lazy.LazyColumn {
                        item { BubbleFrameSettingsCard(true, frame) { frame = it; settings = settings.copy(userBubbleFrame = it) } }
                    }
                }
            }
        }
        compose.waitUntil(5000) { previewContainsFrame() }
        snapshot("bubble-frame-settings.png")
        compose.onNodeWithText("重置").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(BubbleFrameSetting(file.absolutePath), settings.userBubbleFrame) }
        compose.onNodeWithText("清除").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(BubbleFrameSetting(), settings.userBubbleFrame)
            assertEquals(assistant, settings.assistantBubbleFrame)
            assertEquals(settings, Json.decodeFromString<DisplaySetting>(Json.encodeToString(settings)))
            assertEquals(BubbleFrameSetting(), Json.decodeFromString<DisplaySetting>("{}").userBubbleFrame)
        }
    }

    @Test fun wallpaperTintTracksDarkModeOnly() {
        var dark by mutableStateOf(false)
        var alphas = 1f to 1f
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDarkMode provides dark) {
                    val visuals = rememberChatBackgroundVisuals(Settings())
                    SideEffect { alphas = visuals.gradientTopAlpha to visuals.gradientBottomAlpha }
                }
            }
        }
        compose.runOnIdle { assertEquals(0f to 0f, alphas); dark = true }
        compose.runOnIdle { assertEquals(0.2f to 0.5f, alphas) }
    }

    @Test fun bottomFadeLeavesContentUntouchedAndCoversBottomEdge() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDarkMode provides false) {
                    Box(Modifier.size(200.dp).chatBottomFade().background(Color.Blue).testTag("frame"))
                }
            }
        }
        assertEquals(android.graphics.Color.BLUE, framePixel(4))
        val width = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInWindow.width.toInt()
        val pixel = framePixel(width - 2)
        assertTrue(android.graphics.Color.red(pixel) in 180..230)
        assertTrue(android.graphics.Color.green(pixel) in 180..230)
        snapshot("chat-bottom-fade.png")
    }

    private fun makeFrame(): File {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(android.graphics.Color.RED)
        return File(compose.activity.cacheDir, "frame.png").also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun framePixel(inset: Int): Int {
        val bounds = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInWindow
        var pixel = 0
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val origin = IntArray(2).also(view::getLocationInWindow)
            pixel = bitmap.getPixel((bounds.left - origin[0]).toInt() + inset, (bounds.top - origin[1]).toInt() + inset)
            bitmap.recycle()
        }
        return pixel
    }

    private fun previewContainsFrame(): Boolean {
        var visible = false
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            visible = pixels.any { it == android.graphics.Color.RED }
            bitmap.recycle()
        }
        return visible
    }

    private fun snapshot(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("build/test-screenshots/$name")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
