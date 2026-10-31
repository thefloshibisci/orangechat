package me.rerere.rikkahub.ui.components.message

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ToolApprovalState
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.theme.LocalMaterialMode
import me.rerere.rikkahub.ui.theme.Typography
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EverydayChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun eachBubbleHasAvatarAndLongPressWorksAcrossMaterialsAndRoles() {
        var role by mutableStateOf(MessageRole.USER)
        var material by mutableStateOf(DisplayMaterialMode.FLAT)
        var mode by mutableStateOf(ChatDisplayMode.EVERYDAY)
        var opened = false
        compose.setContent {
            val display = DisplaySetting(chatDisplayMode = mode, showUserAvatar = false, showModelIcon = false)
            MaterialTheme(typography = Typography) {
                CompositionLocalProvider(LocalDisplaySettings provides display,
                    LocalSettings provides Settings(displaySetting = display), LocalMaterialMode provides material) {
                    Surface {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            ChatMessageTextBubbles("第一句\n第二句", role, null, emptyList(), emptyList(), null, {}, {},
                                avatar = { Box(Modifier.testTag("avatar")) { ChatBubbleAvatar(role == MessageRole.USER, null, null) } },
                                onLongClick = { opened = true })
                        }
                    }
                }
            }
        }
        for (style in DisplayMaterialMode.entries) {
            for (speaker in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                compose.runOnIdle { material = style; role = speaker; opened = false }
                compose.onAllNodesWithTag("avatar", useUnmergedTree = true).assertCountEquals(2)
                compose.waitUntil(5000) { compose.onAllNodesWithText("第二句").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("第二句").performTouchInput { longClick() }
                compose.runOnIdle { assertTrue(opened) }
            }
        }
        capture("everyday-bubbles.png")
        compose.runOnIdle { mode = ChatDisplayMode.STANDARD }
        compose.onAllNodesWithTag("avatar", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun doubleTapOpensOriginalActionsAndHiddenProcessWithoutMutatingMessage() {
        val now = kotlin.time.Clock.System.now()
        val reason = UIMessagePart.Reasoning("保留的思考记录", createdAt = now, finishedAt = now)
        val original = UIMessage(role = MessageRole.USER, parts = listOf(reason, UIMessagePart.Text("长按这条消息")))
        val node = MessageNode(messages = listOf(original))
        var edited = false
        val display = DisplaySetting(chatDisplayMode = ChatDisplayMode.EVERYDAY)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDisplaySettings provides display,
                    LocalSettings provides Settings(displaySetting = display),
                    LocalNavController provides Navigator(mutableListOf())) {
                    ChatMessage(node = node, onFork = {}, onRegenerate = {}, onEdit = { edited = true },
                        onShare = {}, onDelete = {}, onUpdate = {})
                }
            }
        }
        compose.onNodeWithText("保留的思考记录").assertDoesNotExist()
        compose.waitUntil(5000) { compose.onAllNodesWithText("长按这条消息").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("长按这条消息").performTouchInput { doubleClick() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.copy)).assertExists()
        compose.onNodeWithText("查看思考和工具记录").performClick()
        compose.onNodeWithText("思考和工具记录").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.deep_thinking_seconds, 0f)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("保留的思考记录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保留的思考记录").assertIsDisplayed()
        assertEquals(listOf(reason, UIMessagePart.Text("长按这条消息")), original.parts)
        assertFalse(edited)
    }

    @Test fun largeFontSheetCanScrollToBottomAction() {
        var deleted = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.8f)) {
                    ChatMessageActionsSheet(message = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("测试"))),
                        model = null, onDelete = { deleted = true }, onEdit = {}, onShare = {}, onFork = {},
                        onSelectAndCopy = {}, onToggleFavorite = {}, onReact = {}, onShowProcess = {},
                        onWebViewPreview = {}, onDismissRequest = {})
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.delete)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(deleted) }
    }

    @Test fun pendingToolConfirmationRemainsClickableInEverydayMode() {
        val pending = UIMessagePart.Tool("approval-1", "workspace_shell", "{}", approvalState = ToolApprovalState.Pending)
        var tool by mutableStateOf(pending)
        var approvedId: String? = null
        val display = DisplaySetting(chatDisplayMode = ChatDisplayMode.EVERYDAY)
        compose.setContent {
            KoinApplication(application = { modules(module { single { AppEventBus() } }) }) {
                MaterialTheme {
                    CompositionLocalProvider(LocalDisplaySettings provides display,
                        LocalSettings provides Settings(displaySetting = display),
                        LocalNavController provides Navigator(mutableListOf())) {
                        ChatMessage(node = MessageNode(messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(tool)))),
                            onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {},
                            onToolApproval = { id, approved, _ ->
                                if (approved) {
                                    approvedId = id
                                    tool = tool.copy(output = listOf(UIMessagePart.Text("done")))
                                }
                            })
                    }
                }
            }
        }
        val label = compose.activity.getString(R.string.chat_message_tool_approve)
        compose.onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("approval-1", approvedId) }
        compose.onNodeWithContentDescription(label).assertDoesNotExist()
        assertTrue(pending.isPending)
    }

    @Test fun attachmentDoubleTapOpensActionsWithoutOpeningTheFile() {
        val display = DisplaySetting(chatDisplayMode = ChatDisplayMode.EVERYDAY)
        val node = MessageNode(messages = listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Document(url = "file:///test.pdf", fileName = "资料.pdf", mime = "application/pdf")))))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDisplaySettings provides display,
                    LocalSettings provides Settings(displaySetting = display),
                    LocalNavController provides Navigator(mutableListOf())) {
                    ChatMessage(node = node, onFork = {}, onRegenerate = {}, onEdit = {},
                        onShare = {}, onDelete = {}, onUpdate = {})
                }
            }
        }
        compose.onNodeWithText("资料.pdf").performTouchInput { doubleClick() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.copy)).assertExists()
    }

    @Test fun longPressQuotesOnlyTouchedSegmentAndDoubleTapDoesNotEdit() {
        var quoted = ""
        var edited = false
        val display = DisplaySetting(chatDisplayMode = ChatDisplayMode.EVERYDAY)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDisplaySettings provides display,
                    LocalSettings provides Settings(displaySetting = display),
                    LocalNavController provides Navigator(mutableListOf())) {
                    ChatMessage(node = MessageNode(messages = listOf(UIMessage(role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("第一段\n第二段"))))),
                        onFork = {}, onRegenerate = {}, onEdit = { edited = true },
                        onShare = {}, onDelete = {}, onUpdate = {}, onQuote = { quoted = it })
                }
            }
        }
        compose.onNodeWithText("第二段").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals("第二段", quoted.substringAfter("\n")); assertTrue(quoted.startsWith("**")); assertFalse(edited) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.copy)).assertDoesNotExist()
        compose.onNodeWithText("第一段").performTouchInput { doubleClick() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.copy)).assertExists()
        compose.runOnIdle { assertEquals("第二段", quoted.substringAfter("\n")); assertTrue(quoted.startsWith("**")); assertFalse(edited) }
    }

    @Test fun avatarsAreMirroredAndLongBubblesStayInsideOppositeStart() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).testTag("lane")) {
                    for (user in listOf(false, true)) {
                        ChatBubbleRow(true, user, avatar = { Box(Modifier.size(32.dp).testTag("avatar-$user")) }) {
                            Box(Modifier.fillMaxWidth().height(40.dp).testTag("bubble-$user"))
                        }
                    }
                }
            }
        }
        val lane = compose.onNodeWithTag("lane").fetchSemanticsNode().boundsInRoot
        val ai = compose.onNodeWithTag("avatar-false").fetchSemanticsNode().boundsInRoot
        val user = compose.onNodeWithTag("avatar-true").fetchSemanticsNode().boundsInRoot
        val aiBubble = compose.onNodeWithTag("bubble-false").fetchSemanticsNode().boundsInRoot
        val userBubble = compose.onNodeWithTag("bubble-true").fetchSemanticsNode().boundsInRoot
        assertEquals(ai.left - lane.left, lane.right - user.right, 0.5f)
        assertEquals(aiBubble.left, userBubble.left, 0.5f)
        assertEquals(aiBubble.right, userBubble.right, 0.5f)
        assertEquals(aiBubble.left - ai.right, user.left - userBubble.right, 0.5f)
    }

    @Test fun quotingPreservesDraftAttachmentsAndCannotOverwriteAnEdit() {
        val state = me.rerere.rikkahub.ui.hooks.ChatInputState()
        val image = UIMessagePart.Image("file:///upload/quote-test.png")
        try {
            state.setContents(listOf(UIMessagePart.Text("我的草稿"), image))
            assertTrue(state.quoteText("第一行\n第二行"))
            assertEquals("> 第一行\n> 第二行\n\n我的草稿", state.textContent.text.toString())
            assertEquals(listOf(image), state.messageContent)
            val before = state.getContents()
            state.editingMessage = kotlin.uuid.Uuid.random()
            assertFalse(state.quoteText("不可覆盖编辑"))
            assertEquals(before.filterIsInstance<UIMessagePart.Text>().last().text, state.textContent.text.toString())
            assertEquals(listOf(image), state.messageContent)
        } finally { state.releaseDraft() }
    }

    @Test fun quoteCardStaysInReplyBubbleAndTimeSitsBelowOnBothSides() {
        compose.setContent {
            val display = DisplaySetting(chatDisplayMode = ChatDisplayMode.EVERYDAY)
            MaterialTheme(typography = Typography) {
                CompositionLocalProvider(LocalDisplaySettings provides display,
                    LocalSettings provides Settings(displaySetting = display)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (user in listOf(false, true)) {
                            ChatMessageTextBubbles(
                                text = if (user) "> **南枳**\n> 先休息一会儿\n\n好，等会儿回来"
                                    else "> **我**\n> 不是，是我要去洗澡\n\n我不是故意的",
                                role = if (user) MessageRole.USER else MessageRole.ASSISTANT,
                                assistant = null, reactions = emptyList(), reactionPresets = emptyList(),
                                onReact = null, onUserMessageClick = null, onClickCitation = {},
                                avatar = { Box(Modifier.size(32.dp).testTag("quote-avatar")) },
                                onLongClick = null, timestamp = if (user) "13:08" else "13:07",
                            )
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithTag("quote-avatar").assertCountEquals(2)
        val author = compose.onNodeWithText("我", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val quote = compose.onNodeWithText("不是，是我要去洗澡", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val reply = compose.onNodeWithText("我不是故意的", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val time = compose.onNodeWithText("13:07", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(author.bottom <= quote.top)
        assertTrue(quote.bottom <= reply.top)
        assertTrue(reply.bottom <= time.top)
        compose.onNodeWithText("13:08").assertExists()
        capture("quote-card-time.png")
    }

    @Test fun userTimestampChangesFromUnreadToReadWithResponse() {
        var replied by mutableStateOf(false)
        val message = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello")),
            createdAt = kotlinx.datetime.LocalDateTime(2026, 10, 31, 13, 8))
        val display = DisplaySetting(chatDisplayMode = ChatDisplayMode.EVERYDAY)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDisplaySettings provides display,
                    LocalSettings provides Settings(displaySetting = display),
                    LocalNavController provides Navigator(mutableListOf())) {
                    ChatMessage(node = MessageNode(messages = listOf(message)), hasAssistantResponse = replied,
                        onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {})
                }
            }
        }
        compose.onNodeWithText("13:08 未读").assertExists()
        compose.runOnIdle { replied = true }
        compose.onNodeWithText("13:08 已读").assertExists()
        compose.onNodeWithText("13:08 未读").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/test-screenshots/$name")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
