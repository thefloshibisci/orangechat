package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactSummaryDialogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun compactControlsKeepSendSelectionAndSaveBehavior() {
        val summary = ConversationSummary(text = "测试摘要内容", group = Uuid.random())
        val chat = Conversation(assistantId = Uuid.random(), messageNodes = emptyList(),
            compressionSummaries = listOf(summary), sendCompressedSummary = true)
        var saved: List<ConversationSummary>? = null
        compose.setContent {
            MaterialTheme {
                ConversationSummaryDialog(chat, {}, { parts, _, _ -> saved = parts }, { parts, _, _, _ -> parts })
            }
        }
        val send = compose.activity.getString(R.string.compression_send_summary)
        val split = compose.activity.getString(R.string.compression_split_mode)
        val select = compose.activity.getString(R.string.compression_round_send, 1)
        val merge = compose.activity.getString(R.string.compression_merge_existing)
        compose.onNodeWithContentDescription(send).assertIsOn()
        val first = compose.onNodeWithContentDescription(send).getUnclippedBoundsInRoot()
        val second = compose.onNodeWithContentDescription(split).getUnclippedBoundsInRoot()
        assertTrue("Switch rows must be adjacent", second.top.value - first.top.value <= 40.5f)
        val selectedRow = compose.onNodeWithContentDescription(select)
        val input = compose.onNode(hasSetTextAction())
        assertEquals(input.getUnclippedBoundsInRoot().left.value,
            selectedRow.getUnclippedBoundsInRoot().left.value, 0.5f)
        compose.onNodeWithText(merge).performScrollTo().assertIsEnabled()
        val output = File("build/test-screenshots/compact-summary.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        selectedRow.performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText(merge).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_page_save)).performClick()
        compose.waitUntil(5000) { saved != null }
        assertFalse(saved!!.single().sendToModel)
        assertEquals(summary.text, saved!!.single().text)
    }
}
