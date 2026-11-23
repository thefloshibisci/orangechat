/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import kotlinx.serialization.Serializable
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus

/**
 * 视频通话里"我说一句"的输入方式（用户可选项，持久设置）。
 *
 * 存在的理由：真机反馈明确要求"不能只有自动、改不了"——音频能力模型也要能选语音转文字，
 * 不支持直传的模型则只能走 ASR。所以这是一个**用户偏好**，最终走哪条路由
 * [VideoVoicePolicy.resolve] 按真实能力裁决。
 */
@Serializable
enum class VideoVoiceInputMode {
    /** 自动：能直传就直传，否则退到已配置的语音识别。 */
    AUTO,

    /** 强制音频直传（模型/通道不支持时明确禁用，不偷偷降级成 ASR）。 */
    DIRECT,

    /** 强制语音转文字（即使模型能直接听音频）。 */
    ASR,
}

/** 这一通电话里**实际**走哪条路。 */
enum class VideoVoiceRoute {
    /** 录音作为 WAV 直接进这一次请求（Google inlineData / OpenAI ChatCompletions input_audio）。 */
    DIRECT_AUDIO,

    /** 录音只用来转写成文字，请求里没有音频。 */
    ASR,
}

/** 录制按钮/闭麦之外的"这一段录音"状态。 */
enum class VideoVoiceStatus {
    /** 没有在录：按钮可点（能力可用时）。 */
    Idle,

    /** 正在录（手动开始，必须再点一次才结束）。 */
    Recording,

    /** ASR 已停，等最后一段完整文字。 */
    Transcribing,

    /** 已经拿到录音/文字，正在提交这一轮请求。 */
    Sending,

    /** 这条路由不可用（没权限/没能力/ASR 没配置）。按钮禁用，**绝不**显示"正在聆听"。 */
    Unavailable,
}

/** 能力裁决结果：走哪条路、能不能点、不能点时准确原因。 */
data class VideoVoiceAvailability(
    val route: VideoVoiceRoute? = null,
    val enabled: Boolean = false,
    val reason: String = "",
)

/**
 * 视频面板上这一段手动录音的 UI 状态（纯数据）。
 *
 * 默认值就是"安全 Idle"：进入视频页**不会**自动采音，也不会显示任何"正在聆听"的假状态。
 */
data class VideoVoiceUiState(
    val status: VideoVoiceStatus = VideoVoiceStatus.Idle,
    val route: VideoVoiceRoute? = null,
    /** 不可用/失败时的准确原因（会显示在按钮下方）。 */
    val reason: String = "",
    val elapsedMs: Long = 0L,
    val maxDurationMs: Long = VideoVoicePolicy.MAX_RECORDING_MS,
    /** 这一段是不是因为到达上限自动结束的（结束后会提示，不会悄悄吞掉）。 */
    val endedByLimit: Boolean = false,
    /** 这一段是不是"说完停下来自动发出去"的（提示用词与手动结束不同）。 */
    val endedBySilence: Boolean = false,
) {
    val isRecording: Boolean get() = status == VideoVoiceStatus.Recording
    val isBusy: Boolean
        get() = status == VideoVoiceStatus.Transcribing || status == VideoVoiceStatus.Sending
    /** 按钮是否可以点（录音中也可以点：那就是"结束并发送"）。 */
    val isActionable: Boolean
        get() = status == VideoVoiceStatus.Idle || status == VideoVoiceStatus.Recording
}

/**
 * 手动录音的全部纯判断：路由裁决、按钮文案、占位文本、限时。
 *
 * 放在纯 Kotlin 文件里（只依赖 speech 模块的 [ASRState]），所以 60 秒上限、
 * 时长/文件大小换算、ASR 启动前旧 Idle 不算结果这些边界都能在 JVM 单测里逐条钉住，
 * 不需要真机、也不需要 Robolectric。
 */
object VideoVoicePolicy {
    /** 视频通话里一段录音的硬上限：60 秒（**不复用**聊天语音的 300 秒）。 */
    const val MAX_RECORDING_MS: Long = 60_000L

    /** 自动发送：至少先录这么久，避免刚点下去就被判定成"停下来了"。 */
    const val AUTO_SEND_MIN_DURATION_MS: Long = 600L

