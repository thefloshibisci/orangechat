package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.supportsDirectAudio
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceInputButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun modelSwitchKeepsEntryAndExplainsMissingTranscriptionWithoutRecording() {
        var model by mutableStateOf(Model(modelId = "gemini-2.5-flash"))
        var transcriptionReady by mutableStateOf(false)
        var recordings = 0
        val reasons = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                val direct = model.supportsDirectAudio(ProviderSetting.OpenAI())
                VoiceInputButton(ASRState(isAvailable = direct || transcriptionReady), direct,
                    false, { reasons.add(it) }, { recordings++ })
            }
        }
        val originalBounds = compose.onNodeWithContentDescription("录音直传（最长 5 分钟）")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("录音直传（最长 5 分钟）").performClick()
        compose.runOnIdle { model = Model(modelId = "text-model") }
        val transcription = compose.onNodeWithContentDescription("语音转写").assertIsDisplayed()
        assertEquals(originalBounds, transcription.fetchSemanticsNode().boundsInRoot)
        transcription.performClick()
        compose.runOnIdle {
            assertEquals(1, recordings)
            assertTrue(reasons.single().contains("语音识别服务"))
            transcriptionReady = true
        }
        transcription.performClick()
        compose.runOnIdle { model = Model(modelId = "gemini-2.5-flash") }
        compose.onNodeWithContentDescription("录音直传（最长 5 分钟）").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(3, recordings) }
    }

    @Test fun activeCallExplainsConflictAndHangupRestoresRecording() {
        var callActive by mutableStateOf(true)
        var recordings = 0
        val reasons = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                VoiceInputButton(ASRState(isAvailable = true), false, callActive,
                    { reasons.add(it) }, { recordings++ })
            }
        }
        val button = compose.onNodeWithContentDescription("语音转写").assertIsDisplayed()
        button.performClick()
        compose.runOnIdle {
            assertEquals(0, recordings)
            assertTrue(reasons.single().contains("挂断"))
            callActive = false
        }
        button.performClick()
        compose.runOnIdle { assertEquals(1, recordings) }
    }

    @Test fun ongoingRecordingCanStillStopWhenAvailabilityChanges() {
        var stops = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.testTag("recording")) {
                    VoiceInputButton(ASRState(status = ASRStatus.Listening), false, false,
                        { fail("Must allow stopping an active recording") }, { stops++ })
                }
            }
        }
        compose.onNodeWithTag("recording").assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
}
