package me.rerere.rikkahub.ui.components.message

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.interaction.ChatInteraction
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "zh-rCN-w420dp-h1100dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentCardFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private fun card(kind: String = "transfer", status: String = "pending", theme: String = "lavender") =
        ChatInteraction("sent-$kind", "c", "a", "user", kind, status = status, theme = theme,
            amountCents = 5200, note = "送你一个小心意", connectedAtMillis = 1000, endedAtMillis = 5000)

    @Test fun sentPaymentAndCallCardsHideActionsInEverydayAndRestoreWorkingActionsInStandard() {
        var kind by mutableStateOf("red_packet")
        var mode by mutableStateOf(ChatDisplayMode.STANDARD)
        var regenerations = 0
        var shares = 0
        compose.setContent { MaterialTheme {
            val display = DisplaySetting(chatDisplayMode = mode, userNickname = "严槿", showUserAvatar = false)
            CompositionLocalProvider(LocalDisplaySettings provides display, LocalSettings provides Settings(displaySetting = display)) {
                val message = remember(kind) { UIMessage.user("卡片内容：$kind").copy(annotations = listOf(card(kind, if (kind == "call") "ended" else "pending"))) }
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    ChatMessage(MessageNode(messages = listOf(message)), onFork = {}, onRegenerate = { regenerations++ },
                        onEdit = {}, onShare = { shares++ }, onDelete = {}, onUpdate = {})
                }
            }
        } }
        for (type in listOf("red_packet", "transfer", "call")) {
            for (displayMode in listOf(ChatDisplayMode.STANDARD, ChatDisplayMode.EVERYDAY, ChatDisplayMode.STANDARD)) {
                compose.runOnIdle { kind = type; mode = displayMode }
                if (displayMode == ChatDisplayMode.EVERYDAY) {
                    compose.onNodeWithContentDescription(label(R.string.copy)).assertDoesNotExist()
                    compose.onNodeWithContentDescription(label(R.string.regenerate)).assertDoesNotExist()
                    compose.onNodeWithContentDescription(label(R.string.more_options)).assertDoesNotExist()
                    capture("sent-$type-everyday")
                    continue
                }
                compose.onNodeWithContentDescription(label(R.string.copy)).assertIsDisplayed().performClick()
                compose.runOnIdle {
                    val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains(type))
                }
                val previous = regenerations
                compose.onNodeWithContentDescription(label(R.string.regenerate)).assertIsDisplayed().performClick()
                assertEquals(previous, regenerations)
                compose.onNodeWithText(label(R.string.confirm)).performClick()
                assertEquals(previous + 1, regenerations)
                capture("sent-$type-${displayMode.name.lowercase()}")
                compose.onNodeWithContentDescription(label(R.string.more_options)).assertIsDisplayed().performClick()
                compose.onNodeWithText(label(R.string.share)).performClick()
                assertTrue(shares > 0)
            }
        }
        assertEquals(6, regenerations)
        assertEquals(6, shares)
    }

    @Test fun allTransferStatesUseTheSuppliedRabbitInBothBubbleAndDetail() {
        var state by mutableStateOf("pending")
        var theme by mutableStateOf("ice")
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().background(Color(0xFFF5F5FA)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val transfer = card(status = state, theme = theme)
                GiftChatBubble(transfer, state, "严槿") {}
                GiftDetailCard(transfer, state, false, false, null, "严槿") {}
            }
        } }
        for (palette in listOf("ice", "sakura", "lavender")) for ((status, description) in listOf(
            "pending" to "微笑兔 · 转账", "accepted" to "比心兔 · 已收款", "returned" to "挤眼兔 · 已退回")) {
            compose.runOnIdle { theme = palette; state = status }
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription(description).assertCountEquals(2)
            capture("bunny-$palette-$status")
        }
    }

    @Test fun onlyNewReceiptOfMyGiftBouncesAndScrollingDoesNotReplayIt() {
        var gift by mutableStateOf(card("red_packet"))
        compose.setContent { MaterialTheme {
            LazyColumn(Modifier.fillMaxWidth().height(480.dp).testTag("list"), state = rememberLazyListState()) {
                item(key = "gift") { GiftChatBubble(gift, gift.status, "严槿") {} }
                items(35) { index -> Text("下面的消息$index", Modifier.height(80.dp)) }
            }
        } }
        for (kind in listOf("red_packet", "transfer")) {
            compose.runOnIdle { gift = card(kind) }
            val tag = if (kind == "red_packet") "packet-bubble" else "transfer-bubble"
            fun scale() = compose.onNodeWithTag(tag).fetchSemanticsNode().config[GlassScale]
            assertEquals(1f, scale(), .001f)
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { gift = gift.copy(status = "accepted") }
            val scales = mutableListOf<Float>()
            repeat(40) { frame ->
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                scales += scale()
                if (frame in listOf(5, 13, 30)) capture("receipt-$kind-frame-$frame")
            }
            assertTrue("Receipt has a squash frame", scales.any { it < .99f })
            assertTrue("Receipt has an elastic overshoot", scales.any { it > 1.02f })
            assertEquals(1f, scales.last(), .001f)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("list").performScrollToIndex(30)
            compose.onNodeWithTag(tag).assertDoesNotExist()
            compose.onNodeWithTag("list").performScrollToIndex(0)
            compose.mainClock.autoAdvance = false
            repeat(35) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                assertEquals("Re-entering view cannot replay a receipt", 1f, scale(), .001f)
            }
            compose.onNodeWithTag(tag).performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(120); compose.waitForIdle()
            assertEquals("A finger down cannot start a pop", 1f, scale(), .001f)
            compose.onNodeWithTag(tag).performTouchInput { cancel() }
            compose.runOnIdle { gift = gift.copy(note = "重新组合也不弹") }
            compose.mainClock.advanceTimeBy(160); compose.waitForIdle()
            assertEquals(1f, scale(), .001f)
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun receivedGiftsAndHistoricalAcceptedGiftsNeverStartTheSenderBounce() {
        var gift by mutableStateOf(card().copy(actor = "assistant"))
        compose.setContent { MaterialTheme { GiftChatBubble(gift, gift.status, "南枳") {} } }
        compose.mainClock.autoAdvance = false
        for (updated in listOf(gift.copy(status = "accepted"), card(status = "accepted").copy(id = "history"))) {
            compose.runOnIdle { gift = updated }
            repeat(35) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                assertEquals(1f, compose.onNodeWithTag("transfer-bubble").fetchSemanticsNode().config[GlassScale], .001f)
            }
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun bunnyExpressionsKeepTheirThemePaletteAndWhiteFace() {
        compose.setContent { MaterialTheme {
            Column(Modifier.padding(20.dp).background(Color(0xFFF3F3F8))) {
                for (theme in listOf("ice", "sakura", "lavender")) Row {
                    for (status in listOf("pending", "accepted", "returned")) TransferBunnyBadge(card(status = status, theme = theme), 96.dp)
                }
            }
        } }
        capture("bunny-expressions-three-themes")
    }

    private fun capture(name: String) {
        val file = File("build/reports/chat-interaction/$name.png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
