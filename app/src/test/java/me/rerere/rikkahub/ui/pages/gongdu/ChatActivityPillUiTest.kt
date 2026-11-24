package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room.Room
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.lifecycleScope
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.listen.ListenQueueItem
import me.rerere.rikkahub.data.listen.ListenRepository
import me.rerere.rikkahub.data.listen.ListenSong
import me.rerere.rikkahub.data.listen.ListenState
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.reading.ReadBook
import me.rerere.rikkahub.data.reading.ReadChapter
import me.rerere.rikkahub.data.reading.ReadRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.pages.chat.ChatTopBar
import me.rerere.rikkahub.ui.pages.voice.FloatingCallPill
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.tts.model.PlaybackState
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatActivityPillUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun activityIslandsRetainTheirFinishesAndControls() {
        val context = compose.activity.applicationContext
        val model = Model(modelId = "reader-test", displayName = "reader-test")
        val assistant = Assistant(name = "南枳", chatModelId = model.id)
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id,
            providers = listOf(ProviderSetting.OpenAI(name = "测试", models = listOf(model))))
        val conversation = Conversation(assistantId = assistant.id, title = "叽里咕噜对话", messageNodes = emptyList())
        val chatId = conversation.id.toString()
        val book = ReadBook("b".repeat(64), "月光落在书上", format = "txt",
            chapters = listOf(ReadChapter("第三章 夜读", listOf("从上次的书签继续阅读。"))))
        val read = ReadRepository(context)
        val playing = ListenState(conversationId = chatId, currentIndex = 0, positionMs = 25_000, durationMs = 90_000,
            queue = listOf(ListenQueueItem("track", ListenSong("123", "与你听海", "海岸来信"))))
        context.getSharedPreferences("gongdu_listen", Context.MODE_PRIVATE).edit()
            .putString("base_url", "https://music.example.test").putString("token", "fixture")
            .putString("queue", Json.encodeToString(ListenState.serializer(), playing)).apply()
        val releaseLookup = java.util.concurrent.CountDownLatch(1)
        val listen = ListenRepository(context, OkHttpClient.Builder().addInterceptor { chain ->
            check(releaseLookup.await(15, java.util.concurrent.TimeUnit.SECONDS))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body("""{"url":"https://media.example.test/song"}""".toResponseBody()).build()
        }.build())
        val koin = koinApplication { modules(module { single { read }; single { listen } }) }
        val dark = mutableStateOf(false)
        val callTheme = mutableStateOf("ice")
        val dayBackdrop = Color(0xFF9FB8D2) to Color(0xFFE7B5AF)
        val nightBackdrop = Color(0xFF283B52) to Color(0xFF513539)
        var openedListen = false
        var openedCall = false
        var endedCall = false

        try {
            runBlocking { read.importParsed(book); read.openBook(book.id, chatId) }
            compose.setContent {
                val scheme = if (dark.value) darkColorScheme() else lightColorScheme()
                val (left, right) = if (dark.value) nightBackdrop else dayBackdrop
                KoinIsolatedContext(koin) {
                    CompositionLocalProvider(LocalToaster provides rememberToasterState(), LocalDarkMode provides dark.value) {
                        MaterialTheme(colorScheme = scheme) {
                            Box(Modifier.fillMaxSize()) {
                                Canvas(Modifier.matchParentSize()) {
                                    drawRect(left, size = Size(size.width / 2, size.height))
                                    drawRect(right, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
                                }
                                Column(Modifier.padding(top = 12.dp)) {
                                    ChatTopBar(settings, conversation, rememberDrawerState(DrawerValue.Closed),
                                        bigScreen = false, previewMode = false, onClickMenu = {}, onNewChat = {},
                                        onUpdateTitle = {}, onConversationSettings = {}, onShareReadExcerpt = { true },
                                        showReading = true)
                                    FloatingCallPill(callTheme.value, name = "南枳",
                                        onOpen = { openedCall = true }, onEnd = { endedCall = true })
                                    ListenMiniBar(chatId) { openedListen = true }
                                }
                            }
                        }
                    }
                }
            }
            val tags = listOf("read-mini-bar", "floating-call-pill", "listen-mini-bar")
            for (isDark in listOf(false, true)) for (theme in listOf("ice", "sakura", "lavender")) {
                compose.runOnIdle { dark.value = isDark; callTheme.value = theme }
                val bounds = tags.map { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
                val subtitle = compose.onNodeWithText("南枳 / reader-test (测试)").assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                compose.runOnIdle {
                    val view = compose.activity.window.decorView
                    val density = view.resources.displayMetrics.density
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(AndroidCanvas(bitmap))
                    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
                    val readTint = scheme.surface.copy(alpha = if (isDark) .40f else .34f)
                    val (left, right) = if (isDark) nightBackdrop else dayBackdrop
                    val (readBar, callBar, listenBar) = bounds
                    assertEquals("The visible read capsule should be 42 dp high", 42 * density, readBar.height, 1f)
                    assertEquals("Listening uses the same compact height", readBar.height, listenBar.height, 1f)
                    for (bar in listOf(readBar, listenBar)) {
                        assertEquals("Align left edge with call", callBar.left, bar.left, 1f)
                        assertEquals("Align right edge with call", callBar.right, bar.right, 1f)
                    }
                    assertTrue("The read row should sit directly below the subtitle",
                        readBar.top - subtitle.bottom in 0f..(9 * density))
                    assertTrue("The read row should sit above the other islands", readBar.bottom <= callBar.top)
                    assertTrue("Read text should follow the chat theme",
                        (readBar.top.roundToInt() until readBar.bottom.roundToInt()).any { y ->
                            (readBar.left.roundToInt() until readBar.center.x.roundToInt()).any { x ->
                                bitmap.getPixel(x, y) == scheme.onSurface.toArgb()
                            }
                        })
                    for ((x, behind) in listOf(.35f to left, .65f to right)) {
                        val readX = (readBar.left + readBar.width * x).roundToInt()
                        for (readY in listOf(readBar.top + 4 * density, readBar.bottom - 4 * density)) {
                            val actual = Color(bitmap.getPixel(readX, readY.roundToInt()))
                            assertColorNear("read glass has only one translucent tint", readTint.compositeOver(behind), actual)
                        }
                        val edge = Color(bitmap.getPixel(readX, readBar.top.roundToInt() + 1))
                        assertColorNear("the read glass retains its border",
                            scheme.onSurface.copy(alpha = .26f).compositeOver(readTint.compositeOver(behind)), edge)
                        val listenX = (listenBar.left + listenBar.width * x).roundToInt()
                        val actual = Color(bitmap.getPixel(listenX, (listenBar.top + 4 * density).roundToInt()))
                        assertColorNear("listen glass has only one translucent tint", readTint.compositeOver(behind), actual)
                        val listenEdge = Color(bitmap.getPixel(listenX, listenBar.top.roundToInt() + 1))
                        assertColorNear("the listen glass retains its border",
                            scheme.onSurface.copy(alpha = .26f).compositeOver(readTint.compositeOver(behind)), listenEdge)
                        val callX = (callBar.left + callBar.width * x).roundToInt()
                        val callTop = Color(bitmap.getPixel(callX, (callBar.top + 4 * density).roundToInt()))
                        val callBottom = Color(bitmap.getPixel(callX, (callBar.bottom - 4 * density).roundToInt()))
                        val callRim = Color(bitmap.getPixel(callX, callBar.top.roundToInt() + 1))
                        assertTrue("Call retains its gradient and top sheen", callTop.luminance() - callBottom.luminance() > .06f)
                        assertTrue("Call retains its white rim", callRim.luminance() > callTop.luminance())
                    }
                    val lastBottom = bounds.last().bottom.roundToInt() + (12 * density).roundToInt()
                    val crop = Bitmap.createBitmap(bitmap, 0, 0, view.width, lastBottom)
                    val output = File("build/reports/tests/chat-call-original-finish/fallback-${if (isDark) "dark" else "light"}-$theme.png")
                    output.parentFile?.mkdirs()
                    output.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    crop.recycle()
                    bitmap.recycle()
                }
            }
            compose.onNodeWithText("点击返回通话").performClick()
            compose.onNodeWithContentDescription("挂断").performClick()
            compose.onNodeWithText("与你听海").performClick()
            compose.onNodeWithContentDescription("播放音乐").assertIsDisplayed()
            compose.onNodeWithTag("listen-mini-fallback-art", useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(hasClickAction() and androidx.compose.ui.test.hasText("共读")).assertIsDisplayed()
            compose.onNodeWithContentDescription("结束共读").performClick()
            compose.onNodeWithTag("read-mini-bar").assertDoesNotExist()
            assertTrue(openedCall && endedCall && openedListen)
            compose.onNodeWithContentDescription("收起播放条").performClick()
            compose.onNodeWithTag("listen-mini-bar").assertDoesNotExist()
            // Resume the same selection from another surface after explicitly hiding it.
            compose.runOnIdle { compose.activity.lifecycleScope.launch { listen.toggle() } }
            compose.onNodeWithContentDescription("暂停音乐").performClick()
            compose.onNodeWithTag("listen-mini-bar").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放音乐").assertIsDisplayed()
            assertEquals("123", listen.state.value.current?.song?.id)
        } finally {
            releaseLookup.countDown()
            compose.activityRule.scenario.close()
            koin.close()
            read.close()
            listen.close()
        }
    }

    @Test
    @Config(sdk = [34])
    fun realChatMessagesScrollBehindTheReadGlass() = verifyMessagesScrollBehindCapsule(listening = false)

    @Test
    @Config(sdk = [34])
    fun realChatMessagesScrollBehindTheListenGlassWithoutReading() = verifyMessagesScrollBehindCapsule(listening = true)

    @Test
    @Config(sdk = [34])
    fun realChatMessagesScrollBehindTheCallGlassWithoutReading() = verifyMessagesScrollBehindCapsule(listening = false, calling = true)

    private fun verifyMessagesScrollBehindCapsule(listening: Boolean, calling: Boolean = false) {
        val context = compose.activity.applicationContext
        val read = ReadRepository(context)
        val assistant = Assistant(name = "南枳", useAssistantAvatar = true)
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id,
            displaySetting = DisplaySetting(showMessageJumper = false))
        val firstMessage = "这是一条会滚过共读玻璃的消息"
        val conversation = Conversation(assistantId = assistant.id, title = "共读对话", messageNodes =
            List(20) { MessageNode(messages = listOf(UIMessage.assistant(if (it == 0) firstMessage else "第 $it 条消息"))) })
        val chatId = conversation.id.toString()
        if (listening) {
            val state = ListenState(conversationId = chatId, currentIndex = 0,
                queue = listOf(ListenQueueItem("track", ListenSong("123", "与你听海", "海岸来信"))))
            context.getSharedPreferences("gongdu_listen", Context.MODE_PRIVATE).edit()
                .putString("queue", Json.encodeToString(ListenState.serializer(), state)).apply()
        }
        val listen = if (listening) ListenRepository(context, OkHttpClient()) else null
        val book = ReadBook("a".repeat(64), "月光落在书上", format = "txt",
            chapters = listOf(ReadChapter("第三章 夜读", listOf("从上次书签继续。"))))
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val appScope = AppScope()
        val koin = koinApplication { modules(module {
            single { read }
            if (listen != null) single { listen }
            single { AppEventBus() }
            single { FilesManager(context, FilesRepository(database.managedFileDao()), appScope) }
        }) }
        val list = LazyListState()
        val glass = HazeState()
        val titleHeight = mutableIntStateOf(0)
        val title = "共读对话"
        try {
            if (!listening && !calling) runBlocking { read.importParsed(book); read.openBook(book.id, chatId) }
            compose.setContent {
                val density = LocalDensity.current
                KoinIsolatedContext(koin) {
                    CompositionLocalProvider(
                        LocalToaster provides rememberToasterState(),
                        LocalDisplaySettings provides settings.displaySetting,
                        LocalSettings provides settings,
                        LocalNavController provides remember { Navigator(mutableListOf()) },
                        LocalTTSState provides remember { SilentTts() },
                    ) {
                        MaterialTheme {
                            Scaffold(containerColor = Color.Transparent, topBar = {
                                Column {
                                    ChatTopBar(settings, conversation, rememberDrawerState(DrawerValue.Closed),
                                        bigScreen = false, previewMode = false, onClickMenu = {}, onNewChat = {},
                                        onUpdateTitle = {}, onConversationSettings = {}, onShareReadExcerpt = { true },
                                        showReading = !listening && !calling, readGlass = glass, compactForListening = listening,
                                        modifier = Modifier.onSizeChanged { titleHeight.intValue = it.height })
                                    if (listening) ListenMiniBar(chatId, glass) {}
                                    if (calling) FloatingCallPill("ice", onOpen = {}, onEnd = {}, glass = glass)
                                }
                            }) { innerPadding ->
                                ChatList(innerPadding = innerPadding, conversation = conversation, state = list,
                                    loading = false, previewMode = false, settings = settings, hazeState = HazeState(),
                                    modifier = Modifier.hazeSource(glass),
                                    fixedTopInset = with(density) { titleHeight.intValue.toDp() })
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText(title).assertIsDisplayed()
            if (listening || calling) compose.onNodeWithTag("read-mini-bar").assertDoesNotExist()
            val tag = when {
                calling -> "floating-call-pill"
                listening -> "listen-mini-bar"
                else -> "read-mini-bar"
            }
            val bar = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val message = compose.onNodeWithText(firstMessage).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("The first message is not hidden by the header at rest", message.top >= bar.bottom)
            compose.runOnIdle { list.dispatchRawDelta(message.center.y - bar.center.y) }
            compose.waitForIdle()
            val moved = compose.onNodeWithText(firstMessage).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("The real scroll viewport includes the glass area", moved.center.y in bar.top..bar.bottom)
        } finally {
            compose.activityRule.scenario.close()
            koin.close()
            read.close()
            listen?.close()
            appScope.cancel()
            database.close()
        }
    }

    private fun assertColorNear(message: String, expected: Color, actual: Color) {
        val delta = kotlin.math.abs(actual.red - expected.red) + kotlin.math.abs(actual.green - expected.green) +
            kotlin.math.abs(actual.blue - expected.blue)
        assertTrue("$message: $actual vs $expected", delta < .035f)
    }

    private class SilentTts : CustomTtsState {
        override val isAvailable = MutableStateFlow(false)
        override val isSpeaking = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val currentChunk = MutableStateFlow(0)
        override val totalChunks = MutableStateFlow(0)
        override val playbackState = MutableStateFlow(PlaybackState())
        override fun speak(text: String, flushCalled: Boolean) = Unit
        override fun stop() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun skipNext() = Unit
        override fun fastForward(ms: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun cleanup() = Unit
        override fun enqueueText(text: String) = Unit
        override suspend fun awaitCompletion() = Unit
    }
}
