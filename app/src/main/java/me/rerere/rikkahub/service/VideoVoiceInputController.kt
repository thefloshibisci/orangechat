/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.video.AsrSegmentResult
import me.rerere.rikkahub.data.video.AsrSegmentWatcher
import me.rerere.rikkahub.data.video.DirectSegmentResult
import me.rerere.rikkahub.data.video.TransientAudioClip
import me.rerere.rikkahub.data.video.VideoVoiceActivity
import me.rerere.rikkahub.data.video.VideoVoiceAvailability
import me.rerere.rikkahub.data.video.VideoVoicePolicy
import me.rerere.rikkahub.data.video.VideoVoiceRoute
import me.rerere.rikkahub.data.video.VideoVoiceStatus
import me.rerere.rikkahub.data.video.VideoVoiceUiState
import me.rerere.rikkahub.data.video.directSegmentResult
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.ManualVoiceRecorder
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import java.io.File

private const val CONTROLLER_TAG = "VoiceCallService"

/** 视频面板里打的字只用来显示在字幕上，不许无限增长。 */
internal const val MAX_TYPED_TRANSCRIPT = 200

/** 视频通话"说完自动发送"的判定节拍：150ms 一次，比人停顿的感知更细。 */
private const val VIDEO_SILENCE_POLL_MS = 150L

internal interface VideoVoiceHost {
    val serviceScope: CoroutineScope
    val uiState: MutableStateFlow<VoiceCallUiState>
    val videoMode: Boolean
    val callEnded: Boolean
    val hasAsr: Boolean
    val currentAsr: CustomAsrState?
    val sessionToken: Long
    val videoCoordinator: VideoCallCoordinator?
    val settingsStore: SettingsStore
    fun hasMicrophonePermission(): Boolean
    fun videoVoiceDirectory(): File
    suspend fun videoVoiceDelay(ms: Long)
    suspend fun videoVoiceSilenceDelay(ms: Long)
    fun videoVoiceNowMillis(): Long
    val videoAsrRecordingTimeoutMs: Long
    val videoAsrTranscriptionTimeoutMs: Long
    fun createVoiceRecorder(): ManualVoiceRecorder
    fun directAudioSupported(): Boolean
    fun asrConfigured(): Boolean
    fun invalidateAsrPipeline()
    fun startInitialization()
    fun onHandsFreeChanged(enabled: Boolean)
    fun prepareInterruptForNewSegment()
    fun beginTurn(
        text: String,
        fromVoice: Boolean,
        bubbleText: String,
        transientAudio: TransientAudioClip?,
    ): Boolean
}

/**
 * Coordinates manual / hands-free segmented voice input in video call mode.
 *
 * Keeps segment generation tokens, silence auto-send polling, and transient clip
 * ownership isolated from Android Service / foreground lifecycle code.
 */
