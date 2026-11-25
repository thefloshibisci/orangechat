/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.VOICE_CALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import kotlin.uuid.Uuid

internal fun voiceCallNotificationContentText(
    videoMode: Boolean,
    state: VoiceCallUiState,
): String = when (state.status) {
    // 视频通话是手动采音，"聆听"会误以为麦克风一直在录。
    VoiceCallStatus.Listening -> if (videoMode) "通话中" else "正在聆听..."
    VoiceCallStatus.Processing -> "正在思考..."
    VoiceCallStatus.Speaking -> "正在说话..."
    VoiceCallStatus.Error -> state.errorMessage ?: "通话出错"
    VoiceCallStatus.Idle -> "通话中"
}

internal fun buildVoiceCallNotification(
    context: Context,
    conversationId: Uuid,
    videoMode: Boolean,
    state: VoiceCallUiState,
    hangUpAction: String,
): Notification {
    val contentText = voiceCallNotificationContentText(videoMode, state)

    // 点击通知本体: 回到 RouteActivity 并导航到 VoiceCallPage
    val contentIntent = PendingIntent.getActivity(
        context,
        conversationId.hashCode(),
        Intent(context, RouteActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("openVoiceCallConversationId", conversationId.toString())
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // 通知上的"挂断"按钮: 直接发一个带 hangUpAction 的 Intent 给 VoiceCallService
    val hangUpIntent = PendingIntent.getService(
        context,
        0,
        Intent(context, VoiceCallService::class.java).apply { action = hangUpAction },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    return NotificationCompat.Builder(context, VOICE_CALL_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(if (videoMode) "视频通话" else "语音通话")
        .setContentText(contentText)
        .setSmallIcon(R.drawable.small_icon)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)
        .addAction(0, "挂断", hangUpIntent)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .build()
}
