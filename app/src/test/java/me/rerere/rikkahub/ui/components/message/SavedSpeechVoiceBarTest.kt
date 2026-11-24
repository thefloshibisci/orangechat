package me.rerere.rikkahub.ui.components.message

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w375dp-h812dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedSpeechVoiceBarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Playback : SavedSpeechPlayback {
        var taps = 0
        override val state = MutableStateFlow(SavedSpeechPlaybackState(durationMs = 19500))
        override fun toggle() { taps++; state.value = state.value.copy(playing = !state.value.playing) }
        override fun close() {}
    }

    @Test fun `voice starts silent and only the tapped message changes state`() {
        val first = Playback(); val second = Playback()
        compose.setContent { MaterialTheme {
            Column(Modifier.width(300.dp)) {
                SavedSpeechVoiceBar(listOf(UIMessagePart.Audio("file:///one.mp3")), false, playbackFactory = { _, _ -> first })
                SavedSpeechVoiceBar(listOf(UIMessagePart.Audio("file:///two.mp3")), false, playbackFactory = { _, _ -> second })
            }
        } }
        compose.onAllNodesWithContentDescription("播放已保存的语音").assertCountEquals(2)
        assertEquals(0, first.taps); assertEquals(0, second.taps)
        compose.onAllNodesWithContentDescription("播放已保存的语音")[0].performClick()
        compose.onNodeWithContentDescription("暂停语音").assertExists()
        assertEquals(1, first.taps); assertEquals(0, second.taps)
        compose.onNodeWithContentDescription("暂停语音").performClick()
        compose.onAllNodesWithText("0:20").assertCountEquals(2)
        snapshot("two-messages")
    }

    @Test fun `unknown legacy audio never offers a hidden generation button`() {
        compose.setContent { MaterialTheme {
            SavedSpeechVoiceBar(emptyList(), false)
        } }
        compose.onNodeWithContentDescription("播放已保存的语音").assertIsNotEnabled()
        compose.onNodeWithText("—:—").assertIsDisplayed()
        compose.onNodeWithText("这条旧语音未保存音频，无法重播。重新生成可能计费。").assertIsDisplayed()
        snapshot("legacy")
    }

    @Test fun `small dark view supports large text without guessing duration`() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Column(Modifier.width(280.dp)) {
                    SavedSpeechVoiceBar(listOf(UIMessagePart.Audio("file:///one.mp3")), false, playbackFactory = { _, _ -> Playback() })
                    SavedSpeechVoiceBar(emptyList(), false)
                }
            }
        } }
        compose.onNodeWithText("0:20").assertIsDisplayed()
        compose.onNodeWithText("—:—").assertIsDisplayed()
        snapshot("dark-large-font")
    }

    @Test @Config(qualifiers = "w812dp-h375dp-land") fun `landscape loading has no playable action`() {
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) {
            Column(Modifier.width(350.dp)) { SavedSpeechVoiceBar(emptyList(), true) }
        } }
        compose.onNodeWithText("语音生成中，完成后点击播放").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("播放已保存的语音").assertCountEquals(0)
        snapshot("landscape-loading")
    }

    private fun snapshot(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val file = File("build/reports/tests/saved-speech-screenshots/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
