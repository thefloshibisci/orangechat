/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.speechDeliveryPrompt

/**
 * 本轮的 system prompt。
 *
 * [actual] 是这一轮真实带了附件（手动夹帧、自动抓帧、音频）时用的版本；
 * [withoutTransientAttachments] 是**同一轮**在"本轮临时附件"（临时图或临时录音）被摘掉
 * 之后改用（队列暂停会让临时附件失效）。永久手动夹帧仍然在 parts 里，所以那个变体按
 * [permanentPicture] 重新算"有没有图"，音频一律算没有。
 */
internal data class TurnSystemPrompts(
    val actual: String?,
    val withoutTransientAttachments: String?,
    val withoutCamera: String? = null,
)

internal fun buildCallSystemPrompts(
    ttsProvider: TTSProviderSetting?,
    coordinator: VideoCallCoordinator?,
    pictureAttached: Boolean,
    audioAttached: Boolean,
    permanentPicture: Boolean = false,
    hasTransientAttachment: Boolean = false,
): TurnSystemPrompts {
    val speech = speechDeliveryPrompt(ttsProvider)
    fun join(videoPrompt: String?): String? = listOfNotNull(
        speech.takeIf { it.isNotBlank() },
        videoPrompt?.takeIf { it.isNotBlank() },
    ).joinToString("\n\n").ifBlank { null }

    // 语音通话原有的 system prompt（朗读风格），视频模式下再叠加视频上下文。
    if (coordinator == null) return TurnSystemPrompts(join(null), null)
    // 本轮的备注只被"真实版本"消费一次；降级变体不再重复取用。
    val withAttachments = coordinator.turnContext(
        pictureAttached = pictureAttached,
        includeTurnNotes = true,
        audioAttached = audioAttached,
    )
    val degraded = if (hasTransientAttachment) {
        coordinator.turnContext(
            pictureAttached = permanentPicture,
            includeTurnNotes = false,
            audioAttached = false,
        )
    } else {
        null
    }
    val withoutCamera = coordinator.turnContext(
        pictureAttached = permanentPicture,
        includeTurnNotes = false,
        audioAttached = audioAttached,
        cameraRevoked = true,
    )
    return TurnSystemPrompts(join(withAttachments), join(degraded), join(withoutCamera))
}
