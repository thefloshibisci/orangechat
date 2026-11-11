package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.reading.ReadSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w320dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadTurnOptionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val effects = listOf("slide", "cover", "curl", "vertical")

    @Test fun compactDayResetsBodyToTwentyAndSelectsEveryPageEffect() {
        var settings by mutableStateOf(ReadSettings(fontSize = 25, mode = "scroll"))
        compose.setContent { Appearance(settings) { settings = it } }
        compose.onNodeWithTag("read-font-default").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(20, settings.fontSize)
        compose.onNodeWithText("正文字号 · 20").assertIsDisplayed()
        effects.forEach { compose.onNodeWithTag("read-turn-$it").assertIsNotSelected() }
        effects.forEach { effect ->
            compose.onNodeWithTag("read-turn-$effect").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("page", settings.mode)
            assertEquals(effect, settings.pageTurn)
            assertEquals(20, settings.fontSize)
            assertSelectedEffect(effect)
        }
        compose.onNodeWithTag("read-turn-curl").performScrollTo().performClick()
        compose.onNodeWithTag("read-turn-options").performScrollTo()
        compose.waitForIdle()
        assertTwoRows()
        snapshot("read-turn-controls-compact-day")
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun largeTextNightKeepsSpreadWhenSelectingEffects() {
        var settings by mutableStateOf(ReadSettings(theme = "bluecard-night", fontSize = 25, mode = "spread"))
        compose.setContent { Appearance(settings, 1.3f) { settings = it } }
        compose.onNodeWithTag("read-font-default").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(20, settings.fontSize)
        effects.forEach { effect ->
            compose.onNodeWithTag("read-turn-$effect").performScrollTo().assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertEquals("spread", settings.mode)
            assertEquals("bluecard-night", settings.theme)
            assertEquals(20, settings.fontSize)
            assertSelectedEffect(effect)
        }
        compose.onNodeWithTag("read-turn-options").performScrollTo()
        compose.waitForIdle()
        assertTwoRows()
        snapshot("read-turn-controls-night-large")
    }

    private fun assertSelectedEffect(selected: String) {
        effects.forEach { effect ->
            val option = compose.onNodeWithTag("read-turn-$effect")
            if (effect == selected) option.assertIsSelected() else option.assertIsNotSelected()
        }
    }

    private fun assertTwoRows() {
        val bounds = effects.map { compose.onNodeWithTag("read-turn-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].top, bounds[1].top, 1f)
        assertEquals(bounds[2].top, bounds[3].top, 1f)
        assertTrue(bounds[2].top > bounds[0].bottom)
        assertTrue(bounds.all { it.width > 0f && it.height > 0f })
    }

    @Composable private fun Appearance(settings: ReadSettings, fontScale: Float = 1f, onChange: (ReadSettings) -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme { ReadTheme(settings) {
                Surface { Box(Modifier.fillMaxSize().padding(16.dp)) { ReadAppearance(settings, onChange) } }
            } }
        }
    }

    private fun snapshot(name: String) {
        val output = File("build/reports/tests/gongdu-read-screenshots/$name.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
