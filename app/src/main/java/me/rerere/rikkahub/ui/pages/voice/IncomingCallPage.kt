/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.voice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CallIncoming01
import me.rerere.hugeicons.stroke.Cancel01

private val ColorIncoming = Color(0xFF5489AA) // 来电主色: 琥珀
private val ColorBgIncoming = Color(0xFFE8F3FC) // 暖深褐底

/**
 * 来电界面 (AI 主动发起语音通话时弹出)
 *
 * - 全屏暖色背景 + 来电光球脉动 + 震动提醒
 * - 底部两个按钮: 接听 / 拒接
 * - 接听: 调用 onStartCall 启动 VoiceCallService 并跳到通话页
 * - 拒接: 调用 onDecline 关闭
 * - 返回键 = 拒接
 */
@Composable
fun IncomingCallPage(
    conversationId: String,
    assistantName: String,
    theme: String = "ice",
    onStartCall: () -> Unit,
    onDecline: () -> Unit,
) {
    val permission = me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState(me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio)
    val context = LocalContext.current

    // 来电震动: 进入页面开始, 离开页面停止
    LaunchedEffect(Unit) {
        val vibrator = getVibrator(context)
        if (vibratorHasAmplitudeControl(vibrator)) {
            // 持续震动模式: 响 0.8s 停 0.8s, 循环
            val pattern = longArrayOf(0, 800, 800)
            vibrator?.vibrate(
                VibrationEffect.createWaveform(pattern, 0)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 800, 800), 0)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            getVibrator(context)?.cancel()
        }
    }

    // 返回键 = 拒接
    BackHandler { onDecline() }

    val accentColor by animateColorAsState(
        targetValue = ColorIncoming,
        animationSpec = tween(durationMillis = 600),
        label = "incomingAccent"
    )

    // 来电脉动: 光球缓慢呼吸放大
    val transition = rememberInfiniteTransition(label = "incoming_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    GlassCallPanel(
        theme = theme,
        name = assistantName,
        status = "邀请你开始语音通话",
        duration = "",
        subtitle = "",
        incoming = true,
        onPrimary = { if (permission.allRequiredPermissionsGranted) onStartCall() else permission.requestPermissions() },
        onEnd = onDecline,
    )
}

@Composable
private fun IncomingCallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconTint: Color,
    label: String,
    size: Dp = 64.dp,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = backgroundColor,
            modifier = Modifier.size(size)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(size * 0.4f)
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = label,
            color = Color(0xFF27476E),
            fontSize = 13.sp
        )
    }
}

private fun getVibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
    manager?.defaultVibrator
} else {
    @Suppress("DEPRECATION")
    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
}

private fun vibratorHasAmplitudeControl(vibrator: Vibrator?): Boolean =
    vibrator != null && vibrator.hasAmplitudeControl()