    /** 自动发送：连续安静两秒才认为说完，给换气和接着说话留出余地。 */
    const val AUTO_SEND_SILENCE_MS: Long = 2_000L

    /** 采样峰值超过它算"说到话"（16bit 归一化后的幅度）。 */
    const val AUTO_SEND_SPEECH_PEAK: Float = 0.06f

    /** 与 [me.rerere.rikkahub.ui.hooks.DirectAudioRecorder] 的 WAV 参数保持同一份常量。 */
    const val SAMPLE_RATE: Int = 16_000

    /** 16bit 单声道：每采样 2 字节。 */
    const val BYTES_PER_FRAME: Int = 2

    /** 单段文件的绝对上限：即便时长参数被调大，也不允许写出一个巨大文件。 */
    const val HARD_MAX_FILE_BYTES: Int = 10 * 1024 * 1024

    /** 到达上限时给用户的提示（必须明确，不能悄悄结束）。 */
    fun limitReachedNotice(): String =
        "已经录满 ${MAX_RECORDING_MS / 1000} 秒，这段录音已自动结束并发送"

    /** PCM 数据字节数 = 采样率 × 2 × 秒数，再夹在硬上限之内。 */
    fun maxDataBytes(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        val bytes = SAMPLE_RATE.toLong() * BYTES_PER_FRAME * durationMs / 1000L
        return bytes.coerceAtMost(HARD_MAX_FILE_BYTES.toLong()).toInt()
    }

    /**
     * 裁决这一通电话的手动录音走哪条路。
     *
     * 顺序与理由：
     * 1. 没有麦克风权限 → 不可用（绝不显示"正在聆听"骗人）；
     * 2. 用户选了 ASR → 必须真的配置了 ASR，否则明确禁用并说清原因（打字仍然可用）；
     * 3. 用户选了 DIRECT → 必须模型/通道真的支持直传（`supportsDirectAudio`），否则禁用；
     * 4. AUTO → 能直传就直传，否则退到已配置的 ASR，再不行就禁用并说明可以打字。
     */
    fun resolve(
        mode: VideoVoiceInputMode,
        directAudioSupported: Boolean,
        asrConfigured: Boolean,
        microphoneGranted: Boolean,
    ): VideoVoiceAvailability {
        if (!microphoneGranted) {
            return VideoVoiceAvailability(
                route = null,
                enabled = false,
                reason = "没有麦克风权限：录不了音。可以直接打字，打字不受影响。",
            )
        }
        return when (mode) {
            VideoVoiceInputMode.ASR -> if (asrConfigured) {
                VideoVoiceAvailability(VideoVoiceRoute.ASR, enabled = true)
            } else {
                VideoVoiceAvailability(
                    route = null,
                    enabled = false,
                    reason = "还没有配置语音识别（设置 → 语音 → ASR 服务）。" +
                        "去配置，或者直接打字；不会假装在听。",
                )
            }

            VideoVoiceInputMode.DIRECT -> if (directAudioSupported) {
                VideoVoiceAvailability(VideoVoiceRoute.DIRECT_AUDIO, enabled = true)
            } else {
                VideoVoiceAvailability(
                    route = null,
                    enabled = false,
                    reason = "当前模型或通道暂不支持直接上传录音：只有 Google Gemini、OpenAI " +
                        "ChatCompletions 通道，并且模型本身接受音频（Gemini 或 GPT 音频模型）才行。" +
                        "可以改成「语音转文字」，也可以直接打字。",
                )
            }

            VideoVoiceInputMode.AUTO -> when {
                directAudioSupported ->
                    VideoVoiceAvailability(VideoVoiceRoute.DIRECT_AUDIO, enabled = true)

                asrConfigured -> VideoVoiceAvailability(VideoVoiceRoute.ASR, enabled = true)

                else -> VideoVoiceAvailability(
                    route = null,
                    enabled = false,
                    reason = "当前模型不能直接听语音，也还没有配置语音识别：" +
                        "到 设置 → 语音 → ASR 服务 添加一个，就能说话转文字；" +
                        "现在可以先打字。",
                )
            }
        }
    }