internal class VideoVoiceInputController(
    private val host: VideoVoiceHost,
) {
    private var videoVoice: VideoVoiceUiState
        get() = host.uiState.value.voiceInput
        set(value) {
            // 单段录音状态不代表用户关闭了麦克风；等回复时仍保留免提意图。
            // 静音字段与 UI 在 handsFree 变化时一并更新。
            host.uiState.update { it.copy(voiceInput = value) }
        }

    val currentState: VideoVoiceUiState
        get() = videoVoice

    /** 当前这一段的代次：任何旧回调/旧协程发现代次不对就不许动状态。 */
    private var videoVoiceSegment: Long = 0L
    private var videoVoiceJob: Job? = null
    private var videoVoiceDeadlineJob: Job? = null
    private var videoVoiceRecorder: ManualVoiceRecorder? = null
    private var videoVoiceStartedAt: Long? = null
    private var videoVoiceStopRequested = false
    private var videoVoiceRoute: VideoVoiceRoute? = null

    /** "说完自动发送"的判定器；每段开始时 reset。 */
    private var videoVoiceActivity = VideoVoiceActivity()
    private var videoVoiceSilenceJob: Job? = null

    /**
     * 免提：用户点过一次麦克风之后，这一段发出去、助手答完，自动接着听下一段。
     * 点输入框打字、手动结束、取消、挂断都会关掉它。
     */
    var handsFree = false
        set(value) {
            field = value
            host.onHandsFreeChanged(value)
        }

    private var videoAsrActive = false

    /** 已经录好、还没被 [TransientAudioClip] 接管的文件（取消时要删掉）。 */
    private var videoVoicePendingPath: String? = null

    /**
     * 最近一次手动录音相关的面板提示（用户能看到的原文）。
     *
     * 单独留一份的原因：面板提示走 `video?.notify(...)`，而协调器在服务集成回归里是注入点
     * （测试里为 null）。保存原文既能断言"到上限时确实提示了用户"，也方便真机排查。
     */
    var lastNotice: String? = null
        private set

    /** 当前是否真的有一段在录/在转写/在发送（面板提示只在真的有事发生时出现）。 */
    val isActive: Boolean
        get() = videoVoice.status == VideoVoiceStatus.Recording ||
            videoVoice.status == VideoVoiceStatus.Transcribing ||
            videoVoice.status == VideoVoiceStatus.Sending

    fun notify(text: String) {
        if (text.isBlank()) return
        lastNotice = text
        host.videoCoordinator?.notify(text)
    }

    /**
     * 面板上的那一颗麦克风：与语音通话同一种手感。
     *
     * - 空闲时点一下 = **开始免提聆听**：说完停下来（[VideoVoiceActivity]）自动发出去，
     *   助手答完自动接着听下一段，不用每次都点两下；
     * - 录音中再点一下 = 立刻发送这一段，并且**退出免提**（之后不再自动接听）；
     * - 转写中/发送中重复点按一律忽略（绝不重复提交同一段）。
     *
     * @return true 表示这次点按真的开始了一段或结束并提交了一段。
     */
    fun toggle(): Boolean {
        if (!host.videoMode || host.callEnded) return false
        return when (videoVoice.status) {
            VideoVoiceStatus.Recording -> {
                // 手动点停 = 明确的"就到这里，别再自动听着了"。
                handsFree = false
                finishSegment()
                true
            }

            VideoVoiceStatus.Idle, VideoVoiceStatus.Unavailable -> {
                handsFree = true
                startSegment()
            }

            VideoVoiceStatus.Transcribing, VideoVoiceStatus.Sending -> false
        }
    }

    /** 页面退到后台：未发送的那一段作废（**不发送**），并停掉采集。 */
    fun onHostBackground() {
        if (!host.videoMode) return
        handsFree = false
        if (isActive) {
            cancel("已离开视频页：这一段录音没有发送")
        } else {
            // 没在录也照样停掉采集（回到前台时是干净状态）。
            releaseCapture(discard = true)
        }
    }

    /**
     * 取消正在录/正在转写的一段（闭麦、打断、挂断、退回后台、权限撤回都走这里）。
     *
     * 取消 = 停止采集 + 取消未发送的录音 + **不发消息**；这是与"结束并发送"的唯一区别。
     *
     * @param reason 面板提示文案（空串表示静默清理）。
     */
    fun cancel(reason: String = "") {
        if (!host.videoMode) return
        host.videoCoordinator?.discardFrozenFrames()
        // 取消 = 退出免提：用户刚刚明确表示"不要这一段"，再自动接着听会很吓人。
        handsFree = false
        videoVoiceSegment += 1
        videoVoiceJob?.cancel()
        videoVoiceJob = null
        videoVoiceDeadlineJob?.cancel()
        videoVoiceDeadlineJob = null
        stopSilenceWatch()
        videoVoiceStopRequested = false
        releaseCapture(discard = true)
        host.uiState.update { it.copy(amplitudes = emptyList()) }
        // The reason is a one-shot panel notice, not persistent availability state.
        videoVoice = refreshState()
        if (reason.isNotBlank()) notify(reason)
    }

    /**
     * 权限/设置变化之后的显式刷新（麦克风授权、ASR 配置变化）。
     *
     * 正在录/正在转写/正在发送时**不覆盖**当前状态（不能让一次刷新把录音状态清成 Idle）。
     * 一次性失败原因只走面板提示，不在这里回填；`Unavailable` + reason 只表示能力真的
     * 不可用（权限/ASR 配置/模型），由 [refreshState] 按 availability 决定。
     */
    fun refresh() {
        if (!host.videoMode || host.callEnded) return
        if (videoVoice.isActionable && videoVoice.isRecording) return
        if (videoVoice.isBusy) return
        val refreshed = refreshState()
        if (refreshed.status == VideoVoiceStatus.Unavailable) handsFree = false
        videoVoice = refreshed
    }

    fun availability(): VideoVoiceAvailability = VideoVoicePolicy.resolve(
        mode = host.settingsStore.settingsFlow.value.videoCallSetting.voiceInputMode,
        directAudioSupported = runCatching { host.directAudioSupported() }.getOrDefault(false),
        asrConfigured = runCatching { host.asrConfigured() }.getOrDefault(false),
        microphoneGranted = host.hasMicrophonePermission(),
    )

    fun toggleMuteInVideoMode() {
        if (handsFree) {
            cancel()
        } else {
            handsFree = true
            if (!videoVoice.isBusy) startSegment()
        }
    }

    fun muteForTypingInVideoMode() {
        if (!host.videoMode || host.callEnded) return
        // 去打字就是明确的"别再自动听着"：免提在这里关掉（转写中的那一段仍照常发完）。
        handsFree = false
        if (videoVoice.status == VideoVoiceStatus.Recording) {
            cancel("已切换到打字：刚才那一段录音没有发送")
        }
    }

    fun onTypedTextSubmitted() {
        if (host.videoMode) handsFree = false
        // 打字发送 = 明确的"改说文字"：正在录/正在转写的那一段作废（不自动提交）。
        if (host.videoMode && videoVoice.status != VideoVoiceStatus.Idle) {
            cancel("已改用文字发送，刚才那一段录音已取消")
        }
    }

    /**
     * 免提续听：上一轮已经自然答完，接着听下一段。
     *
     * 只在"还在 Listening + 空闲 + 真的开了免提"时才接，任何一步不满足就保持安静。
     */
    fun resumeHandsFree() {
        if (!host.videoMode || host.callEnded || !handsFree) return
        if (videoVoice.status != VideoVoiceStatus.Idle) return
        if (host.uiState.value.status != VoiceCallStatus.Listening) return
        if (availability().route == VideoVoiceRoute.ASR && !host.hasAsr) {
            host.startInitialization()
            return
        }
        startSegment(interrupt = false)
    }

    fun disposeRecorder() {
        videoVoiceRecorder?.let { runCatching { it.cleanup() } }
        videoVoiceRecorder = null
    }

    /** 开始一段（换会话/挂断时也会被重置）。 */
    fun resetState() {
        videoVoiceSegment += 1
        videoVoiceJob?.cancel()
        videoVoiceJob = null
        videoVoiceDeadlineJob?.cancel()
        videoVoiceDeadlineJob = null
        stopSilenceWatch()
        handsFree = false
        videoVoiceStopRequested = false
        videoVoicePendingPath = null
        videoVoiceRoute = null
        videoVoiceStartedAt = null
        videoVoice = VideoVoiceUiState()
    }

    /**
     * @param interrupt true = 用户点了麦克风（打断正在说的/正在生成的）；
     *        false = 免提自动接听下一段（刚刚那一轮已经自然结束，不能再去撤它的授权）。
     */
    fun startSegment(interrupt: Boolean = true): Boolean {
        if (!host.videoMode || host.callEnded) return false
        val avail = availability()
        val route = avail.route
        if (!avail.enabled || route == null) {
            handsFree = false
            // 不能点就必须说清原因（没权限/没能力/ASR 没配置），绝不显示"正在聆听"骗人。
            videoVoice = VideoVoiceUiState(
                status = VideoVoiceStatus.Unavailable,
                route = null,
                reason = avail.reason,
            )
            notify(avail.reason)
            return false
        }
        // 同一时刻只允许一个麦克风在采音：先把任何旧的采集停掉（不发送）。
        videoVoiceJob?.cancel()
        videoVoiceJob = null
        videoVoiceDeadlineJob?.cancel()
        videoVoiceDeadlineJob = null
        stopSilenceWatch()
        releaseCapture(discard = true)
        if (interrupt) {
            // 避免把自己的朗读录进去：开始采音之前先停 TTS。
            // Cancel streaming generation and withdraw its queued speech; keep its already-submitted
            // user message id for the eventual video call record.
            host.prepareInterruptForNewSegment()
        }
        // 新的这一句从干净状态开始：旧的一轮文字/振幅不许被"说完自动发送"当成已经说到话。
        host.uiState.update { it.copy(userTranscript = "", amplitudes = emptyList()) }
        videoVoiceActivity.reset()
        val segment = ++videoVoiceSegment
        videoVoiceStartedAt = host.videoVoiceNowMillis()
        videoVoiceStopRequested = false
        videoVoiceRoute = route
        val started = when (route) {
            VideoVoiceRoute.DIRECT_AUDIO -> startDirectSegment(segment)
            VideoVoiceRoute.ASR -> startAsrSegment(segment)
        }
        if (started) startSilenceWatch(segment)
        return started
    }

    private fun refreshState(reason: String = ""): VideoVoiceUiState {
        val avail = availability()
        return VideoVoiceUiState(
            status = if (avail.enabled) VideoVoiceStatus.Idle else VideoVoiceStatus.Unavailable,
            route = avail.route,
            reason = reason.ifBlank { avail.reason },
            maxDurationMs = VideoVoicePolicy.MAX_RECORDING_MS,
        )
    }

    /**
     * 这一段的已录时长。
     *
     * 优先用录音器/ASR 自己报的时长；有些 provider 一直不上报 durationMs，
     * 那就用墙钟兜底（两者取大，时间不会倒流）。
     */
    private fun elapsedForSegment(reportedMs: Long): Long {
        val started = videoVoiceStartedAt
        val wall = if (started != null) host.videoVoiceNowMillis() - started else 0L
        return maxOf(reportedMs, wall).coerceAtLeast(0L)
    }

    private fun ensureVoiceRecorder(): ManualVoiceRecorder =
        videoVoiceRecorder ?: host.createVoiceRecorder().also { videoVoiceRecorder = it }

    /**
     * 停掉这一段的采集。
     *
     * @param discard true 表示"取消"：录音器被 abort（已写出的文件当场删掉，不发布路径），
     *        并且把已经录好但还没被接管的文件也删掉。
     */
    private fun releaseCapture(discard: Boolean) {
        videoVoiceRecorder?.let { recorder ->
            if (discard) runCatching { recorder.abort() }.onFailure { Log.w(CONTROLLER_TAG, "取消录音失败", it) }
            else runCatching { recorder.stop() }.onFailure { Log.w(CONTROLLER_TAG, "停止录音失败", it) }
        }
        if (discard) {
            videoVoicePendingPath?.let { path ->
                TransientAudioClip.discardFile(File(path), host.videoVoiceDirectory())
            }
        }
        videoVoicePendingPath = null
        if (discard) {
            disposeRecorder()
            if (videoAsrActive && host.hasAsr) {
                // stop() would start a transcription request. Dispose cancels the provider request
                // and its own temporary file instead; never infer ownership from an emitted path.
                runCatching { host.currentAsr?.cleanup() }
                host.invalidateAsrPipeline()
                if (!host.callEnded && host.hasMicrophonePermission()) host.startInitialization()
            }
            videoAsrActive = false
        }
    }

    /** 这一段开始之后才启动"说完自动发送"的看守：没真的开始就不要空转。 */
    private fun startSilenceWatch(segment: Long) {
        videoVoiceSilenceJob?.cancel()
        videoVoiceSilenceJob = host.serviceScope.launch {
            while (true) {
                host.videoVoiceSilenceDelay(VIDEO_SILENCE_POLL_MS)
                if (host.callEnded || segment != videoVoiceSegment) return@launch
                if (videoVoice.status != VideoVoiceStatus.Recording) return@launch
                val now = host.videoVoiceNowMillis()
                videoVoiceActivity.onTranscript(host.uiState.value.userTranscript, now)
                if (videoVoiceActivity.shouldSend(elapsedForSegment(0L), now)) {
                    // 与语音通话同一种手感：说完不用点，自己就发出去了。
                    finishSegment(bySilence = true)
                    return@launch
                }
            }
        }
    }

    private fun stopSilenceWatch() {
        videoVoiceSilenceJob?.cancel()
        videoVoiceSilenceJob = null
        videoVoiceActivity.reset()
    }

    /** 直传路线：录音器写 WAV，写完（用户点停或到 60 秒上限）就直接进这一次请求。 */
    private fun startDirectSegment(segment: Long): Boolean {
        val recorder = ensureVoiceRecorder()
        try {
            recorder.start { /* 直传不需要 partial 文字 */ }
        } catch (error: Exception) {
            Log.e(CONTROLLER_TAG, "视频通话开始录音失败", error)
            failSegment(segment, error.message ?: "无法开始录音")
            return false
        }
        videoVoice = VideoVoiceUiState(
            status = VideoVoiceStatus.Recording,
            route = VideoVoiceRoute.DIRECT_AUDIO,
            maxDurationMs = VideoVoicePolicy.MAX_RECORDING_MS,
        )
        videoVoiceJob = host.serviceScope.launch {
            // 监听录音器自己的状态流（而不是定时轮询）：用户点停与"到 60 秒上限自动结束"
            // 走的是同一条完成路径，所以两者都会**发送**，区别只在于要不要提示"已录满"。
            recorder.state.collect { recorderState ->
                if (segment != videoVoiceSegment || host.callEnded) return@collect
                if (recorderState.isRecording) {
                    videoVoice = videoVoice.copy(elapsedMs = elapsedForSegment(recorderState.durationMs))
                    // 幅度也要露出来：既是给"说完自动发送"的输入，也让面板能显示有没有在说。
                    host.uiState.update { it.copy(amplitudes = recorderState.amplitudes.takeLast(24)) }
                    recorderState.amplitudes.lastOrNull()?.let { peak ->
                        videoVoiceActivity.onAmplitude(peak, host.videoVoiceNowMillis())
                    }
                }
                when (val result = directSegmentResult(recorderState)) {
                    null -> Unit
                    is DirectSegmentResult.Failed -> {
                        failSegment(segment, result.reason)
                    }

                    is DirectSegmentResult.Ready -> {
                        completeDirectSegment(segment, recorder, result)
                    }
                }
            }
        }
        return true
    }

    private suspend fun completeDirectSegment(
        segment: Long,
        recorder: ManualVoiceRecorder,
        ready: DirectSegmentResult.Ready,
    ) {
        if (segment != videoVoiceSegment || host.callEnded) {
            TransientAudioClip.discardFile(File(ready.path), host.videoVoiceDirectory())
            return
        }
        // 到上限自动结束也要发送，但必须明确提示（不允许悄悄吞掉那一段）。
        val reachedLimit = recorder.reachedLimit
        videoVoice = videoVoice.copy(
            status = VideoVoiceStatus.Sending,
            elapsedMs = ready.durationMs,
            endedByLimit = reachedLimit,
        )
        videoVoicePendingPath = ready.path
        val clip = TransientAudioClip.adopt(
            file = File(ready.path),
            directory = host.videoVoiceDirectory(),
            durationMs = ready.durationMs,
        )
        if (clip == null) {
            videoVoicePendingPath = null
            failSegment(segment, "录音文件不可用，请重新录一段")
            return
        }
        // 所有权移交：文件从此由 clip 负责，取消路径也要经它才能删。
        videoVoicePendingPath = null
        if (reachedLimit) notify(VideoVoicePolicy.limitReachedNotice())
        submitTurn(
            segment = segment,
            text = VideoVoicePolicy.directVoicePlaceholder(ready.durationMs),
            bubbleText = "🎙️ 语音 ${VideoVoicePolicy.formatDuration(ready.durationMs)}",
            clip = clip,
        )
    }

    /** ASR 路线：复用既有 ASR 工厂，但由**手动**的那一次点击驱动。 */
    private fun startAsrSegment(segment: Long): Boolean {
        val asrStream = host.currentAsr
        if (asrStream == null) {
            failSegment(segment, "语音识别还没准备好，请稍后再试，或者直接打字")
            return false
        }
        videoAsrActive = true
        val token = host.sessionToken
        // watcher 先把"启动这一刻的状态"喂一遍：即使状态流的中间值被合并掉（provider 很快就
        // Listening → Idle），"这一段确实录过"这个事实也不会丢，不会把用户卡在"转写中"。
        // stopRequested=false，所以启动之前的旧 Idle 不会被当成本次结果。
        val watcher = AsrSegmentWatcher()
        watcher.observe(asrStream.state.value, stopRequested = false)
        try {
            asrStream.start { partial ->
                // partial 只用来展示，不参与"这一段说完了没有"的判断。
                if (segment == videoVoiceSegment && partial.isNotBlank()) {
                    videoVoiceActivity.onTranscript(partial, host.videoVoiceNowMillis())
                    host.uiState.update { it.copy(userTranscript = partial.take(MAX_TYPED_TRANSCRIPT)) }
                }
            }
        } catch (error: Exception) {
            Log.e(CONTROLLER_TAG, "视频通话语音识别启动失败", error)
            failSegment(segment, error.message ?: "语音识别启动失败")
            return false
        }
        // start() 是同步返回的 provider 会立刻进入 Connecting/Listening：再看一次，别漏。
        watcher.observe(asrStream.state.value, stopRequested = false)
        videoVoice = VideoVoiceUiState(
            status = VideoVoiceStatus.Recording,
            route = VideoVoiceRoute.ASR,
            maxDurationMs = VideoVoicePolicy.MAX_RECORDING_MS,
        )
        videoVoiceJob = host.serviceScope.launch {
            asrStream.state.collect { state ->
                // 会话号 + ASR 实例 + 段代次三重校验：旧会话/旧实例的回调一律不许动状态。
                if (segment != videoVoiceSegment || host.callEnded) return@collect
                if (token != host.sessionToken || !host.hasAsr || asrStream !== host.currentAsr) return@collect
                host.uiState.update { it.copy(amplitudes = state.amplitudes) }
                if (state.isRecording) {
                    videoVoice = videoVoice.copy(elapsedMs = elapsedForSegment(state.durationMs))
                    state.amplitudes.lastOrNull()?.let { peak ->
                        videoVoiceActivity.onAmplitude(peak, host.videoVoiceNowMillis())
                    }
                }
                when (val result = watcher.observe(state, videoVoiceStopRequested)) {
                    null -> Unit
                    is AsrSegmentResult.Failed ->
                        failSegment(segment, result.reason)

                    AsrSegmentResult.Empty ->
                        failSegment(segment, "没有识别到内容，请重新录一段，或者直接打字")

                    is AsrSegmentResult.Transcript -> {
                        videoVoice = videoVoice.copy(
                            status = VideoVoiceStatus.Sending,
                            elapsedMs = state.durationMs,
                        )
                        submitTurn(
                            segment = segment,
                            text = result.text,
                            bubbleText = result.text.take(MAX_TYPED_TRANSCRIPT),
                            clip = null,
                        )
                    }
                }
            }
        }
        videoVoiceDeadlineJob = host.serviceScope.launch {
            host.videoVoiceDelay(host.videoAsrRecordingTimeoutMs)
            if (segment == videoVoiceSegment && !host.callEnded &&
                videoVoice.status == VideoVoiceStatus.Recording) {
                videoVoice = videoVoice.copy(endedByLimit = true)
                notify("已经录满 60 秒，录音已自动结束，正在转写")
                finishSegment()
            }
        }
        return true
    }

    /** 用户再点一次 = 结束这一段；[bySilence] = 说完停顿后自动结束。 */
    private fun finishSegment(bySilence: Boolean = false) {
        if (videoVoice.status != VideoVoiceStatus.Recording) return
        videoVoice = videoVoice.copy(endedBySilence = bySilence)
        stopSilenceWatch()
        // 截止在"用户说完/点结束"这一刻：先把此刻的滚动帧钉住，
        // 转写/收尾花掉的几秒不再偷换成更晚的画面。
        host.videoCoordinator?.freezeFramesForTurn()
        videoVoiceStopRequested = true
        if (bySilence) notify("听到你停下来了，这一段正在发出去")
        when (videoVoiceRoute) {
            VideoVoiceRoute.DIRECT_AUDIO -> {
                videoVoice = videoVoice.copy(status = VideoVoiceStatus.Sending)
                releaseCapture(discard = false)
            }

            VideoVoiceRoute.ASR -> {
                videoVoice = videoVoice.copy(status = VideoVoiceStatus.Transcribing)
                videoVoiceDeadlineJob?.cancel()
                val segment = videoVoiceSegment
                videoVoiceDeadlineJob = host.serviceScope.launch {
                    host.videoVoiceDelay(host.videoAsrTranscriptionTimeoutMs)
                    if (segment == videoVoiceSegment && !host.callEnded &&
                        videoVoice.status == VideoVoiceStatus.Transcribing) {
                        failSegment(segment, "语音转写超时，这一段没有发送，请重试或直接打字")
                    }
                }
                host.currentAsr?.let { activeAsr ->
                    runCatching { activeAsr.stop() }.onFailure {
                        failSegment(segment, "停止语音识别失败：这一段没有发送")
                    }
                }
            }

            null -> Unit
        }
    }

    /**
     * 把这一段的结论交给正常的通话回合链（同一队列、同一 TTS、同一挂断清理）。
     *
     * [clip] 非空表示音频直传：附件所有权会随请求一起移交；提交失败则当场释放。
     */
    private suspend fun submitTurn(
        segment: Long,
        text: String,
        bubbleText: String,
        clip: TransientAudioClip?,
    ) {
        if (segment != videoVoiceSegment || host.callEnded) {
            clip?.release(fromRequest = false)
            return
        }
        if (clip != null && !runCatching { host.directAudioSupported() }.getOrDefault(false)) {
            clip.release(fromRequest = false)
            failSegment(segment, "模型或通道已变化，不再支持音频直传：这段录音没有发送，请重新录音或打字")
            return
        }
        videoVoice = videoVoice.copy(status = VideoVoiceStatus.Sending)
        if (videoAsrActive && host.hasAsr) {
            val completedAsr = host.currentAsr
            host.invalidateAsrPipeline()
            runCatching { completedAsr?.cleanup() }
            if (host.hasMicrophonePermission()) host.startInitialization()
        }
        videoAsrActive = false
        stopSilenceWatch()
        disposeRecorder()
        host.uiState.update { it.copy(amplitudes = emptyList()) }
        val accepted = runCatching {
            host.beginTurn(
                text = text,
                fromVoice = false,
                bubbleText = bubbleText,
                transientAudio = clip,
            )
        }.getOrDefault(false)
        if (!accepted) {
            clip?.release(fromRequest = false)
            failSegment(segment, "这一轮没有发出去，请再录一次或直接打字")
            return
        }
        videoVoice = VideoVoiceUiState(
            status = VideoVoiceStatus.Idle,
            route = videoVoiceRoute,
            maxDurationMs = VideoVoicePolicy.MAX_RECORDING_MS,
        )
        // 这一段已经交给请求了：观察协程没有别的事要做（下一段会重新起一个）。
        videoVoiceJob?.cancel()
        videoVoiceJob = null
        videoVoiceDeadlineJob?.cancel()
        videoVoiceDeadlineJob = null
    }

    /** 这一段失败：停采集、清文件，回到可再录的 Idle；原因只走一次性面板提示，
     * 不写进持久状态（否则按钮会一直停在"不可用"）。 */
    private fun failSegment(segment: Long, reason: String) {
        if (segment != videoVoiceSegment) return
        handsFree = false
        host.videoCoordinator?.discardFrozenFrames()
        stopSilenceWatch()
        videoVoiceSegment += 1
        videoVoiceJob?.cancel()
        videoVoiceJob = null
        videoVoiceDeadlineJob?.cancel()
        videoVoiceDeadlineJob = null
        videoVoiceStopRequested = false
        releaseCapture(discard = true)
        host.uiState.update { it.copy(amplitudes = emptyList(), userTranscript = "") }
        videoVoice = VideoVoiceUiState(
            status = VideoVoiceStatus.Idle,
            route = videoVoiceRoute,
            reason = "",
            maxDurationMs = VideoVoicePolicy.MAX_RECORDING_MS,
        )
        notify(reason)
    }
}
