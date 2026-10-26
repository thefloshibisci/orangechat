package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import me.rerere.rikkahub.R
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
class ChatSendButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun generationWithDraftSendsAndLongPressStoresWithoutStopping() {
        var sends = 0
        var stores = 0
        var stops = 0
        compose.setContent {
            MaterialTheme { ChatSendButton(true, false, { sends++ }, { stores++ }, { stops++ }) }
        }
        val send = compose.onNodeWithContentDescription(compose.activity.getString(R.string.send))
        send.performClick()
        send.performSemanticsAction(SemanticsActions.OnLongClick) { it.invoke() }
        compose.runOnIdle {
            assertEquals(1, sends)
            assertEquals(1, stores)
            assertEquals(0, stops)
        }
    }

    @Test fun generationWithoutDraftKeepsExplicitStopAction() {
        var stops = 0
        compose.setContent {
            MaterialTheme { ChatSendButton(true, true, { fail("Must stop") }, { fail("Must stop") }, { stops++ }) }
        }
        val stop = compose.onNodeWithContentDescription(compose.activity.getString(R.string.stop))
        stop.performClick()
        stop.performSemanticsAction(SemanticsActions.OnLongClick) { it.invoke() }
        compose.runOnIdle { assertEquals(2, stops) }
    }

    @Test fun idleEmptyInputCannotSend() {
        compose.setContent {
            MaterialTheme { ChatSendButton(false, true, { fail("Empty send") }, { fail("Empty store") }, { fail("Idle stop") }) }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.send)).assertIsNotEnabled()
    }

    @Test fun typingDraftKeepsRunningReplyScreenLeaseUntilGenerationEnds() {
        var empty by mutableStateOf(true)
        var loading by mutableStateOf(true)
        compose.setContent { MaterialTheme { ChatSendButton(loading, empty, {}, {}, {}) } }
        fun keepsScreenOn() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        compose.runOnIdle { assertTrue(keepsScreenOn()); empty = false }
        compose.runOnIdle { assertTrue(keepsScreenOn()); loading = false }
        compose.runOnIdle { assertFalse(keepsScreenOn()) }
    }
}
