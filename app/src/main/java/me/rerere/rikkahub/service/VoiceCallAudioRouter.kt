/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

private const val AUDIO_ROUTER_TAG = "VoiceCallAudioRouter"

internal class VoiceCallAudioRouter(
    private val context: Context,
) {
    private var initialAudioMode: Int? = null
    private var initialSpeakerphoneOn = false
    private var initialCommunicationDevice: AudioDeviceInfo? = null

    fun setSpeakerEnabled(enableSpeaker: Boolean): Result<Boolean?> {
        val audioManager = context.getSystemService(AudioManager::class.java)
            ?: return Result.success(null)
        return runCatching {
            if (initialAudioMode == null) {
                initialAudioMode = audioManager.mode
                @Suppress("DEPRECATION")
                run { initialSpeakerphoneOn = audioManager.isSpeakerphoneOn }
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (initialCommunicationDevice == null) {
                    initialCommunicationDevice = audioManager.communicationDevice
                }
                val targetType = if (enableSpeaker) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val device = if (enableSpeaker) {
                    audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
                } else {
                    initialCommunicationDevice?.takeIf { it.type == targetType }
                        ?: audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
                }
                check(device != null && audioManager.setCommunicationDevice(device)) {
                    if (enableSpeaker) "扬声器当前不可用" else "听筒当前不可用"
                }
            } else {
                @Suppress("DEPRECATION")
                run { audioManager.isSpeakerphoneOn = enableSpeaker }
            }
            enableSpeaker
        }
    }

    fun restore() {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
                initialCommunicationDevice = null
            } else if (initialAudioMode != null) {
                @Suppress("DEPRECATION")
                run { audioManager.isSpeakerphoneOn = initialSpeakerphoneOn }
            }
            initialAudioMode?.let { audioManager.mode = it }
            initialAudioMode = null
        }.onFailure { Log.w(AUDIO_ROUTER_TAG, "恢复通话音频路由失败", it) }
    }
}
