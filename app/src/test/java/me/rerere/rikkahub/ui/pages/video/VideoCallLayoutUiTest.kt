package me.rerere.rikkahub.ui.pages.video

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.State
import android.animation.ValueAnimator
import org.robolectric.util.ReflectionHelpers
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.video.DanmakuLine
import me.rerere.rikkahub.data.video.VideoCameraStatus
import me.rerere.rikkahub.data.video.VideoVoiceRoute
import me.rerere.rikkahub.data.video.VideoVoiceStatus
import me.rerere.rikkahub.data.video.VideoVoiceUiState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import me.rerere.rikkahub.ui.components.message.interactionColors
import me.rerere.rikkahub.ui.components.ui.AvatarDisplay
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 全屏 [VideoCallLayout] 的像素级快照（Robolectric 原生 graphics）。
 *
 * 快照写到 `SNAPSHOT_DIR`（默认 build/test-screenshots/，可用 -D 覆盖到 D: 盘），
 * 让人能直接打开 PNG 核对全屏布局：满幅画面、玻璃控件、左对齐字幕、顶边渐隐。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoCallLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val colors = interactionColors("ice")

    private fun preview(colorA: Color, colorB: Color): @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colorA, colorB))))
    }

    @Test
    fun rendersFullScreenCallWithTranscriptAndControls() {
        val voice = mutableStateOf(VideoVoiceUiState(route = VideoVoiceRoute.ASR, status = VideoVoiceStatus.Recording))
        val callStatus = mutableStateOf(VoiceCallStatus.Listening)
        val playing = mutableStateOf(false)
        val muted = mutableStateOf(false)
        var micTaps = 0
        compose.setContent {
            MaterialTheme {
                VideoCallLayout(
                    assistantName = "橘子",
                    assistantAvatar = Avatar.Dummy,
                    presence = videoCallPresence(VoiceCallUiState(
                        status = callStatus.value,
                        voiceInput = voice.value,
                        isMuted = muted.value,
                        isAssistantSpeaking = playing.value,
                    )),
                    duration = "03:41",
                    giftCount = 3,
                    transcript = listOf(
                        DanmakuLine("1", false, "你好呀，看看我这边。", 0L),
                        DanmakuLine("2", true, "我现在在公园，给你看看这棵大树。", 0L),
                        DanmakuLine("3", false, "好漂亮的树！", 0L),
                        DanmakuLine("4", true, "今天天气特别特别好，我走了很长很长的一段路才到这里，" +
                            "路上还买了一杯咖啡，你看远处那个湖。", 0L),
                        DanmakuLine("5", false, "湖里有好多鸭子。", 0L),
                        DanmakuLine("6", true, "我想坐在这儿再待一会儿。", 0L),
                    ),
                    notice = "语音识别出错，已重新开始聆听",
                    otherCallActive = false,
                    errorMessage = null,
                    micGranted = true,
                    pendingFrameAt = 1234L,
                    voiceInput = voice.value,
                    canSend = true,
                    cameraEnabled = true,
                    previewing = true,
                    mirrored = false,
                    speakerOn = true,
                    muted = muted.value,
                    processingOrSpeaking = true,
                    calibrationDegrees = 0,
                    cameraStatus = VideoCameraStatus(),
                    cameraPermissionGranted = true,
                    shouldBindCamera = true,
                    input = "",
                    colors = colors,
                    onBack = {}, onInterrupt = {}, onSpeakerToggle = {},
                    onSwitchCamera = {}, onMirrorToggle = {}, onClipFrame = {},
                    onCameraToggle = {}, onMuteToggle = { micTaps += 1 },
                    onHangUp = {}, onInputChange = {}, onInputFocused = {}, onSend = {},
                    onCameraPermission = {}, onCalibrate = {},
                    giftEffect = null, onGiftSkip = {},
                    preview = preview(Color(0xFF2E6F8E), Color(0xFF1B3A4B)),
                )
            }
        }
        compose.onNodeWithText("正在聆听").assertIsDisplayed()
        compose.onAllNodesWithText("停约2秒", substring = true).assertCountEquals(0)
        snapshot("video-call-listening.png")
        val mic = compose.onNodeWithContentDescription("麦克风")
        mic.assertIsOn().performClick()
        compose.runOnIdle { assertTrue(micTaps == 1) }
        compose.runOnIdle { voice.value = voice.value.copy(status = VideoVoiceStatus.Transcribing) }
        compose.onNodeWithText("正在转写").assertIsDisplayed()
        compose.onAllNodesWithText("正在聆听").assertCountEquals(0)
        mic.assertIsOn().performClick()
        compose.runOnIdle { assertTrue("转写期间仍能静音", micTaps == 2) }
        compose.runOnIdle { muted.value = true }
        mic.assertIsOff()
        compose.runOnIdle {
            muted.value = false
            voice.value = voice.value.copy(status = VideoVoiceStatus.Idle)
        }
        compose.onNodeWithText("在线").assertIsDisplayed()
        snapshot("video-call-fullscreen.png")
        compose.runOnIdle { callStatus.value = VoiceCallStatus.Speaking }
        compose.onNodeWithText("准备朗读").assertIsDisplayed()
        compose.onAllNodesWithText("正在讲话").assertCountEquals(0)
        compose.runOnIdle { playing.value = true }
        compose.onNodeWithText("正在讲话").assertIsDisplayed()
        snapshot("video-call-speaking.png")
        compose.runOnIdle {
            playing.value = false
            callStatus.value = VoiceCallStatus.Listening
            voice.value = voice.value.copy(status = VideoVoiceStatus.Recording)
        }
        compose.onNodeWithText("正在聆听").assertIsDisplayed()
    }

    /**
     * 键盘弹起 = 打字模式：底栏只剩输入 pill（发送仍在 pill 内），
     * 麦克风和挂断**不布局**——发送键旁边没有挂断，不会误点。
     */
    @Test
    fun typingModeKeepsOnlyTheInputPill() {
        val typing = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                VideoCallLayout(
                    assistantName = "橘子",
                    assistantAvatar = Avatar.Dummy,
                    presence = VideoCallPresence.Online,
                    duration = "00:05",
                    giftCount = 0,
                    transcript = listOf(DanmakuLine("1", false, "你好", 0L)),
                    notice = null,
                    otherCallActive = false,
                    errorMessage = null,
                    micGranted = true,
                    pendingFrameAt = null,
                    voiceInput = VideoVoiceUiState(
                        status = VideoVoiceStatus.Idle,
                        route = VideoVoiceRoute.ASR,
                    ),
                    canSend = true,
                    cameraEnabled = true,
                    previewing = true,
                    mirrored = false,
                    speakerOn = true,
                    muted = true,
                    processingOrSpeaking = false,
                    typing = typing.value,
                    calibrationDegrees = 0,
                    cameraStatus = VideoCameraStatus(),
                    cameraPermissionGranted = true,
                    shouldBindCamera = true,
                    input = "正在打",
                    colors = colors,
                    onBack = {}, onInterrupt = {}, onSpeakerToggle = {},
                    onSwitchCamera = {}, onMirrorToggle = {}, onClipFrame = {},
                    onCameraToggle = {}, onMuteToggle = {},
                    onHangUp = {}, onInputChange = {}, onInputFocused = {}, onSend = {},
                    onCameraPermission = {}, onCalibrate = {},
                    giftEffect = null, onGiftSkip = {},
                    preview = preview(Color(0xFF2E6F8E), Color(0xFF1B3A4B)),
                )
            }
        }
        val input = compose.onNodeWithText("正在打")
        val collapsedWidth = input.fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { typing.value = true }
        repeat(5) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val expandingWidth = input.fetchSemanticsNode().boundsInRoot.width
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val expandedWidth = input.fetchSemanticsNode().boundsInRoot.width
        assertTrue("输入框应逐步拉长: $collapsedWidth -> $expandingWidth -> $expandedWidth",
            expandingWidth > collapsedWidth && expandingWidth < expandedWidth)
        compose.onAllNodesWithContentDescription("挂断").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("麦克风").assertCountEquals(0)
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        snapshot("video-call-typing.png")

        compose.runOnUiThread { typing.value = false }
        repeat(5) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val shrinkingWidth = input.fetchSemanticsNode().boundsInRoot.width
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertTrue("输入框应逐步缩回", shrinkingWidth > collapsedWidth && shrinkingWidth < expandedWidth)
        assertTrue(kotlin.math.abs(input.fetchSemanticsNode().boundsInRoot.width - collapsedWidth) < 1f)
        compose.onNodeWithContentDescription("挂断").assertIsDisplayed()
    }

    /** 字幕钉底：新行进来之后，最后一行必须真的可见（不依赖布局信息推断）。 */
    @Test
    fun transcriptStaysPinnedToTheNewestLine() {
        val lines = (1..30).map { DanmakuLine("$it", it % 2 == 0, "第 $it 行字幕", it.toLong()) }
        compose.setContent {
            MaterialTheme {
                VideoCallLayout(
                    assistantName = "橘子",
                    assistantAvatar = Avatar.Dummy,
                    presence = VideoCallPresence.Online,
                    duration = "02:00",
                    giftCount = 0,
                    transcript = lines,
                    notice = null,
                    otherCallActive = false,
                    errorMessage = null,
                    micGranted = true,
                    pendingFrameAt = null,
                    voiceInput = VideoVoiceUiState(
                        status = VideoVoiceStatus.Idle,
                        route = VideoVoiceRoute.ASR,
                    ),
                    canSend = true,
                    cameraEnabled = true,
                    previewing = true,
                    mirrored = false,
                    speakerOn = true,
                    muted = false,
                    processingOrSpeaking = false,
                    calibrationDegrees = 0,
                    cameraStatus = VideoCameraStatus(),
                    cameraPermissionGranted = true,
                    shouldBindCamera = true,
                    input = "",
                    colors = colors,
                    onBack = {}, onInterrupt = {}, onSpeakerToggle = {},
                    onSwitchCamera = {}, onMirrorToggle = {}, onClipFrame = {},
                    onCameraToggle = {}, onMuteToggle = {},
                    onHangUp = {}, onInputChange = {}, onInputFocused = {}, onSend = {},
                    onCameraPermission = {}, onCalibrate = {},
                    giftEffect = null, onGiftSkip = {},
                    preview = preview(Color(0xFF2E6F8E), Color(0xFF1B3A4B)),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("第 30 行字幕").assertIsDisplayed()
    }

    @Test
    fun rendersCameraOffState() {
        compose.setContent {
            MaterialTheme {
                VideoCallLayout(
                    assistantName = "橘子",
                    assistantAvatar = Avatar.Dummy,
                    presence = VideoCallPresence.Online,
                    duration = "01:12",
                    giftCount = 0,
                    transcript = emptyList(),
                    notice = null,
                    otherCallActive = false,
                    errorMessage = null,
                    micGranted = true,
                    pendingFrameAt = null,
                    voiceInput = VideoVoiceUiState(
                        status = VideoVoiceStatus.Idle,
                        route = VideoVoiceRoute.ASR,
                    ),
                    canSend = true,
                    cameraEnabled = false,
                    previewing = false,
                    mirrored = false,
                    speakerOn = true,
                    muted = false,
                    processingOrSpeaking = false,
                    calibrationDegrees = 0,
                    cameraStatus = VideoCameraStatus(),
                    cameraPermissionGranted = true,
                    shouldBindCamera = false,
                    input = "",
                    colors = colors,
                    onBack = {}, onInterrupt = {}, onSpeakerToggle = {},
                    onSwitchCamera = {}, onMirrorToggle = {}, onClipFrame = {},
                    onCameraToggle = {}, onMuteToggle = {},
                    onHangUp = {}, onInputChange = {}, onInputFocused = {}, onSend = {},
                    onCameraPermission = {}, onCalibrate = {},
                    giftEffect = null, onGiftSkip = {},
                    preview = preview(Color(0xFF2E6F8E), Color(0xFF1B3A4B)),
                )
            }
        }
        snapshot("video-call-camera-off.png")
    }

    @Test
    fun presenceReflectsRecordingAndActualPlayback() {
        val online = VoiceCallUiState(status = VoiceCallStatus.Listening)
        assertEquals(VideoCallPresence.Online, videoCallPresence(online))
        val recording = online.copy(voiceInput = VideoVoiceUiState(status = VideoVoiceStatus.Recording))
        assertEquals(VideoCallPresence.Listening, videoCallPresence(recording))
        assertEquals(VideoCallPresence.Online, videoCallPresence(recording.copy(isMuted = true)))
        assertEquals(VideoCallPresence.Transcribing,
            videoCallPresence(online.copy(voiceInput = VideoVoiceUiState(status = VideoVoiceStatus.Transcribing))))
        assertEquals(VideoCallPresence.Sending,
            videoCallPresence(online.copy(voiceInput = VideoVoiceUiState(status = VideoVoiceStatus.Sending))))
        assertEquals(VideoCallPresence.Thinking, videoCallPresence(online.copy(status = VoiceCallStatus.Processing)))
        val preparing = online.copy(status = VoiceCallStatus.Speaking)
        assertEquals(VideoCallPresence.PreparingSpeech, videoCallPresence(preparing))
        assertEquals(VideoCallPresence.Speaking, videoCallPresence(preparing.copy(isAssistantSpeaking = true)))
        assertEquals(VideoCallPresence.Speaking,
            videoCallPresence(online.copy(status = VoiceCallStatus.Processing, isAssistantSpeaking = true)))
        assertEquals(VideoCallPresence.Ready, videoCallPresence(VoiceCallUiState()))
        assertEquals(VideoCallPresence.Error, videoCallPresence(recording.copy(status = VoiceCallStatus.Error)))
    }

    @Test
    fun listeningCharactersMoveInSequenceWithOneAccessibleLabel() {
        val phase = mutableStateOf(.1f)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF18374A)).padding(20.dp)) {
                    VideoCallStatusText(VideoCallPresence.Listening, phase)
                }
            }
        }
        compose.onNodeWithText("正在聆听").assertIsDisplayed()
        val first = compose.onNodeWithTag("listening-letter-0", useUnmergedTree = true)
        val second = compose.onNodeWithTag("listening-letter-1", useUnmergedTree = true)
        val firstRaised = first.fetchSemanticsNode().boundsInRoot.top
        val secondResting = second.fetchSemanticsNode().boundsInRoot.top
        assertTrue(firstRaised < secondResting)
        compose.runOnIdle { phase.value = .24f }
        assertTrue(second.fetchSemanticsNode().boundsInRoot.top < first.fetchSemanticsNode().boundsInRoot.top)
        assertTrue(listeningLetterPulse(.1f, 0) > listeningLetterPulse(.1f, 1))
        assertTrue(listeningLetterPulse(.24f, 1) > listeningLetterPulse(.24f, 0))
        assertFalse(listeningLetterPulse(.9f, 0) > 0f)
    }

    @Test
    fun presenceMotionRunsWithTheFrameClock() {
        val savedScale = ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java, "sDurationScale")
        var phase: State<Float>? = null
        try {
            ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", 1f)
            compose.mainClock.autoAdvance = false
            compose.setContent {
                MaterialTheme {
                    val animation = rememberPresencePhase(VideoCallPresence.Listening)
                    phase = animation
                    Box(Modifier.fillMaxSize().background(Color(0xFF18374A)).padding(20.dp)) {
                        VideoCallStatusText(VideoCallPresence.Listening, animation)
                    }
                }
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(160L)
            compose.waitForIdle()
            assertTrue("聆听动效必须真正随时钟推进", phase!!.value > 0f)
            compose.onNodeWithText("正在聆听").assertIsDisplayed()
            snapshot("video-call-listening-letters.png")
        } finally {
            ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", savedScale)
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun presenceStaysReadableWithSystemAnimationsDisabled() {
        val savedScale = ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java, "sDurationScale")
        var phase: State<Float>? = null
        try {
            ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", 0f)
            assertFalse(ValueAnimator.areAnimatorsEnabled())
            compose.setContent {
                MaterialTheme {
                    val animation = rememberPresencePhase(VideoCallPresence.Listening)
                    phase = animation
                    Box(Modifier.fillMaxSize().background(Color(0xFF18374A)).padding(20.dp)) {
                        VideoCallStatusText(VideoCallPresence.Listening, animation)
                    }
                }
            }
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            assertEquals(0f, phase!!.value, 0f)
            compose.onNodeWithText("正在聆听").assertIsDisplayed()
            val first = compose.onNodeWithTag("listening-letter-0", useUnmergedTree = true)
            val second = compose.onNodeWithTag("listening-letter-1", useUnmergedTree = true)
            assertEquals(first.fetchSemanticsNode().boundsInRoot.top,
                second.fetchSemanticsNode().boundsInRoot.top, .1f)
            snapshot("video-call-listening-reduced-motion.png")
        } finally {
            ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", savedScale)
        }
    }

    /** 32dp 聊天气泡尺寸的 [AvatarDisplay]（首字 / Emoji），核对 autoSize 不至于太小。 */
    @Test
    fun rendersSmallChatAvatars() {
        compose.setContent {
            MaterialTheme {
                Row {
                    AvatarDisplay("橘子", Avatar.Dummy, Modifier.size(32.dp))
                    AvatarDisplay("橘子", Avatar.Emoji("🍊"), Modifier.size(32.dp))
                }
            }
        }
        snapshot("avatar-32dp.png")
    }

    private fun snapshot(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = System.getProperty("videoCallSnapshotDir") ?: "build/test-screenshots"
        val output = File(dir, name)
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
