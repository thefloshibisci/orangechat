package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "zh-rCN-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatAttachmentPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable private fun Menu(onSelect: (String) -> Unit = {}) {
        PickerMenuLayout(attachments = {
            PickerActionStrip {
                listOf("拍照", "照片", "文件", "视频", "音频", "共度", "语音通话", "红包", "转账").forEach { label ->
                    BigIconTextButton(icon = { Text("◇") }, text = { Text(label) }) { onSelect(label) }
                }
            }
        }, actions = {
            listOf("MCP 服务器", "扩展", "压缩历史").forEach { label ->
                ListItem(headlineContent = { Text(label) }, leadingContent = { Text("◇") },
                    modifier = Modifier.testTag("row-" + label).clickable { onSelect(label) })
            }
        })
    }

    @Test fun simultaneousSwitchKeepsKeyboardGapAndNaturalMenuHeight() {
        var keyboard by mutableStateOf(260)
        var expanded by mutableStateOf(false)
        val nav = 20
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().background(Color(0xFFE6EDF4))) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f))
                    ChatComposerLayout(ime = WindowInsets(bottom = keyboard + nav), navigationBars = WindowInsets(bottom = nav)) {
                        Surface(Modifier.testTag("input").fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                            Text("输入消息与AI聊天", Modifier.height(80.dp).padding(16.dp))
                        }
                        ChatPanelDock {
                            if (expanded) Surface(Modifier.testTag("menu"), shape = MaterialTheme.shapes.large) {
                                ChatAttachmentPanel { Menu() }
                            }
                        }
                    }
                }
                if (keyboard > 0) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .height((keyboard + nav).dp).background(Color.DarkGray).testTag("keyboard")) {
                    Text("键盘", Modifier.padding(16.dp), color = Color.White)
                }
            }
        } }
        var menuHeight: Float? = null
        for (height in listOf(180, 260, 420)) {
            compose.runOnIdle { keyboard = height; expanded = false }
            val keyboardTop = compose.onNodeWithTag("keyboard").fetchSemanticsNode().boundsInRoot.top
            val input = compose.onNodeWithTag("input").fetchSemanticsNode().boundsInRoot
            assertEquals("Input must leave 8dp above the keyboard", 8f, keyboardTop - input.bottom, 1f)
            capture("keyboard-gap-" + height)
            // Menu returns to its own natural height after the keyboard closes.
            compose.runOnIdle { keyboard = 0; expanded = true }
            compose.waitForIdle()
            val menu = compose.onNodeWithTag("menu").fetchSemanticsNode().boundsInRoot
            val inputBottom = compose.onNodeWithTag("input").fetchSemanticsNode().boundsInRoot.bottom
            assertEquals("Original input/menu spacing", 8f, menu.top - inputBottom, 1f)
            if (menuHeight == null) menuHeight = menu.height else assertEquals(menuHeight!!, menu.height, 1f)
            compose.onNodeWithText("压缩历史").assertIsDisplayed().performClick()
            assertNoVerticalScroll()
            compose.runOnIdle { expanded = false; keyboard = 1 }
            compose.waitForIdle()
            for (fraction in listOf(.25f, .5f, .75f, 1f)) {
                compose.runOnIdle { keyboard = (height * fraction).toInt() }
                val liveKeyboard = compose.onNodeWithTag("keyboard").fetchSemanticsNode().boundsInRoot
                val liveInput = compose.onNodeWithTag("input").fetchSemanticsNode().boundsInRoot
                assertEquals(8f, liveKeyboard.top - liveInput.bottom, 1f)
            }
            assertEquals(input.top, compose.onNodeWithTag("input").fetchSemanticsNode().boundsInRoot.top, 1f)
        }
    }

    @Test fun bothDirectionsOverlapWithoutAddingMenuAndKeyboardHeights() {
        val panel = ChatPanelState()
        var keyboard by mutableStateOf(260)
        val nav = 20
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().testTag("screen")) {
                Spacer(Modifier.weight(1f))
                ChatComposerLayout(ime = WindowInsets(bottom = keyboard + nav), navigationBars = WindowInsets(bottom = nav)) {
                    Box(Modifier.height(80.dp).fillMaxWidth().testTag("input"))
                    ChatPanelDock { if (panel.expanded) ChatAttachmentPanel { Menu() } }
                }
            }
        } }
        for (height in listOf(180, 260, 420)) {
            compose.runOnIdle { keyboard = 0; panel.close(); panel.toggle(false) }
            compose.waitForIdle()
            val natural = compose.onNodeWithTag("chat-panel-dock").fetchSemanticsNode().boundsInRoot.height
            compose.runOnIdle { panel.close(); keyboard = height }
            compose.waitForIdle()
            val initialTop = compose.onNodeWithTag("input").fetchSemanticsNode().boundsInRoot.top
            val screenBottom = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot.bottom
            fun assertFrame(): Float {
                val input = compose.onNodeWithTag("input").fetchSemanticsNode().boundsInRoot
                val dock = compose.onNodeWithTag("chat-panel-dock").fetchSemanticsNode().boundsInRoot
                assertEquals("IME and menu must overlap, never stack", maxOf(keyboard.toFloat(), dock.height),
                    screenBottom - nav - input.bottom - 8f, 1f)
                assertTrue("Input must remain visible", input.top >= 0)
                assertTrue("Menu must not grow beyond its original height", dock.height <= natural + 1f)
                return dock.height
            }
            compose.mainClock.autoAdvance = false
            fun advanceFrames(duration: Long) {
                // Android measure/layout is outside the Compose clock. Flush each frame so
                // animateContentSize observes the new target before advancing its animation.
                repeat((duration / 16).toInt().coerceAtLeast(1)) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                }
            }
            compose.runOnIdle { panel.toggle(true) }
            advanceFrames(64)
            assertTrue("Menu must start before IME reaches zero", assertFrame() > 0f)
            var previous = 0f
            for (fraction in listOf(.75f, .5f, .25f, 0f)) {
                compose.runOnIdle { keyboard = (height * fraction).toInt(); panel.keyboardChanged(keyboard > 0, false, keyboard) }
                advanceFrames(48)
                val current = assertFrame()
                assertTrue("Opening must not bounce", current >= previous - 1f)
                previous = current
            }
            advanceFrames(304)
            assertEquals(natural, assertFrame(), 1f)
            assertNoVerticalScroll()
            previous = natural
            for (fraction in listOf(.01f, .25f, .5f, .75f, 1f)) {
                compose.runOnIdle { keyboard = (height * fraction).toInt().coerceAtLeast(1); panel.keyboardChanged(true, false, keyboard) }
                advanceFrames(48)
                val current = assertFrame()
                assertTrue("Closing must not bounce", current <= previous + 1f)
                previous = current
            }
            advanceFrames(304)
            assertEquals(0f, assertFrame(), 1f)
            assertEquals(initialTop, compose.onNodeWithTag("input").fetchSemanticsNode().boundsInRoot.top, 1f)
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun originalFullWidthRowsRemainStackedAndAllActionsWorkWithoutVerticalScrolling() {
        var width by mutableStateOf(320)
        var fontScale by mutableStateOf(1f)
        var selected = ""
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) { MaterialTheme {
            Column(Modifier.width(width.dp).fillMaxHeight().background(Color(0xFFE6EDF4))) {
                Spacer(Modifier.weight(1f))
                ChatComposerLayout(ime = WindowInsets(0), navigationBars = WindowInsets(0)) {
                    Surface(Modifier.testTag("input").fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                        Text("输入消息与AI聊天", Modifier.height(80.dp).padding(16.dp))
                    }
                    ChatPanelDock {
                        Surface(Modifier.testTag("menu"), shape = MaterialTheme.shapes.large) {
                            ChatAttachmentPanel { Menu { selected = it } }
                        }
                    }
                }
            }
        } } }
        for ((w, font) in listOf(320 to 1f, 320 to 1.3f, 360 to 1f)) {
            compose.runOnIdle { width = w; fontScale = font }
            val panel = compose.onNodeWithTag("chat-attachment-panel").fetchSemanticsNode().boundsInRoot
            var previousBottom = panel.top
            for (label in listOf("MCP 服务器", "扩展", "压缩历史")) {
                val row = compose.onNodeWithTag("row-" + label)
                val bounds = row.fetchSemanticsNode().boundsInRoot
                assertEquals(panel.width - 32f, bounds.width, 1f)
                assertTrue(bounds.top >= previousBottom && bounds.bottom <= panel.bottom && bounds.height >= 48f)
                previousBottom = bounds.bottom
                row.assertIsDisplayed().performClick()
                assertEquals(label, selected)
            }
            assertNoVerticalScroll()
            capture("menu-restored-" + w + "-" + font)
        }
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("转账").assertIsDisplayed().performClick()
        assertEquals("转账", selected)
    }

    private fun capture(name: String) {
        val file = File("build/reports/chat-interaction/" + name + ".png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun assertNoVerticalScroll() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
    }
}
