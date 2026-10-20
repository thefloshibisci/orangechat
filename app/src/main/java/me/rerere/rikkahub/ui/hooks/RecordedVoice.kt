package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/** Controllers may publish the file before Idle, and may retain the previous file while starting. */
internal fun ASRState.completedRecordingAfter(previousPath: String?): String? =
    audioFilePath?.takeIf { status == ASRStatus.Idle && it != previousPath }

/** Direct recordings use the same persistent attachment and request path as uploaded audio. */
internal fun recordedVoicePart(
    path: String,
    durationMs: Long,
    transcript: String,
    sendAudio: Boolean,
): UIMessagePart = if (sendAudio) {
    UIMessagePart.Audio(if (path.startsWith("/")) Uri.fromFile(File(path)).toString() else path)
} else {
    UIMessagePart.VoiceMessage(url = path, duration = durationMs, transcript = transcript)
}