    /** 主按钮文案：开始录音 / 结束并发送 / 转写中 / 发送中 / 不可用。 */
    fun buttonLabel(state: VideoVoiceUiState): String = when (state.status) {
        VideoVoiceStatus.Recording -> "结束并发送"
        VideoVoiceStatus.Transcribing -> "转写中…"
        VideoVoiceStatus.Sending -> "发送中…"
        VideoVoiceStatus.Unavailable -> "不可用"
        VideoVoiceStatus.Idle -> when (state.route) {
            VideoVoiceRoute.DIRECT_AUDIO -> "录一段"
            VideoVoiceRoute.ASR -> "语音转文字"
            null -> "不可用"
        }
    }

    /** 录音时是时长，其余是路由/原因。 */
    fun hintLabel(state: VideoVoiceUiState): String = when (state.status) {
        VideoVoiceStatus.Recording ->
            "正在听，说完停一下就发出去 · ${formatDuration(state.elapsedMs)} / ${state.maxDurationMs / 1000}秒"

        VideoVoiceStatus.Transcribing -> "正在等最后一段文字"
        VideoVoiceStatus.Sending -> "正在发送这一轮"
        VideoVoiceStatus.Unavailable, VideoVoiceStatus.Idle ->
            state.reason.ifBlank {
                if (state.route == VideoVoiceRoute.ASR) "只发文字，不传音频" else "发完即删，不留在历史里"
            }
    }

    /** `12秒` / `1分05秒`。 */
    fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        if (seconds < 60L) return "${seconds}秒"
        return "${seconds / 60L}分${(seconds % 60L).toString().padStart(2, '0')}秒"
    }

    /**
     * 直传路线写进聊天历史的占位文本。
     *
     * 历史里**永远不存 audio part**，只存这行字；所以下一轮不会再 base64 一段已经删掉的音频。
     * 同时明确告诉模型"录音不保留"，它就不会在之后的轮次里假装还能回听。
     */
    fun directVoicePlaceholder(durationMs: Long): String =
        "🎙️ 语音 ${formatDuration(durationMs)}（仅本轮提供；录音不保留）"

    /**
     * 队列暂停/录音失效之后追加的说明。
     *
     * 暂停后这一条仍会作为普通文字消息送出去，但**没有**音频了；这段说明保证模型
     * 不会把"仅本轮提供"读成"现在还能听"，也不会替用户编一段逐字稿。
     */
    fun expiredVoiceNote(durationMs: Long): String =
        "[这段录音已失效：录的那 ${formatDuration(durationMs)} 没有发送，也不会再发送；" +
            "不要声称听过它，也不要替用户编造逐字内容。需要的话让用户重新录一段。]"
}

/** DIRECT 路线：录音器自己的状态流 → 这一段的结论。 */
sealed interface DirectSegmentResult {
    data class Ready(val path: String, val durationMs: Long) : DirectSegmentResult
    data class Failed(val reason: String) : DirectSegmentResult
}

/**
 * DIRECT 路线的一段录音判定（纯函数）。
 *
 * 录音器自己会写 WAV 并把最终路径发布在 [ASRState.audioFilePath] 上，
 * 所以"用户点停"和"到达 60 秒上限自动结束"在这里是同一条路径——两者都必须发送，
 * 区别只在于要不要提示"已经录满"。
 */
fun directSegmentResult(state: ASRState): DirectSegmentResult? = when {
    state.status == ASRStatus.Error -> DirectSegmentResult.Failed(state.errorMessage ?: "录音失败")
    state.status == ASRStatus.Idle && !state.audioFilePath.isNullOrBlank() ->
        DirectSegmentResult.Ready(state.audioFilePath!!, state.durationMs)

    else -> null
}

/**
 * 视频通话的"说完自动发送"判定（纯状态，可 JVM 单测）。
 *
 * 与语音通话的 VAD 同一条思路，但阈值在这里独立可测：
 * 1. 必须先**真的听到过人声**（否则一段全程安静的录音会被当成一句话发出去）；
 * 2. 录够 [minDurationMs] 才开始判定，避免刚点下去就被"静音"结束；
 * 3. 连续安静 [silenceMs] 就算这一句说完了。
 *
 * ASR 路线还可以只靠"转写稳定且有内容"成立：个别 provider 不上报振幅。
 */
