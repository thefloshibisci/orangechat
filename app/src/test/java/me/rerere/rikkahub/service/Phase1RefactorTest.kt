/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.serein.SereinHttp
import me.rerere.rikkahub.data.serein.SereinHookConfig
import me.rerere.rikkahub.testing.TestAndroidKeyStore
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.speechDeliveryPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security
import kotlin.time.Clock
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class Phase1RefactorTest {

    @Test
    fun `ChatNotificationHelper determines live update content for all states`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val helper = ChatNotificationHelper(app)

        val pendingTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "search_web",
            input = """{"query":"kotlin"}""",
        )
        val (toolChip, _, toolContent) = helper.determineNotificationContent(listOf(pendingTool))
        assertEquals(app.getString(R.string.notification_live_update_chip_tool), toolChip)
        assertEquals("""{"query":"kotlin"}""", toolContent)

        val activeReasoning = UIMessagePart.Reasoning(
            reasoning = "thinking through options",
            createdAt = Clock.System.now(),
            finishedAt = null,
        )
        val (thinkChip, thinkStatus, thinkContent) = helper.determineNotificationContent(listOf(activeReasoning))
        assertEquals(app.getString(R.string.notification_live_update_chip_thinking), thinkChip)
        assertEquals(app.getString(R.string.notification_live_update_thinking), thinkStatus)
        assertEquals("thinking through options", thinkContent)

        val writingText = UIMessagePart.Text("final response body")
        val (writeChip, writeStatus, writeContent) = helper.determineNotificationContent(listOf(writingText))
        assertEquals(app.getString(R.string.notification_live_update_chip_writing), writeChip)
        assertEquals(app.getString(R.string.notification_live_update_writing), writeStatus)
        assertEquals("final response body", writeContent)

        val (emptyChip, emptyStatus, emptyContent) = helper.determineNotificationContent(emptyList())
        assertEquals(app.getString(R.string.notification_live_update_chip_writing), emptyChip)
        assertEquals(app.getString(R.string.notification_live_update_title), emptyStatus)
        assertEquals("", emptyContent)
    }

    @Test
    fun `extractNeteaseSongId parses supported schemes and rejects non-matching text`() {
        assertEquals("123456", extractNeteaseSongId("play orpheus://song/123456/?autoplay=1 now"))
        assertEquals("987654", extractNeteaseSongId("check https://music.163.com/song?id=987654&userid=1"))
        assertNull(extractNeteaseSongId(""))
        assertNull(extractNeteaseSongId("https://example.com/song?id=123456"))
    }

    @Test
    fun `buildConversationAfterMessageDelete handles branches single nodes and missing messages`() {
        val m1 = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("v1")))
        val m2 = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("v2")))
        val single = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("reply")))
        val branchedNode = MessageNode(messages = listOf(m1, m2), selectIndex = 1)
        val singleNode = MessageNode(messages = listOf(single), selectIndex = 0)
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(branchedNode, singleNode),
        )

        val afterDeletingSecondBranch = buildConversationAfterMessageDelete(conversation, m2.id)
        assertNotNull(afterDeletingSecondBranch)
        assertEquals(2, afterDeletingSecondBranch!!.messageNodes.size)
        assertEquals(listOf(m1), afterDeletingSecondBranch.messageNodes[0].messages)
        assertEquals(0, afterDeletingSecondBranch.messageNodes[0].selectIndex)

        val afterDeletingSingleNode = buildConversationAfterMessageDelete(conversation, single.id)
        assertNotNull(afterDeletingSingleNode)
        assertEquals(1, afterDeletingSingleNode!!.messageNodes.size)

        assertNull(buildConversationAfterMessageDelete(conversation, Uuid.random()))
    }

    @Test
    fun `voiceCallNotificationContentText formats voice and video states`() {
        assertEquals(
            "正在聆听...",
            voiceCallNotificationContentText(false, VoiceCallUiState(status = VoiceCallStatus.Listening)),
        )
        assertEquals(
            "通话中",
            voiceCallNotificationContentText(true, VoiceCallUiState(status = VoiceCallStatus.Listening)),
        )
        assertEquals(
            "正在思考...",
            voiceCallNotificationContentText(false, VoiceCallUiState(status = VoiceCallStatus.Processing)),
        )
        assertEquals(
            "正在说话...",
            voiceCallNotificationContentText(true, VoiceCallUiState(status = VoiceCallStatus.Speaking)),
        )
        assertEquals(
            "网络异常",
            voiceCallNotificationContentText(
                false,
                VoiceCallUiState(status = VoiceCallStatus.Error, errorMessage = "网络异常"),
            ),
        )
        assertEquals(
            "通话中",
            voiceCallNotificationContentText(false, VoiceCallUiState(status = VoiceCallStatus.Idle)),
        )
    }

    @Test
    fun `buildCallSystemPrompts handles voice-only path without video coordinator`() {
        val provider = TTSProviderSetting.SystemTTS(name = "sys")
        val prompts = buildCallSystemPrompts(
            ttsProvider = provider,
            coordinator = null,
            pictureAttached = false,
            audioAttached = false,
        )
        assertEquals(speechDeliveryPrompt(provider), prompts.actual)
        assertNull(prompts.withoutTransientAttachments)
        assertNull(prompts.withoutCamera)
    }

    @Test
    fun `SereinHttp exposes a singleton bare OkHttpClient without logging interceptors`() {
        val first = SereinHttp.client
        val second = SereinHttp.client
        assertSame(first, second)
        assertTrue(first.interceptors.isEmpty())
        assertTrue(first.networkInterceptors.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SettingsStore select suppresses unrelated writes and emits on slice change`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        stopKoin()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val keyProvider = TestAndroidKeyStore.TestProvider()
        Security.addProvider(keyProvider)
        val scope = AppScope()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val store = SettingsStore(app, scope)
            withTimeout(10_000) { store.settingsFlow.first { !it.init } }

            val observed = mutableListOf<SereinHookConfig>()
            val collectorJob = launch {
                store.sereinHookFlow.collect { observed += it }
            }
            withTimeout(5_000) {
                while (observed.isEmpty()) kotlinx.coroutines.delay(10)
            }
            assertEquals(1, observed.size)

            // Unrelated launchCount updates must not re-emit on the sereinHook slice.
            store.update { it.copy(launchCount = it.launchCount + 1) }
            store.update { it.copy(launchCount = it.launchCount + 1) }
            kotlinx.coroutines.delay(50)
            assertEquals(1, observed.size)

            val updatedHook = SereinHookConfig(enabled = true, baseUrl = "https://memory.example")
            store.update { it.copy(sereinHook = updatedHook) }
            withTimeout(5_000) {
                while (observed.size < 2) kotlinx.coroutines.delay(10)
            }
            assertEquals(listOf(SereinHookConfig(), updatedHook), observed)
            collectorJob.cancel()
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            stopKoin()
            Security.removeProvider(keyProvider.name)
            Dispatchers.resetMain()
        }
    }
}
