/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.video.TransientAudioClip
import me.rerere.rikkahub.data.video.VideoFrameRequestScope
import me.rerere.rikkahub.data.video.VideoVoiceAvailability
import me.rerere.rikkahub.data.video.VideoVoiceStatus
import me.rerere.rikkahub.testing.TestAndroidKeyStore
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.hooks.ManualVoiceRecorder
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import me.rerere.tts.model.PlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.Security
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class Phase3RefactorTest {

    private lateinit var app: Application
    private lateinit var keyProvider: TestAndroidKeyStore.TestProvider
    private lateinit var appScope: AppScope
    private lateinit var settingsStore: SettingsStore

    @Before
    fun setUp() {
        runBlocking {
            Dispatchers.setMain(Dispatchers.Default)
            stopKoin()
            startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
            keyProvider = TestAndroidKeyStore.TestProvider()
            Security.addProvider(keyProvider)
            app = ApplicationProvider.getApplicationContext()
            appScope = AppScope()
            settingsStore = SettingsStore(app, appScope)
            withTimeout(10_000) { settingsStore.settingsFlow.first { !it.init } }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            try {
                appScope.coroutineContext[Job]?.cancelAndJoin()
            } finally {
                stopKoin()
                Security.removeProvider(keyProvider.name)
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun `VideoVoiceInputController toggle when unavailable sets Unavailable records notice and disables handsFree`() =
        runBlocking {
            val hostScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val host = FakeVideoVoiceHost(
                    hostScope = hostScope,
                    settingsStore = settingsStore,
                    voiceDir = File(app.cacheDir, "video_voice_unavailable"),
                    microphoneGranted = false,
                    directAudio = false,
                )
                val controller = VideoVoiceInputController(host)

                val started = controller.toggle()

                assertFalse(started)
                assertEquals(VideoVoiceStatus.Unavailable, controller.currentState.status)
                assertTrue(controller.currentState.reason.isNotBlank())
                assertEquals(controller.currentState.reason, controller.lastNotice)
                assertFalse(controller.handsFree)
                assertEquals(listOf(true, false), host.handsFreeHistory)
            } finally {
                hostScope.cancel()
            }
        }

    @Test
    fun `VideoVoiceInputController cancel and onHostBackground clear handsFree amplitudes and update notice`() =
        runBlocking {
            val hostScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val host = FakeVideoVoiceHost(
                    hostScope = hostScope,
                    settingsStore = settingsStore,
                    voiceDir = File(app.cacheDir, "video_voice_cancel"),
                    microphoneGranted = true,
                    directAudio = true,
                )
                val controller = VideoVoiceInputController(host)

                assertTrue(controller.toggle())
                assertTrue(controller.isActive)
                assertTrue(controller.handsFree)
                val firstRecorder = host.recorders.single()
                firstRecorder.mutableState.value = ASRState(
                    status = ASRStatus.Listening,
                    amplitudes = listOf(0.3f, 0.7f),
                )
                yield()
                assertEquals(listOf(0.3f, 0.7f), host.uiState.value.amplitudes)

                controller.cancel("用户手动取消")

                assertFalse(controller.handsFree)
                assertFalse(host.handsFreeHistory.last())
                assertTrue(host.uiState.value.amplitudes.isEmpty())
                assertEquals(VideoVoiceStatus.Idle, controller.currentState.status)
                assertEquals("用户手动取消", controller.lastNotice)
                assertEquals(1, firstRecorder.abortCount)

                assertTrue(controller.toggle())
                assertTrue(controller.isActive)
                val secondRecorder = host.recorders.last()
                secondRecorder.mutableState.value = ASRState(
                    status = ASRStatus.Listening,
                    amplitudes = listOf(0.5f),
                )
                yield()

                controller.onHostBackground()

                assertFalse(controller.handsFree)
                assertTrue(host.uiState.value.amplitudes.isEmpty())
                assertEquals(VideoVoiceStatus.Idle, controller.currentState.status)
                assertEquals("已离开视频页：这一段录音没有发送", controller.lastNotice)
            } finally {
                hostScope.cancel()
            }
        }

    @Test
    fun `VideoVoiceInputController muteForTypingInVideoMode disables handsFree while keeping Idle and null notice`() =
        runBlocking {
            val hostScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val host = FakeVideoVoiceHost(
                    hostScope = hostScope,
                    settingsStore = settingsStore,
                    voiceDir = File(app.cacheDir, "video_voice_typing"),
                    microphoneGranted = true,
                    directAudio = true,
                )
                val controller = VideoVoiceInputController(host)
                controller.handsFree = true

                controller.muteForTypingInVideoMode()

                assertFalse(controller.handsFree)
                assertEquals(VideoVoiceStatus.Idle, controller.currentState.status)
                assertNull(controller.lastNotice)
            } finally {
                hostScope.cancel()
            }
        }

    @Test
    fun `VoiceCallTurnOrchestrator closeRequestToken isolates older token from newer active token`() =
        runBlocking {
            val hostScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val host = FakeVoiceCallTurnHost(
                    applicationContext = app,
                    serviceScope = hostScope,
                    settingsStore = settingsStore,
                )
                val orchestrator = VoiceCallTurnOrchestrator(host)

                val t1 = orchestrator.beginTurnRequestIfVideoMode()
                val t2 = orchestrator.beginTurnRequestIfVideoMode()
                assertNotNull(t1)
                assertNotNull(t2)

                orchestrator.closeRequestToken(t1!!, cancelled = true)
                assertEquals(listOf(t1 to true), host.finishedTokens)

                orchestrator.endActiveRequestToken(cancelled = false)
                assertEquals(listOf(t1 to true, t2!! to false), host.finishedTokens)
            } finally {
                hostScope.cancel()
            }
        }

    @Test
    fun `VoiceCallTurnOrchestrator invalidateTurnAndWithdraw cancels queued request finishes token and stops TTS`() =
        runBlocking {
            val hostScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val tts = RecordingTts()
                val host = FakeVoiceCallTurnHost(
                    applicationContext = app,
                    serviceScope = hostScope,
                    settingsStore = settingsStore,
                    tts = tts,
                )
                val orchestrator = VoiceCallTurnOrchestrator(host)

                assertTrue(orchestrator.beginTurn("hello world", fromVoice = false))
                yield()

                assertEquals(1, host.submittedEntries.size)
                assertEquals(1, host.beganTokens.size)
                val entry = host.submittedEntries.single()
                val token = host.beganTokens.single()
                val stopCountBeforeInvalidate = tts.stopCount

                orchestrator.invalidateTurnAndWithdraw()

                assertEquals(listOf(entry.id), host.cancelledRequestIds)
                assertEquals(listOf(token to true), host.finishedTokens)
                assertEquals(stopCountBeforeInvalidate + 1, tts.stopCount)
            } finally {
                hostScope.cancel()
            }
        }

    @Test
    fun `VoiceCallTurnOrchestrator endCallCleanup withdraws queued request and cancels active token`() =
        runBlocking {
            val hostScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val host = FakeVoiceCallTurnHost(
                    applicationContext = app,
                    serviceScope = hostScope,
                    settingsStore = settingsStore,
                )
                val orchestrator = VoiceCallTurnOrchestrator(host)

                assertTrue(orchestrator.beginTurn("keep track of request", fromVoice = false))
                yield()

                val entry = host.submittedEntries.single()
                val token = host.beganTokens.single()

                orchestrator.endCallCleanup(withdrawRequests = true)

                assertEquals(listOf(entry.id), host.cancelledRequestIds)
                assertEquals(listOf(token to true), host.finishedTokens)
            } finally {
                hostScope.cancel()
            }
        }

    private class FakeManualRecorder : ManualVoiceRecorder {
        val mutableState = MutableStateFlow(ASRState())
        override val state: StateFlow<ASRState> get() = mutableState
        override val reachedLimit: Boolean = false
        var abortCount: Int = 0
            private set

        override fun start(onTranscriptChange: (String) -> Unit) {
            mutableState.value = ASRState(status = ASRStatus.Listening)
        }

        override fun stop() {
            mutableState.value = ASRState(status = ASRStatus.Idle)
        }

        override fun abort() {
            abortCount += 1
            mutableState.value = ASRState(status = ASRStatus.Idle)
        }

        override fun cleanup() = Unit
    }

    private class FakeVideoVoiceHost(
        hostScope: CoroutineScope,
        override val settingsStore: SettingsStore,
        private val voiceDir: File,
        var microphoneGranted: Boolean,
        var directAudio: Boolean,
    ) : VideoVoiceHost {
        override val serviceScope: CoroutineScope = hostScope
        override val uiState = MutableStateFlow(VoiceCallUiState())
        override val videoMode: Boolean = true
        override val callEnded: Boolean = false
        override val hasAsr: Boolean = false
        override val currentAsr: CustomAsrState? = null
        override val sessionToken: Long = 1L
        override val videoCoordinator: VideoCallCoordinator? = null
        override val videoAsrRecordingTimeoutMs: Long = 60_000L
        override val videoAsrTranscriptionTimeoutMs: Long = 15_000L

        val handsFreeHistory = mutableListOf<Boolean>()
        val recorders = mutableListOf<FakeManualRecorder>()

        override fun hasMicrophonePermission(): Boolean = microphoneGranted
        override fun videoVoiceDirectory(): File = voiceDir
        override suspend fun videoVoiceDelay(ms: Long) = delay(ms)
        override suspend fun videoVoiceSilenceDelay(ms: Long) = delay(ms)
        override fun videoVoiceNowMillis(): Long = System.currentTimeMillis()
        override fun createVoiceRecorder(): ManualVoiceRecorder = FakeManualRecorder().also { recorders += it }
        override fun directAudioSupported(): Boolean = directAudio
        override fun asrConfigured(): Boolean = false
        override fun invalidateAsrPipeline() = Unit
        override fun startInitialization() = Unit
        override fun onHandsFreeChanged(enabled: Boolean) {
            handsFreeHistory += enabled
        }

        override fun prepareInterruptForNewSegment() = Unit
        override fun beginTurn(
            text: String,
            fromVoice: Boolean,
            bubbleText: String,
            transientAudio: TransientAudioClip?,
        ): Boolean = true
    }

    private class RecordingTts : CustomTtsState {
        override val isAvailable = MutableStateFlow(true)
        override val isSpeaking = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val currentChunk = MutableStateFlow(0)
        override val totalChunks = MutableStateFlow(0)
        override val playbackState = MutableStateFlow(PlaybackState())
        var stopCount = 0
            private set

        override fun speak(text: String, flushCalled: Boolean) = Unit
        override fun stop() {
            stopCount += 1
        }

        override fun pause() = Unit
        override fun resume() = Unit
        override fun skipNext() = Unit
        override fun fastForward(ms: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun cleanup() = Unit
        override fun enqueueText(text: String) = Unit
        override suspend fun awaitCompletion() = Unit
    }

    private class FakeVoiceCallTurnHost(
        override val applicationContext: Context,
        override val serviceScope: CoroutineScope,
        override val settingsStore: SettingsStore,
        private val tts: CustomTtsState? = null,
    ) : VoiceCallTurnHost {
        private val convId = Uuid.random()
        private val convFlow = MutableStateFlow(
            Conversation(id = convId, assistantId = Uuid.random(), messageNodes = emptyList()),
        )
        private val queue = MessageQueue()

        override val uiState = MutableStateFlow(VoiceCallUiState())
        override fun conversationIdOrNull(): Uuid = convId
        override fun conversation(): StateFlow<Conversation> = convFlow
        override val videoMode: Boolean = true
        override val callEnded: Boolean = false
        override val videoCoordinator: VideoCallCoordinator? = null
        override val hasTts: Boolean get() = tts != null
        override val currentTts: CustomTtsState? get() = tts
        override val hasMicrophone: Boolean = false
        override val currentMicrophone: VoiceCallMicrophone? = null
        override val callTranscript = VoiceCallTranscript()

        val beganTokens = mutableListOf<String>()
        val finishedTokens = mutableListOf<Pair<String, Boolean>>()
        val submittedEntries = mutableListOf<QueuedMessage>()
        val cancelledRequestIds = mutableListOf<Uuid>()
        val recordedVideoMessageIds = mutableListOf<Uuid>()

        override fun shouldListenToCall(): Boolean = false
        override fun syncMicrophone() = Unit
        override fun startListening(recoveryMessage: String?) = Unit
        override fun interruptSpeaking() = Unit
        override fun postNotice(text: String?) = Unit
        override fun notifyVideoVoice(text: String) = Unit
        override fun videoVoiceAvailability(): VideoVoiceAvailability = VideoVoiceAvailability()
        override fun refreshVideoVoiceInput() = Unit
        override fun resumeVideoHandsFree() = Unit
        override fun recordVideoCallMessage(id: Uuid) {
            recordedVideoMessageIds += id
        }

        override fun beginVideoTurnRequest(token: String): Boolean {
            beganTokens += token
            return true
        }

        override fun finishVideoTurnRequest(token: String, cancelled: Boolean) {
            finishedTokens += token to cancelled
        }

        override fun submitCallMessage(
            parts: List<UIMessagePart>,
            transientSystemPrompt: String?,
            transientImageUrls: List<String>,
            ownedImageUrls: List<String>,
            transientSystemPromptWithoutImages: String?,
            requestToken: String?,
            transientAudio: TransientAudioClip?,
            imageScope: VideoFrameRequestScope?,
        ): QueuedMessage? = queue.enqueue(
            parts = parts,
            transientSystemPrompt = transientSystemPrompt,
            transientImageUrls = transientImageUrls,
            ownedImageUrls = ownedImageUrls,
            transientSystemPromptWithoutImages = transientSystemPromptWithoutImages,
            requestToken = requestToken,
            transientAudio = transientAudio,
            transientImageScope = imageScope,
        )?.also { submittedEntries += it }

        override fun cancelCallRequest(id: Uuid) {
            cancelledRequestIds += id
        }
    }
}
