package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationSettingsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `invalid input blocks save and corrected value preserves opening assistant snapshot`() {
        val assistant = Assistant(name = "Role", temperature = 0.5f, systemPrompt = "Keep role")
        val chat = Conversation(assistantId = assistant.id, messageNodes = emptyList())
        var saved: Pair<Assistant, Assistant>? = null
        var dismissed = false
        compose.setContent { MaterialTheme {
            ConversationSettingsDialog(chat, assistant, { before, after -> saved = before to after }, {}, { dismissed = true })
        } }
        val temperature = compose.onNodeWithText("温度，留空使用接口默认")
        temperature.performScrollTo().performTextReplacement("NaN")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.runOnIdle { assertNull(saved); assertFalse(dismissed) }
        temperature.performScrollTo().performTextReplacement("0.8")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(assistant, saved?.first)
            assertEquals(0.8f, saved?.second?.temperature)
            assertEquals("Keep role", saved?.second?.systemPrompt)
            assertTrue(dismissed)
        }
    }

    @Test fun `cancelling a changed draft leaves session untouched`() {
        val assistant = Assistant(temperature = 0.5f)
        val chat = Conversation(assistantId = assistant.id, messageNodes = emptyList())
        var saved = false
        var reset = false
        var dismissed = false
        compose.setContent { MaterialTheme {
            ConversationSettingsDialog(chat, assistant, { _, _ -> saved = true }, { reset = true }, { dismissed = true })
        } }
        compose.onNodeWithText("温度，留空使用接口默认").performScrollTo().performTextReplacement("1.2")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertFalse(saved); assertFalse(reset); assertTrue(dismissed) }
    }

    @Test fun `reset requires explicit confirmation before restoring assistant defaults`() {
        val assistant = Assistant()
        val chat = Conversation(assistantId = assistant.id, messageNodes = emptyList())
        var saved = false
        var reset = false
        var dismissed = false
        compose.setContent { MaterialTheme {
            ConversationSettingsDialog(chat, assistant, { _, _ -> saved = true }, { reset = true }, { dismissed = true })
        } }
        compose.onNodeWithText("恢复助手默认").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(reset); assertFalse(dismissed) }
        compose.onNodeWithText("恢复").performClick()
        compose.runOnIdle { assertTrue(reset); assertTrue(dismissed); assertFalse(saved) }
    }
}
