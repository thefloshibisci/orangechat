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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
        compose.onNodeWithTag("packet-bubble").assertHeightIsEqualTo(136.dp)
        compose.onNodeWithTag("packet-bubble").assertWidthIsEqualTo(280.dp)
        compose.onNodeWithTag("transfer-bubble").assertHeightIsEqualTo(136.dp)
        compose.onNodeWithTag("transfer-bubble").assertWidthIsEqualTo(280.dp)
        assertTrue(compose.onNodeWithTag("transfer-bubble").fetchSemanticsNode().boundsInRoot.height == compose.onNodeWithTag("packet-bubble").fetchSemanticsNode().boundsInRoot.height)
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

    @Test fun `confirmed transfer animates its themed receipt seal`() {
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
                me.rerere.rikkahub.ui.pages.gongdu.GongduFeedCard(
                    me.rerere.rikkahub.data.gongdu.GongduFeed("preview", "把今天的小风景，带回我们的聊天里", "", "一起记录生活"), enabled
                ) { opens++; enabled = false }
                me.rerere.rikkahub.ui.pages.gongdu.GongduEmptyService {}
            }
        } }
        capture("gongdu-cards")
        compose.onNodeWithText("一起看看 ›").performClick()
        compose.onNodeWithText("一起看看 ›").performClick()
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
                me.rerere.rikkahub.ui.pages.voice.FloatingCallPill(theme = "lavender", onOpen = { reopened = true }, onEnd = {})
                me.rerere.rikkahub.ui.pages.voice.GlassCallPanel("lavender", "南枳", "正在聆听", "01:30", "我在听呀",
                    speakerOn = speakerOn, onSpeaker = { speakerOn = !speakerOn; speakerToggles++ },
                    onCollapse = {}, onPrimary = {}, onEnd = {})
            }
        } }
        compose.onNodeWithText("点击返回通话").performClick()
        org.junit.Assert.assertTrue(reopened)
        compose.onNodeWithTag("call-control-静音").assertIsDisplayed()
        compose.onNodeWithTag("call-control-扬声器").performClick()
        org.junit.Assert.assertEquals(1, speakerToggles)
        compose.onNodeWithTag("call-control-免提").assertIsDisplayed()
        compose.onNodeWithTag("call-control-挂断").assertIsDisplayed()
        capture("note261-call-screen")
    }
}
