/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.voice

/**
 * 语音通话状态机
 *
 * 状态流转:
 * Idle -> Listening -> Processing -> Speaking -> Listening -> ...
 * Model reply failure -> Listening; microphone/initialization failure -> Error.
 */
enum class VoiceCallStatus {
    Idle,
    Listening,
    Processing,
    Speaking,
    Error
}

/** Recover the next utterance without replaying the failed one or changing audio controls.
 * [message] is surfaced as a transient notice (it fades), never as a persistent error. */
fun VoiceCallUiState.listeningAfterReply(message: String? = null): VoiceCallUiState = copy(
    status = VoiceCallStatus.Listening, userTranscript = "", assistantText = "",
    amplitudes = emptyList(), errorMessage = null,
    notice = message, noticeSeq = if (message != null) noticeSeq + 1 else noticeSeq,
)

/**
 * 语音通话 UI 状态
 */
data class VoiceCallUiState(
    val status: VoiceCallStatus = VoiceCallStatus.Idle,
    val userTranscript: String = "",
    val assistantText: String = "",
    val errorMessage: String? = null,
    val amplitudes: List<Float> = emptyList(),
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isAssistantSpeaking: Boolean = false,
    val isCameraEnabled: Boolean = true,
    /** Session-only screen-space preview flip; JPEG is never mirrored. */
    val isPreviewMirrored: Boolean = false,
    val autoSendEnabled: Boolean = true,
    /** 一次性提示（会淡出）。识别出错、回复失败这类"已自动恢复"的消息放这里，
     * 与持久的 [errorMessage]（能力真的坏掉）分开。 */
    val notice: String? = null,
    /** 每次写入非空 [notice] 都 +1：同样的提示再来一次也要重新播放淡入。 */
    val noticeSeq: Long = 0,
    /**
     * 视频通话里"手动录一段"这一段的状态。
     *
     * 纯语音通话恒为安全 Idle，不受影响；视频页从这里读按钮状态（不用另开一条 flow，
     * 这样 binder 还没连上时也能拿到正确的初值）。
     */
    val voiceInput: me.rerere.rikkahub.data.video.VideoVoiceUiState =
        me.rerere.rikkahub.data.video.VideoVoiceUiState(),
) {
    val isActive: Boolean
        get() = status != VoiceCallStatus.Idle
}
