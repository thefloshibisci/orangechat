package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageQueuePanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entry = QueuedMessage(parts = listOf(UIMessagePart.Text("queued source")))
    private var state by mutableStateOf(MessageQueueState(messages = listOf(entry)))
    private val finished = mutableListOf<Pair<Uuid, List<UIMessagePart>?>>()
    private val removed = mutableListOf<Uuid>()
    private var pauses = 0
    private var resumes = 0
    private var visible by mutableStateOf(true)

    @Test fun onlyNonemptyQueueShowsCountAndLifetimeNote() {
        state = MessageQueueState()
        show()
        compose.onNodeWithTag("chat_message_queue").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(messages = listOf(entry)) }
        compose.onNodeWithText("排队消息 · 1 条").assertIsDisplayed()
        compose.onNodeWithText("待发消息暂存于本次运行，进程结束后不保留。").assertIsDisplayed()
    }

    @Test fun pauseAndResumeDoNotRemoveQueuedMessage() {
        show()
        compose.onNodeWithText("暂停队列").performClick()
        compose.onNodeWithText("队列已暂停 · 1 条").assertIsDisplayed()
        compose.onNodeWithText("继续发送").performClick()
        compose.onNodeWithText("排队消息 · 1 条").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, pauses)
            assertEquals(1, resumes)
            assertEquals(listOf(entry), state.messages)
        }
    }

    @Test fun withdrawalTargetsOnlyChosenMessage() {
        val other = QueuedMessage(parts = listOf(UIMessagePart.Text("retained source")))
        state = state.copy(messages = listOf(entry, other))
        show()
        compose.onNodeWithTag("queue_remove_${entry.id}").performClick()
        compose.onNodeWithText("1. retained source").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(entry.id), removed)
            assertEquals(listOf(other), state.messages)
        }
    }

    @Test fun cancelEditReleasesOnceAndPreservesIndependentMainDraft() {
        val mainDraft = ChatInputState().apply { setMessageText("unsent main draft") }
        show()
        compose.onNodeWithTag("queue_edit_${entry.id}").performClick()
        compose.onNodeWithTag("chat_queue_edit_text").performTextReplacement("cancelled edit")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(listOf(entry.id to null), finished)
            assertEquals(entry.parts, state.messages.single().parts)
            assertEquals("unsent main draft", mainDraft.textContent.text.toString())
            mainDraft.releaseDraft()
        }
    }

    @Test fun savingEditPreservesMultipartOrderAndDoesNotAlsoCancel() {
        val parts = listOf(
            UIMessagePart.Text("first text"), UIMessagePart.Image("file:///queued.png"),
            UIMessagePart.Text("editable text"), UIMessagePart.VoiceMessage("file:///queued.wav", 123, "transcript"),
        )
        state = state.copy(messages = listOf(entry.copy(parts = parts)))
        show()
        compose.onNodeWithTag("queue_edit_${entry.id}").performClick()
        compose.onNodeWithTag("chat_queue_edit_text").performTextReplacement("new text")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            val expected = parts.toMutableList().apply { this[2] = UIMessagePart.Text("new text") }
            assertEquals(listOf(entry.id to expected), finished)
            assertEquals(expected, state.messages.single().parts)
            assertFalse(state.messages.single().isEditing)
        }
    }

    @Test fun editorCannotSaveEmptyPayloadAndRemovingAttachmentChangesOnlyDraft() {
        val image = UIMessagePart.Image("file:///queued-only.png")
        state = state.copy(messages = listOf(entry.copy(parts = listOf(image))))
        show()
        compose.onNodeWithTag("queue_edit_${entry.id}").performClick()
        compose.onNodeWithTag("queue_attachment_remove_0").performClick()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(image), state.messages.single().parts) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(listOf(entry.id to null), finished) }
    }

    @Test fun leavingPageReleasesEditReservationOnce() {
        show()
        compose.onNodeWithTag("queue_edit_${entry.id}").performClick()
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertEquals(listOf(entry.id to null), finished) }
    }

    @Test fun externallyDiscardedQueueClosesEditorAndReleasesReservation() {
        show()
        compose.onNodeWithTag("queue_edit_${entry.id}").performClick()
        compose.runOnIdle { state = state.copy(messages = emptyList()) }
        compose.onNodeWithText("编辑排队消息").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(entry.id to null), finished) }
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                if (visible) MessageQueuePanel(
                    state = state,
                    onRemove = { id -> removed += id; state = state.copy(messages = state.messages.filterNot { it.id == id }) },
                    onBeginEdit = { id ->
                        val previous = state.messages.find { it.id == id }
                        state = state.copy(messages = state.messages.map { if (it.id == id) it.copy(isEditing = true) else it })
                        previous
                    },
                    onFinishEdit = { id, parts ->
                        finished += id to parts
                        state = state.copy(messages = state.messages.map {
                            if (it.id == id) it.copy(parts = parts ?: it.parts, isEditing = false) else it
                        })
                    },
                    onPause = { pauses++; state = state.copy(paused = true) },
                    onResume = { resumes++; state = state.copy(paused = false) },
                )
            }
        }
    }
}
