package me.rerere.rikkahub.ui.components.message

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import org.junit.Assert.assertTrue
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.interaction.ChatInteraction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlinx.datetime.toJavaLocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w420dp-h1100dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RichChatCardsUiTest {
    @Test fun returnActionHasBreathingRoomAndBusyPaymentHasNoProgressBar() {
        var kind by mutableStateOf("red_packet")
        var busy by mutableStateOf(false)
        var action = ""
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().padding(20.dp)) {
            GiftDetailCard(ChatInteraction("return-spacing", "c", "a", "assistant", kind,
                amountCents = 5200, note = "给你的小心意", theme = "lavender"),
                "待领取", true, busy, null, "南枳") { action = it }
        } } }
        for (type in listOf("red_packet", "transfer")) {
            compose.runOnIdle { kind = type; busy = false }
            val button = if (type == "red_packet") compose.onNodeWithTag("packet-open-button")
                else compose.onNodeWithText("确认收款  ✦")
            val buttonBounds = button.fetchSemanticsNode().boundsInRoot
            val returnBounds = compose.onNodeWithText("退还给对方").fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("Return link must sit below the main button", (returnBounds.top - buttonBounds.bottom) / density >= 20f)
            if (type == "red_packet") {
                val footer = compose.onNodeWithTag("packet-footer").fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertEquals("Refund text must be 10dp above the blessing", 10f,
                    (footer.top - returnBounds.bottom) / density, .5f)
                compose.onNodeWithTag("packet-detail").assertHeightIsEqualTo(400.dp)
            }
            compose.onNodeWithText("退还给对方").performClick()
            org.junit.Assert.assertEquals("return", action)
            capture("gift-return-lower-$type")
            compose.runOnIdle { busy = true }
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
            compose.onNodeWithText(if (type == "transfer") "处理中…" else "…").assertIsDisplayed()
            capture("gift-busy-no-bar-$type")
        }
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun waitingIslandUsesAssistantNameAndOnlyAcceptEndControls() {
        var accepts = 0
        var ends = 0
        compose.setContent { MaterialTheme {
            me.rerere.rikkahub.ui.pages.voice.FloatingCallPill("ice", name = "南枳", incoming = true,
                onAccept = { accepts++ }, onOpen = {}, onEnd = { ends++ })
        } }
        compose.onNodeWithText("南枳 · 等待接通").assertIsDisplayed()
        compose.onNodeWithContentDescription("静音").assertDoesNotExist()
        compose.onNodeWithContentDescription("接听").performClick()
        compose.onNodeWithContentDescription("挂断").performClick()
        org.junit.Assert.assertEquals(1, accepts)
        org.junit.Assert.assertEquals(1, ends)
        capture("flow-waiting-island")
    }

    @Test fun incomingBackAndCollapseDoNotDeclineInvitation() {
        var collapsed = 0
        var declined = 0
        compose.setContent { MaterialTheme {
            me.rerere.rikkahub.ui.pages.voice.IncomingCallPage("test", "南枳", theme = "ice",
                onCollapse = { collapsed++ }, onStartCall = {}, onDecline = { declined++ })
        } }
        capture("flow-incoming")
        compose.onNodeWithText("↙").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        org.junit.Assert.assertEquals(2, collapsed)
        org.junit.Assert.assertEquals(0, declined)
        compose.onNodeWithTag("call-control-拒接").performClick()
        org.junit.Assert.assertEquals(1, declined)
    }

    @Test fun endedCallShowsNamedTimestampedTranscriptAndCloses() {
        var closed = false
        val rows = listOf(me.rerere.ai.ui.CallTranscriptEntry("user", "严槿", "今天想听你唱歌。", 1000),
            me.rerere.ai.ui.CallTranscriptEntry("assistant", "南枳", "好呀，我唱给你听。", 2000))
        compose.setContent { MaterialTheme {
            me.rerere.rikkahub.ui.pages.voice.CallHistoryPage(
                ChatInteraction("history", "c", "a", "assistant", "call", status = "ended", theme = "ice",
                    connectedAtMillis = 1000, endedAtMillis = 63000, callTranscript = rows), "南枳", rows) { closed = true }
        } }
        compose.onNodeWithText("南枳 · 通话记录").assertIsDisplayed()
        compose.onNodeWithText("严槿").assertIsDisplayed()
        compose.onNodeWithText("今天想听你唱歌。").assertIsDisplayed()
        compose.onNodeWithText("好呀，我唱给你听。").assertIsDisplayed()
        compose.onNodeWithText("通话结束 · 01:02").assertIsDisplayed()
        capture("flow-call-history")
        compose.onNodeWithTag("gift-detail-close").performClick()
        assertTrue(closed)
    }

    @Test fun giftComposerUsesThemeAndBottomRightSendWithoutCancel() {
        var sends = 0
        var theme by mutableStateOf("sakura")
        var virtual by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                InteractionGlass(theme, Modifier.testTag("gift-composer")) {
                    GiftComposerContent(theme, "red_packet", virtual, "0.01", {}, "给你的小心意", {}, false, null,
                        onModeChange = { virtual = it }) { sends++ }
                }
            }
        } }
        compose.onNodeWithText("取消").assertDoesNotExist()
        compose.onNodeWithTag("gift-virtual-mode").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("本次为虚拟金额，不关联钱包、不记账").assertIsDisplayed()
        compose.onNodeWithTag("gift-virtual-mode").performClick().assertIsOff()
        val bounds = compose.onNodeWithTag("gift-composer").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("gift-send").fetchSemanticsNode().boundsInRoot
        assertTrue(send.center.x > bounds.center.x && send.center.y > bounds.center.y)
        listOf("sakura", "ice", "lavender").forEach {
            compose.runOnIdle { theme = it }
            capture("flow-send-$it")
        }
        compose.onNodeWithTag("gift-send").performClick()
        org.junit.Assert.assertEquals(1, sends)
    }

    @Test fun mascotFacesAndThemedCloseControlsRenderAcrossAllThemes() {
        var theme by mutableStateOf("sakura")
        var enabled by mutableStateOf(true)
        var closes = 0
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().background(Color(0xFFABBACB)).padding(20.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                GiftDetailCard(ChatInteraction("face", "c", "a", "assistant", "red_packet",
                    theme = theme, amountCents = 1, note = "大吉大利～", status = "accepted"),
                    "已领取", false, false, null, "南枳") {}
                GiftDetailCloseButton(theme, enabled) { closes++ }
            }
        } }
        listOf("sakura", "ice", "lavender").forEach {
            compose.runOnIdle { theme = it }
            compose.onNodeWithTag("gift-detail-close").assertIsDisplayed().performClick()
            capture("polish-detail-$it")
        }
        org.junit.Assert.assertEquals(3, closes)
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("gift-detail-close").assertIsNotEnabled()
    }

    @Test fun floatingPillKeepsBalancedInsetsAndStatusAboveAvatar() {
        var theme by mutableStateOf("sakura")
        var opened = false
        var ended = false
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().background(Color(0xFFABBACB)).padding(top = 20.dp)) {
                me.rerere.rikkahub.ui.pages.voice.FloatingCallPill(theme, onOpen = { opened = true }, onEnd = { ended = true })
            }
        } }
        val pill = compose.onNodeWithTag("floating-call-pill").fetchSemanticsNode().boundsInRoot
        val avatar = compose.onNodeWithTag("floating-call-avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val dot = compose.onNodeWithTag("floating-call-status", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Avatar must be vertically centered without an empty lower band",
            kotlin.math.abs((avatar.top - pill.top) - (pill.bottom - avatar.bottom)) < 2f)
        assertTrue("Online status belongs on the lower right avatar rim",
            dot.center.y > avatar.center.y && dot.bottom >= avatar.bottom && dot.center.x > avatar.center.x)
        compose.onNodeWithContentDescription("扬声器").assertDoesNotExist()
        compose.onNodeWithContentDescription("听筒").assertDoesNotExist()
        listOf("sakura", "ice", "lavender").forEach {
            compose.runOnIdle { theme = it }
            capture("polish-pill-$it")
        }
        compose.onNodeWithText("点击返回通话").performClick()
        compose.onNodeWithContentDescription("挂断").performClick()
        assertTrue(opened && ended)
    }

    @Test fun compactMoreStripPagesAllActionsAndCanReturn() {
        var selected = ""
        val labels = listOf("拍照", "照片", "文件", "视频", "音频", "共度", "语音通话", "红包", "转账")
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().background(Color(0xFFF4F3FB)).padding(16.dp)) {
                me.rerere.rikkahub.ui.components.ai.PickerActionStrip {
                    labels.forEach { label ->
                        me.rerere.rikkahub.ui.components.ai.BigIconTextButton(
                            icon = { androidx.compose.material3.Text("◇") },
                            text = { androidx.compose.material3.Text(label) }) { selected = label }
                    }
                }
                androidx.compose.material3.Text("MCP服务器")
            }
        } }
        val strip = compose.onNodeWithTag("picker-more-strip").fetchSemanticsNode().boundsInRoot
        val divider = compose.onNodeWithTag("picker-more-divider").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals("More should sit on the divider", divider.center.y, strip.center.y, 1f)
        val total = compose.onNodeWithTag("picker-action-strip").fetchSemanticsNode().boundsInRoot
        assertTrue("More must stay a narrow strip under the tiles", strip.height / total.height < .26f)
        capture("polish-picker-first")
        compose.onNodeWithText("更多").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("音频").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals("音频", selected)
        compose.onNodeWithText("更多").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("转账").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals("转账", selected)
        capture("polish-picker-last")
        compose.onNodeWithText("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("拍照").assertIsDisplayed()
    }

    @Test fun packetRevealWaitsForReceiptAndAnimatesBeforeShowingAmount() {
        var card by mutableStateOf(ChatInteraction("motion", "c", "a", "assistant", "red_packet",
            amountCents = 1, note = "辛苦啦", theme = "lavender", status = "pending", walletLinked = true))
        var busy by mutableStateOf(false)
        var requests = 0
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().padding(20.dp)) {
            GiftDetailCard(card, if (card.status == "accepted") "已领取" else "待领取", true, busy, null, "南枳") {
                requests++; busy = true; card = card.copy(status = "syncing")
            }
        } } }
        compose.onNodeWithText("拆").performClick()
        compose.onNodeWithTag("packet-amount-reveal").assertDoesNotExist()
        org.junit.Assert.assertEquals(1, requests)
        compose.mainClock.autoAdvance = false
        capture("packet-motion-000")
        compose.runOnUiThread { busy = false; card = card.copy(status = "accepted") }
        for (frame in 1..4) { compose.mainClock.advanceTimeBy(48); capture("packet-motion-%03d".format(frame)) }
        compose.onNodeWithTag("packet-open-button").assertExists()
        compose.onNodeWithTag("packet-amount-reveal").assertDoesNotExist()
        val opening = compose.onNode(SemanticsMatcher.keyIsDefined(PacketOpeningProgress))
            .fetchSemanticsNode().config[PacketOpeningProgress]
        assertTrue("Opening must have intermediate frames", opening > 0f && opening < .56f)
        capture("packet-opening-mid")
        for (frame in 5..7) { compose.mainClock.advanceTimeBy(48); capture("packet-motion-%03d".format(frame)) }
        compose.onNodeWithTag("packet-amount-reveal").assertExists()
        assertTrue("Receipt must not start a second pop",
            !compose.onNodeWithTag("packet-amount-reveal").fetchSemanticsNode().config.contains(GiftRevealProgress))
        capture("packet-reveal-mid")
        for (frame in 8..21) { compose.mainClock.advanceTimeBy(48); capture("packet-motion-%03d".format(frame)) }
        compose.onNodeWithTag("packet-open-button").assertDoesNotExist()
        compose.onNodeWithText("0.01").assertIsDisplayed()
        compose.onNodeWithTag("packet-detail").assertHeightIsEqualTo(400.dp)
        capture("packet-reveal-final")
        compose.mainClock.autoAdvance = true
    }

    @Test fun transferConfirmationRevealsItsReceiptWithoutBouncingTheCard() {
        var card by mutableStateOf(ChatInteraction("transfer-motion", "c", "a", "assistant", "transfer",
            amountCents = 5200, theme = "sakura", status = "pending", walletLinked = true))
        var busy by mutableStateOf(false)
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().padding(20.dp)) {
            GiftDetailCard(card, if (card.status == "accepted") "已收款" else "待收款", true, busy, null, "南枳") {
                busy = true; card = card.copy(status = "syncing")
            }
        } } }
        compose.mainClock.autoAdvance = false
        capture("transfer-motion-000")
        compose.onNodeWithText("确认收款  ✦").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("transfer-confirmation-reveal").assertDoesNotExist()
        compose.runOnUiThread { busy = false; card = card.copy(status = "accepted") }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        capture("transfer-motion-001")
        val reveal = compose.onNodeWithTag("transfer-confirmation-reveal").fetchSemanticsNode().config[GiftRevealProgress]
        assertTrue("Confirmation must have visible intermediate frames", reveal > 0f && reveal < 1f)
        org.junit.Assert.assertEquals(1f, compose.onNodeWithTag("transfer-detail").fetchSemanticsNode().config[GlassScale])
        capture("transfer-confirmation-mid")
        for (frame in 2..14) { compose.mainClock.advanceTimeBy(48); capture("transfer-motion-%03d".format(frame)) }
        compose.onNodeWithText("已确认收款").assertIsDisplayed()
        compose.onNodeWithText("确认收款  ✦").assertDoesNotExist()
        org.junit.Assert.assertEquals(1f, compose.onNodeWithTag("transfer-confirmation-reveal").fetchSemanticsNode().config[GiftRevealProgress])
        capture("transfer-confirmation-final")
        compose.mainClock.autoAdvance = true
    }

    @Test fun reopeningAnAcceptedTransferDoesNotReplayConfirmation() {
        compose.setContent { MaterialTheme {
            GiftDetailCard(ChatInteraction("accepted-transfer", "c", "a", "assistant", "transfer",
                amountCents = 5200, theme = "lavender", status = "accepted"),
                "已收款", true, false, null, "南枳") {}
        } }
        compose.mainClock.autoAdvance = false
        org.junit.Assert.assertEquals(1f, compose.onNodeWithTag("transfer-confirmation-reveal").fetchSemanticsNode().config[GiftRevealProgress])
        compose.onNodeWithText("已确认收款").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
    }

    @Test fun openingTheGiftDialogHasAnObservableEntranceTransition() {
        var open by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            androidx.compose.material3.Button(onClick = { open = true }) { androidx.compose.material3.Text("打开礼物") }
            if (open) InteractionDetailDialog({ open = false }, true) {
                GiftDetailCard(ChatInteraction("dialog", "c", "a", "assistant", "red_packet", amountCents = 1),
                    "待领取", true, false, null, "南枳") {}
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("打开礼物").performClick()
        compose.mainClock.advanceTimeBy(96)
        val progress = compose.onNodeWithTag("gift-detail-dialog").fetchSemanticsNode().config[GiftRevealProgress]
        assertTrue(progress > 0f && progress < 1f)
        compose.mainClock.advanceTimeBy(500)
        org.junit.Assert.assertEquals(1f, compose.onNodeWithTag("gift-detail-dialog").fetchSemanticsNode().config[GiftRevealProgress])
        compose.mainClock.autoAdvance = true
    }

    @Test fun settledGiftCardsStayStillOnReentryAndScrolling() {
        var show by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                if (show) {
                    GiftChatBubble(ChatInteraction("p", "c", "a", "assistant", "red_packet",
                        status = "accepted"), "已领取", "南枳") {}
                    GiftChatBubble(ChatInteraction("t", "c", "a", "assistant", "transfer",
                        status = "accepted"), "已收款", "南枳") {}
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        repeat(2) {
            compose.runOnUiThread { show = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread { show = true }
            compose.mainClock.advanceTimeBy(32)
            for (tag in listOf("packet-bubble", "transfer-bubble")) {
                org.junit.Assert.assertEquals(1f, compose.onNodeWithTag(tag).fetchSemanticsNode().config[GlassScale])
                compose.onNodeWithTag(tag).performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -50f)); up() }
                compose.mainClock.advanceTimeBy(48)
                org.junit.Assert.assertEquals(1f, compose.onNodeWithTag(tag).fetchSemanticsNode().config[GlassScale])
            }
        }
        compose.onAllNodesWithText("已收下").assertCountEquals(1)
        compose.mainClock.autoAdvance = true
    }

    @Test fun `cards and both voice directions render with original vector decorations`() {
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFF4F0FF), Color(0xFFE1F3FF), Color(0xFFFFF0F6)))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RichChatCard(ChatInteraction("1","c","a","assistant","transfer",amountCents=131400,note="本周零花钱投喂",theme="ice"),null,true)
            RichChatCard(ChatInteraction("2","c","a","user","red_packet",amountCents=500,note="给你买小蛋糕",theme="sakura"),null,true)
            RichChatCard(ChatInteraction("3","c","a","assistant","call",theme="lavender"),null,true)
            VoiceMessageBubble(UIMessagePart.VoiceMessage(url="file:///preview.m4a",duration=7000,transcript="今天也想和你聊聊天"),false)
            VoiceMessageBubble(UIMessagePart.VoiceMessage(url="file:///preview2.m4a",duration=4000,transcript="我在听呀"),true)
        } } }
        compose.onNodeWithTag("transfer-bubble").assertIsDisplayed()
        compose.onNodeWithText("给你买小蛋糕").assertIsDisplayed()
        compose.onNodeWithTag("packet-bubble").assertHeightIsEqualTo(94.dp)
        compose.onNodeWithTag("packet-bubble").assertWidthIsEqualTo(280.dp)
        compose.onNodeWithTag("transfer-bubble").assertHeightIsEqualTo(136.dp)
        compose.onNodeWithTag("transfer-bubble").assertWidthIsEqualTo(280.dp)
        assertTrue(compose.onNodeWithTag("transfer-bubble").fetchSemanticsNode().boundsInRoot.height > compose.onNodeWithTag("packet-bubble").fetchSemanticsNode().boundsInRoot.height)
        compose.onNodeWithText("语音通话", substring = true).assertExists()
        capture("native-cards")
    }

    @Test fun `readonly payment opens detail without a receive action`() {
        compose.setContent { MaterialTheme {
            RichChatCard(ChatInteraction("1","original","a","assistant","transfer",amountCents=501),null,true)
        } }
        compose.onNodeWithTag("transfer-bubble").performClick()
        compose.onNodeWithText("确认收款  ✦").assertDoesNotExist()
        compose.onNodeWithText("关闭").assertIsDisplayed()
    }
    @Test fun `packet detail preserves sealed and opened states without exposing amount before receipt`() {
        var received by mutableStateOf(false)
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().background(Color(0xFFF5F1FA)).padding(20.dp)) {
            GiftDetailCard(ChatInteraction("packet", "c", "a", "assistant", "red_packet", amountCents=5200,
                note="给你买小蛋糕",theme="sakura",status=if(received) "accepted" else "pending"),
                if(received) "已领取" else "待领取", true, false, null) { action -> if(action == "accept") received = true }
        } } }
        compose.onNodeWithText("52.00").assertDoesNotExist()
        compose.onNodeWithText("拆").assertIsDisplayed()
        compose.onNodeWithTag("packet-detail").assertHeightIsEqualTo(400.dp)
        capture("packet-sealed")
        compose.onNodeWithText("拆").performClick()
        compose.onNodeWithText("52.00").assertIsDisplayed()
        compose.onNodeWithText("拆").assertDoesNotExist()
        capture("packet-opened")
    }

    @Test fun `transfer detail retains receipt rows and confirmation action`() {
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().background(Color(0xFFEAF4FF)).padding(20.dp)) {
            GiftDetailCard(ChatInteraction("transfer", "c", "a", "assistant", "transfer", amountCents=131400,
                note="本周零花钱投喂",theme="ice"),"待收款",true,false,null) {}
        } } }
        compose.onNodeWithText("转账说明").assertIsDisplayed()
        compose.onNodeWithText("发起时间").assertIsDisplayed()
        compose.onNodeWithTag("transfer-detail").assertHeightIsEqualTo(395.dp)
        compose.onNodeWithText("确认收款  ✦").assertIsDisplayed()
        compose.onNodeWithText("退还给对方").assertIsDisplayed()
        capture("transfer-detail")
    }

    private fun capture(name: String) {
        val output = File("build/reports/chat-interaction/$name.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }

    @Test fun `three theme icons remain distinct across payment kinds and settled states`() {
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().background(Color(0xFFF4F3FB)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("ice", "sakura", "lavender").forEach { theme ->
                GiftChatBubble(ChatInteraction("t-$theme", "c", "a", "assistant", "transfer", amountCents=131400,
                    note="给你的一点心意", theme=theme), "待收款", "南枳") {}
                GiftChatBubble(ChatInteraction("p-$theme", "c", "a", "assistant", "red_packet", amountCents=5200,
                    note="给你买小蛋糕", theme=theme, status="accepted"), "已领取", "南枳") {}
            }
        } } }
        compose.onAllNodesWithText("✦ 已领取").assertCountEquals(3)
        capture("theme-cards")
    }

    @Test fun `call bubbles distinguish invitation active and ended states`() {
        val connectedAt = System.currentTimeMillis() - 1_320_000L
        val endedAt = connectedAt + 1_320_000L
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RichChatCard(ChatInteraction("call-pending", "c", "a", "assistant", "call", theme="ice", status="pending"), null, true)
            RichChatCard(ChatInteraction("call-active", "c", "a", "assistant", "call", theme="sakura", status="accepted", connectedAtMillis=connectedAt), null, true)
            RichChatCard(ChatInteraction("call-ended", "c", "a", "assistant", "call", theme="lavender", status="ended", connectedAtMillis=connectedAt, endedAtMillis=endedAt), null, true)
        } } }
        compose.onNodeWithText("语音通话邀请").assertExists()
        compose.onNodeWithText("✦ 等待接听 · 点击查看").assertExists()
        compose.onNodeWithText("✦ 通话中 · 点击查看").assertExists()
        compose.onNodeWithText("通话结束 · 22:00").assertExists()
        compose.onNodeWithText("✦ 已结束 · 点击查看").assertExists()
        capture("call-bubble-states")
    }

    @Test fun `confirmed transfer retains its themed receipt seal`() {
        var card by mutableStateOf(ChatInteraction("transfer-received", "c", "a", "assistant", "transfer",
            amountCents = 5200, note = "给你的一点心意", theme = "sakura", status = "pending"))
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                GiftChatBubble(card, if (card.status == "accepted") "已收款" else "待收款", "南枳") {}
            }
        } }
        compose.runOnIdle { card = card.copy(status = "accepted") }
        compose.waitForIdle()
        compose.onNodeWithText("已收下").assertIsDisplayed()
        capture("transfer-received")
    }


    @Test fun `gongdu note card opens once and ignores taps while busy`() {
        var opens = 0
        var enabled by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                androidx.compose.material3.Text("一起逛逛", style = MaterialTheme.typography.headlineSmall)
                me.rerere.rikkahub.ui.pages.gongdu.FeedCard(
                    me.rerere.rikkahub.data.gongdu.GongduFeed("preview", "把今天的小风景，带回我们的聊天里", "", "一起记录生活"), enabled
                ) { opens++; enabled = false }
                me.rerere.rikkahub.ui.pages.gongdu.GongduEmptyService {}
            }
        } }
        capture("gongdu-cards")
        compose.onNodeWithText("把今天的小风景，带回我们的聊天里").performClick()
        compose.onNodeWithText("把今天的小风景，带回我们的聊天里").performClick()
        org.junit.Assert.assertEquals(1, opens)
    }

    @Test fun `everyday interaction keeps read timestamp beside its own avatar row`() {
        val display = me.rerere.rikkahub.data.datastore.DisplaySetting(chatDisplayMode = me.rerere.rikkahub.data.datastore.ChatDisplayMode.EVERYDAY)
        val message = me.rerere.ai.ui.UIMessage.assistant("红包").copy(annotations = listOf(
            ChatInteraction("row", "c", "a", "assistant", "red_packet", note = "卡片布局检查")))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(me.rerere.rikkahub.ui.context.LocalDisplaySettings provides display,
                me.rerere.rikkahub.ui.context.LocalSettings provides me.rerere.rikkahub.data.datastore.Settings(displaySetting = display)) {
                ChatMessage(node = me.rerere.rikkahub.data.model.MessageNode(messages = listOf(message)), readOnly = true,
                    hasUserRead = true, onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {})
            }
        } }
        compose.onNodeWithText("卡片布局检查").assertIsDisplayed()
        compose.onNode(hasText("已读", substring = true)).assertIsDisplayed()
        capture("note261-everyday-card")
    }

    @Test fun `standard interaction keeps a timestamp when the sender header is hidden`() {
        val display = me.rerere.rikkahub.data.datastore.DisplaySetting(
            userNickname = "严槿", showUserAvatar = false, showDateBelowName = true,
        )
        val message = me.rerere.ai.ui.UIMessage.user("红包").copy(annotations = listOf(
            ChatInteraction("standard-row", "c", "a", "user", "red_packet", note = "标准模式布局检查"),
        ))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(
                me.rerere.rikkahub.ui.context.LocalDisplaySettings provides display,
                me.rerere.rikkahub.ui.context.LocalSettings provides me.rerere.rikkahub.data.datastore.Settings(displaySetting = display),
            ) {
                ChatMessage(node = me.rerere.rikkahub.data.model.MessageNode(messages = listOf(message)), readOnly = true,
                    onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {})
            }
        } }
        compose.onNodeWithText("标准模式布局检查").assertIsDisplayed()
        val shortTime = message.createdAt.toJavaLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        compose.onNodeWithText(shortTime).assertIsDisplayed()
        capture("standard-interaction-timestamp-fallback")
    }

    @Test fun `call screen and floating pill remain accessible`() {
        var reopened = false
        var speakerOn by mutableStateOf(false)
        var speakerToggles = 0
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                me.rerere.rikkahub.ui.pages.voice.FloatingCallPill(theme = "lavender", name = "南枳", onOpen = { reopened = true }, onEnd = {})
                me.rerere.rikkahub.ui.pages.voice.GlassCallPanel("lavender", "南枳", "正在聆听", "01:30", "我在听呀",
                    speakerOn = speakerOn, onSpeaker = { speakerOn = !speakerOn; speakerToggles++ },
                    onCollapse = {}, onPrimary = {}, onEnd = {})
            }
        } }
        compose.onNodeWithText("点击返回通话").performClick()
        org.junit.Assert.assertTrue(reopened)
        compose.onNodeWithTag("call-control-静音").assertIsDisplayed()
        capture("flow-earpiece")
        compose.onNodeWithTag("call-control-听筒").performClick()
        org.junit.Assert.assertEquals(1, speakerToggles)
        compose.onNodeWithTag("call-control-扬声器").assertIsDisplayed()
        compose.onNodeWithTag("call-control-挂断").assertIsDisplayed()
        capture("note261-call-screen")
        compose.onNodeWithTag("call-control-扬声器").performClick()
        compose.onNodeWithTag("call-control-听筒").assertIsDisplayed()
        org.junit.Assert.assertEquals(2, speakerToggles)
    }
}