class VideoVoiceActivity(
    private val speechPeak: Float = VideoVoicePolicy.AUTO_SEND_SPEECH_PEAK,
    private val minDurationMs: Long = VideoVoicePolicy.AUTO_SEND_MIN_DURATION_MS,
    private val silenceMs: Long = VideoVoicePolicy.AUTO_SEND_SILENCE_MS,
) {
    // 时刻一律用可空值：调用方传进来的时间戳可能是 0（很多 provider 从 0 开始计时），
    // 用 0 当"还没设置"的哨兵会和真实样本撞车。
    private var heardSpeech = false
    private var silentSince: Long? = null
    private var lastSampleAt: Long? = null
    private var lastTranscript: String = ""
    private var transcriptStableSince: Long? = null

    fun reset() {
        heardSpeech = false
        silentSince = null
        lastSampleAt = null
        lastTranscript = ""
        transcriptStableSince = null
    }

    /** 喂一个音量峰值（0..1）与它的采样时刻。 */
    fun onAmplitude(peak: Float, nowMillis: Long) {
        lastSampleAt = nowMillis
        if (peak >= speechPeak) {
            heardSpeech = true
            silentSince = null
        } else {
            silentSince = silentSince ?: nowMillis
        }
    }

    /** 喂一段转写（ASR 路线的 partial）。 */
    fun onTranscript(text: String, nowMillis: Long) {
        val trimmed = text.trim()
        if (trimmed != lastTranscript) {
            lastTranscript = trimmed
            transcriptStableSince = nowMillis
        } else {
            transcriptStableSince = transcriptStableSince ?: nowMillis
        }
    }

    /**
     * @param elapsedMs 这一段已经录了多久。
     * @param nowMillis 单调/墙钟的当前时刻（与喂样本时同一个时钟）。
     */
    fun shouldSend(elapsedMs: Long, nowMillis: Long): Boolean {
        if (elapsedMs < minDurationMs) return false
        val stableSince = transcriptStableSince
        val transcriptReady = lastTranscript.isNotBlank() && stableSince != null &&
            nowMillis - stableSince >= silenceMs
        // 上报过振幅的链路必须真的观察到安静；样本中断不等于用户停止说话。
        if (lastSampleAt != null) {
            val silenceAnchor = silentSince ?: return false
            if (nowMillis - silenceAnchor < silenceMs) return false
        }
        return if (lastTranscript.isNotBlank()) transcriptReady else heardSpeech
    }
}

/** ASR 路线：这一段的结论。 */
sealed interface AsrSegmentResult {
    data class Transcript(val text: String) : AsrSegmentResult
    data object Empty : AsrSegmentResult
    data class Failed(val reason: String) : AsrSegmentResult
}

/**
 * ASR 路线的一段判定（纯状态机）。
 *
 * 两个必须钉住的点：
 * 1. **stop 之后**才收结果（`stopRequested`）——否则开着录音时 provider 自己冒出来的 Idle
 *    会被当成"说完了"，直接抢在用户手动结束之前发送；
 * 2. 必须先真的看到它**录过**（Connecting/Listening/Stopping），启动之前那条旧 Idle
 *    （provider 上一段的残留）绝不能被当成本次结果，旧 partial 也不复用。
 */
class AsrSegmentWatcher {
    private var observedRecording = false

    fun observe(state: ASRState, stopRequested: Boolean): AsrSegmentResult? {
        if (state.status == ASRStatus.Error) {
            return AsrSegmentResult.Failed(state.errorMessage ?: "语音识别失败")
        }
        if (state.isRecording) {
            if (state.status == ASRStatus.Connecting || state.status == ASRStatus.Listening) {
                observedRecording = true
            }
            return null
        }
        if (!stopRequested) return null
        if (!observedRecording || state.status != ASRStatus.Idle) return null
        observedRecording = false
        val text = state.transcript.trim()
        return if (text.isEmpty()) AsrSegmentResult.Empty else AsrSegmentResult.Transcript(text)
    }
}
