package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.pager.PagerState
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.gongdu.GongduConfiguration
import me.rerere.rikkahub.data.gongdu.GongduEvent
import me.rerere.rikkahub.data.gongdu.GongduFeed
import me.rerere.rikkahub.data.gongdu.GongduInbox
import me.rerere.rikkahub.data.gongdu.GongduSetting
import me.rerere.rikkahub.ui.theme.PresetThemes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GongduHubUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable private fun Themed(dark: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDarkMode provides dark) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { Surface { content() } }
        }
    }

    private fun snapshot(name: String) {
        val output = File("build/reports/tests/gongdu-screenshots/$name.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun coverflowCentreIsFlatAndNeighboursShrinkTiltAndFade() {
        assertEquals(CoverflowTransform(1f, 0f, 1f), coverflowTransform(0f))
        val right = coverflowTransform(1f)
        assertEquals(0.86f, right.scale, 0.001f); assertEquals(5f, right.rotation, 0.001f); assertEquals(0.6f, right.alpha, 0.001f)
        assertEquals(-5f, coverflowTransform(-1f).rotation, 0.001f)
        val far = coverflowTransform(3f)
        assertEquals(0.8f, far.scale, 0.001f); assertEquals(7f, far.rotation, 0.001f); assertEquals(0.35f, far.alpha, 0.001f)
        // The card to the right of the centre has a positive offset, so it tilts clockwise like a fanned hand.
        assertEquals(1f, hubPageOffset(page = 3, currentPage = 2, fraction = 0f), 0.001f)
        assertTrue(coverflowTransform(hubPageOffset(3, 2, 0f)).rotation > 0f)
        assertTrue(coverflowTransform(hubPageOffset(1, 2, 0f)).rotation < 0f)
        // Swiping toward the next page brings it to the centre.
        assertEquals(0.6f, hubPageOffset(3, 2, 0.4f), 0.001f)
        // Halfway through a swipe the values are interpolated, not stepped.
        assertEquals(0.93f, coverflowTransform(0.5f).scale, 0.001f)
    }

    @Test fun hubEntersEnabledRoomAndReportsPlannedOnes() {
        compose.activity.getSharedPreferences("gongdu_hub", 0).edit().clear().commit()
        var entered: String? = null
        var unavailable: String? = null
        lateinit var pager: PagerState
        compose.setContent {
            Themed(false) {
                pager = rememberHubPagerState(GongduRooms)
                GongduHub({}, { entered = it.id }, { unavailable = it.id }, pager = pager)
            }
        }
        compose.onNodeWithText("共度").assertIsDisplayed()
        compose.onNodeWithText("01 / 04").assertIsDisplayed()
        snapshot("hub-light")
        compose.onNodeWithContentDescription("一起逛，轻点进入").performClick()
        assertEquals("look", entered)
        compose.runOnIdle { runBlocking { pager.scrollToPage(1) } }
        compose.waitForIdle()
        compose.onNodeWithText("02 / 04").assertIsDisplayed()
        // Tapping a side card does not enter it; only the centred card responds.
        compose.onNodeWithContentDescription("一起逛，轻点进入").performClick()
        assertEquals("look", entered)
        compose.onNodeWithContentDescription("一起读，筹备中").performClick()
        assertEquals("read", unavailable)
    }

    @Test fun hubReopensOnTheLastRoom() {
        compose.activity.saveLastGongduRoom("listen")
        compose.setContent { Themed(true) { GongduHub({}, {}, {}) } }
        compose.onNodeWithText("03 / 04").assertIsDisplayed()
        snapshot("hub-dark")
        compose.activity.getSharedPreferences("gongdu_hub", 0).edit().clear().commit()
    }

    @Test fun inboxKindNeedsAnExplicitVerb() {
        assertEquals(InboxKind.Other, inboxKind("小雨：好喜欢这篇"))
        assertEquals(InboxKind.Like, inboxKind("小雨：赞了你的笔记"))
        assertEquals(InboxKind.Comment, inboxKind("阿木：评论了你的笔记：好看"))
        assertEquals(InboxKind.Follow, inboxKind("momo：关注了你"))
    }

    @Test fun summariesAreSplitBadgedAndCleaned() {
        assertEquals("小雨" to "评论了你的笔记：好看", splitInboxSummary("小雨：评论了你的笔记：好看"))
        assertEquals("" to "收到新的小红书互动", splitInboxSummary("收到新的小红书互动"))
        assertEquals(InboxKind.Follow, inboxKind("阿木：开始关注你了"))
        assertEquals(InboxKind.Like, inboxKind("阿木：赞了你的笔记"))
        assertEquals(InboxKind.Comment, inboxKind("阿木：回复了你的评论"))
        assertEquals("周末去爬山 好开心", cleanSummary("周末去爬山\$\$ **好开心**[笑哭R]"))
    }

    // Full room screens in the app's real default theme, for visual review in CI artifacts.
    @Composable private fun AppThemed(dark: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDarkMode provides dark) {
            MaterialTheme(colorScheme = PresetThemes.first().getColorScheme(dark)) { Surface { content() } }
        }
    }

    private val sampleFeeds = listOf(
        GongduFeed("1", "char查user手机，想捉奸却翻出更糟的事", "t", "羽落"),
        GongduFeed("2", "毛玻璃恋爱小组件，一套可以直接抄", "t", "Amber"),
        GongduFeed("3", "堆堆前端美化 手账风聊天界面", "t", "青山劝月"),
        GongduFeed("4", "小猫第一次见雪，原地愣住三秒", "t", "momo"),
    )

    private fun roomScreen(name: String, tab: Int, dark: Boolean = false, body: @Composable () -> Unit) {
        compose.setContent {
            AppThemed(dark) {
                Column(Modifier.width(360.dp).height(780.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    RoomHeader("一起逛", "小红书 · 一起看，也一起聊", true) {}
                    RoomTabs(listOf("推荐", "搜索", "消息", "设置"), tab, {}, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    body()
                }
            }
        }
        compose.waitForIdle()
        snapshot(name)
    }

    @Test fun roomRecommendScreen() = roomScreen("screen-recommend", 0) {
        FeedGrid(sampleFeeds, false, header = { SectionTitle("一起逛逛", "挑一篇，带回聊天") }, empty = "", onOpen = {})
    }

    private val sampleInbox = GongduSetting(inbox = GongduInbox(initialized = true, supported = true, events = listOf(
        GongduEvent("a", "momo：关注了你"), GongduEvent("b", "小雨：赞了你的笔记《南枳的小相册开张啦》"),
        GongduEvent("c", "阿木：评论了你的笔记：小鸡小鸡，知道我是谁嘛～"), GongduEvent("d", "小雨：好喜欢这篇", acknowledged = true),
    )))

    @Test fun roomInboxScreen() = roomScreen("screen-inbox", 2) {
        InboxTab(true, sampleInbox, null, null, false, onDoorbell = {}, onRefresh = {})
    }

    @Test fun roomInboxScreenDark() = roomScreen("screen-inbox-dark", 2, dark = true) {
        InboxTab(true, sampleInbox, null, null, false, onDoorbell = {}, onRefresh = {})
    }

    @Test fun roomSettingsScreen() = roomScreen("screen-settings", 3) {
        GongduSetup(GongduConfiguration("https://example.test/mcp"), "已连接", true, "已登录：南枳", false,
            true, { _, _ -> }, {}, {}, {}, {})
    }

    @Test fun roomPiecesRenderAtPhoneWidthLight() = roomPieces(dark = false)

    @Test fun roomPiecesRenderAtPhoneWidthDark() = roomPieces(dark = true)

    private fun roomPieces(dark: Boolean) {
        val feeds = listOf(
            GongduFeed("1", "一个很长很长的标题，用来检查两行截断是否正常工作不会撑破卡片", "t", "小雨"),
            GongduFeed("2", "秋天的第一杯奶茶", "t", "阿木"),
        )
        val events = listOf(GongduEvent("a", "小雨：好喜欢这篇"), GongduEvent("b", "阿木：评论了你的笔记：好看", acknowledged = true))
        compose.setContent {
            Themed(dark) {
                Column(Modifier.width(360.dp)) {
                    RoomTabs(listOf("推荐", "搜索", "消息", "设置"), 2, {})
                    ConnectionChip(true)
                    Box(Modifier.width(170.dp)) { FeedCard(feeds[0], true) {} }
                    events.forEach { InboxRow(it) }
                    Box(Modifier.height(420.dp)) {
                        GongduSetup(GongduConfiguration("https://example.test/mcp"), "已连接", true, "", false,
                            true, { _, _ -> }, {}, {}, {}, {})
                    }
                }
            }
        }
        // "好喜欢这篇" is a plain message, not a like: the row shows the name and text, no invented verb.
        compose.onNodeWithText("好喜欢这篇").assertIsDisplayed()
        compose.onNodeWithText("小雨 赞了你").assertDoesNotExist()
        compose.onNodeWithText("阿木").assertIsDisplayed()
        compose.onNodeWithText("高级设置").assertIsDisplayed()
        // Configured services keep address/token folded away.
        compose.onNodeWithText("服务地址").assertDoesNotExist()
        snapshot(if (dark) "room-dark" else "room-light")
    }
}
