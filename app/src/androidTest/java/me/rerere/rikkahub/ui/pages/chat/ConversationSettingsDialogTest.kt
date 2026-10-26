package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSettingsDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun invalidRequestCannotSaveAndValidEditKeepsOpeningSnapshot() {
        val original = Assistant(name = "Role", temperature = 0.5f)
        val current = mutableStateOf(original)
        var saved: Pair<Assistant, Assistant>? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                ConversationSettingsDialog(
                    conversation = Conversation(assistantId = original.id, messageNodes = emptyList()),
                    assistant = current.value,
                    onSave = { before, after -> saved = before to after },
                    onReset = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithText("温度，留空使用接口默认").performScrollTo().performTextReplacement("NaN")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.runOnIdle { current.value = original.copy(name = "Concurrent role edit") }
        compose.onNodeWithText("温度，留空使用接口默认").performScrollTo().performTextReplacement("1.2")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(original, saved?.first)
            assertEquals(1.2f, saved?.second?.temperature)
            assertTrue(dismissed)
        }
    }

    @Test fun restoringDefaultsRequiresConfirmationAndCancelWritesNothing() {
        val assistant = Assistant()
        var reset = 0
        var saved: Assistant? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                ConversationSettingsDialog(
                    conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList()),
                    assistant = assistant,
                    onSave = { _, desired -> saved = desired },
                    onReset = { reset++ },
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithText("恢复助手默认").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, reset); assertFalse(dismissed) }
        compose.onNodeWithText("恢复").performClick()
        compose.runOnIdle {
            assertEquals(1, reset)
            assertNull(saved)
            assertTrue(dismissed)
        }
    }
}
