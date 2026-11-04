package me.rerere.rikkahub.ui.pages.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CallIncoming01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MicOff01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.ReferenceGlass
import me.rerere.rikkahub.ui.components.message.interactionColors
import me.rerere.rikkahub.ui.components.message.themeMascot

/** Original 315 x 530 glass phone card; controls retain the app's actual call capabilities. */
@Composable
internal fun GlassCallPanel(
    theme: String, name: String, status: String, duration: String, subtitle: String,
    incoming: Boolean = false, muted: Boolean = false, canControl: Boolean = true,
    speakerOn: Boolean = false, speaking: Boolean = false,
    error: String? = null, waveform: List<Float> = emptyList(), onCollapse: (() -> Unit)? = null,
    onPrimary: () -> Unit, onSpeaker: () -> Unit = {}, onEnd: () -> Unit,
) {
    val colors = interactionColors(theme)
    val ripple = rememberInfiniteTransition(label = "callRipple")
    val ripplePhase by ripple.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "ripplePhase")
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors.top, colors.bottom)))
        .systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp), contentAlignment = Alignment.Center) {
        ReferenceGlass(theme, Modifier.widthIn(max = 315.dp).fillMaxWidth().heightIn(min = 530.dp), radius = 38.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    if (onCollapse != null) TextButton(onClick = onCollapse, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("↙", color = colors.ink) }
                    else Spacer(Modifier.size(20.dp))
                    Text(if (incoming) "语音来电" else "语音通话", Modifier.background(Color.White.copy(alpha = .65f), CircleShape)
                        .border(1.dp, Color.White, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(20.dp))
                }
                Box(Modifier.padding(top = 18.dp).size(156.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.matchParentSize()) {
                        repeat(3) { index ->
                            val phase = (ripplePhase + index / 3f) % 1f
                            drawCircle(Color.White.copy(alpha = (.50f * (1f - phase)).coerceAtLeast(.04f)),
                                size.minDimension * (.18f + .31f * phase), style = Stroke(1.dp.toPx()))
                        }
                    }
                    Box(Modifier.size(96.dp).background(Brush.linearGradient(listOf(Color.White, Color(0xFFF2EAF9), colors.bottom)), CircleShape)
                        .border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Image(painterResource(themeMascot(theme)), null, Modifier.size(54.dp), colorFilter = ColorFilter.tint(colors.accent))
                    }
                }
                Text(name.ifBlank { "AI 助手" }, Modifier.padding(top = 8.dp), fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                Text(status, Modifier.padding(top = 6.dp), color = colors.ink.copy(alpha = .7f), fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                if (!incoming) Text(duration, Modifier.padding(top = 10.dp).background(Color.White.copy(alpha = .55f), CircleShape)
                    .border(1.dp, Color.White, CircleShape).padding(horizontal = 18.dp, vertical = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (!incoming) Row(Modifier.padding(top = 20.dp).height(24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    (0..7).forEach { index ->
                        val liveAmplitude = waveform.getOrNull(waveform.size - 8 + index)?.coerceIn(0f, 1f)
                        val amplitude = liveAmplitude ?: if (speaking) (.12f + .48f * kotlin.math.abs(
                            kotlin.math.sin(ripplePhase * 6.283f + index * .82f))) else .3f
                        Box(Modifier.width(4.dp).height((6 + amplitude * 18).dp).background(colors.ink.copy(alpha = .55f), CircleShape))
                    }
                }
                if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 12.dp).heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                    fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                error?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                Row(Modifier.padding(top = if (incoming) 56.dp else 30.dp).fillMaxWidth()
                    .background(Color.White.copy(alpha = .45f), RoundedCornerShape(28.dp))
                    .border(1.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(28.dp)).padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (incoming) {
                        GlassCallButton(theme, HugeIcons.Cancel01, "拒接", Color(0xFFFFA6BE), true, onEnd)
                        GlassCallButton(theme, HugeIcons.CallIncoming01, "接听", Color(0xFF8EE0C3), true, onPrimary)
                    } else {
                        GlassCallButton(theme, if (muted) HugeIcons.MicOff01 else HugeIcons.Mic01, if (muted) "取消静音" else "静音", colors.accent.copy(alpha = .28f), canControl, onPrimary)
                        GlassCallButton(theme, HugeIcons.VolumeHigh,
                            if (speakerOn) "免提" else "扬声器", colors.accent.copy(alpha = .48f), true, onSpeaker)
                        GlassCallButton(theme, HugeIcons.Cancel01, "挂断", Color(0xFFFFA6BE), true, onEnd)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassCallButton(theme: String, icon: ImageVector, label: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    val colors = interactionColors(theme)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White), modifier = Modifier.size(52.dp).testTag("call-control-$label")) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White, tint))), contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = colors.accent.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(24.dp))
            }
        }
        Text(label, fontSize = 10.sp, lineHeight = 14.sp, color = LocalContentColor.current.copy(alpha = .7f))
    }
}


@Composable
internal fun FloatingCallPill(theme: String, name: String = "AI 助手", onOpen: () -> Unit, onEnd: () -> Unit) {
    val start by me.rerere.rikkahub.service.VoiceCallService.startedAtMillis.collectAsState()
    val callState by me.rerere.rikkahub.service.VoiceCallService.activeUiState.collectAsState()
    var seconds by remember { mutableStateOf(0L) }
    LaunchedEffect(start) {
        while (true) {
            seconds = if (start > 0) ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0) else 0
            kotlinx.coroutines.delay(1000)
        }
    }
    val colors = interactionColors(theme)
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        ReferenceGlass(theme, Modifier.widthIn(max = 380.dp).fillMaxWidth().heightIn(min = 60.dp), radius = 32.dp, onClick = onOpen) {
            Row(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(34.dp).background(Color.White.copy(alpha = .78f), CircleShape)
                    .border(1.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(themeMascot(theme)), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(colors.accent))
                    Box(Modifier.align(Alignment.BottomEnd).size(9.dp).background(Color(0xFF76C9A2), CircleShape)
                        .border(1.dp, Color.White, CircleShape))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${name.ifBlank { "AI 助手" }} · ${when (callState.status) {
                        VoiceCallStatus.Listening -> "正在聆听"
                        VoiceCallStatus.Processing -> "正在思考"
                        VoiceCallStatus.Speaking -> "正在说话"
                        VoiceCallStatus.Error -> "通话异常"
                        else -> "通话中"
                    }}  %02d:%02d".format(seconds / 60, seconds % 60),
                        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = colors.ink, maxLines = 1)
                    Text(if (callState.isMuted) "麦克风已静音" else "点击返回通话", fontSize = 9.sp,
                        lineHeight = 12.sp, color = colors.ink.copy(alpha = .72f), maxLines = 1)
                }
                Row(Modifier.width(30.dp).height(22.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    (0..3).forEach { index ->
                        val amplitude = callState.amplitudes.getOrNull(callState.amplitudes.size - 4 + index)?.coerceIn(0f, 1f) ?: .22f
                        Box(Modifier.width(3.dp).height((4 + amplitude * 14).dp).background(colors.ink.copy(alpha = .7f), CircleShape))
                    }
                }
                IconButton(onClick = { me.rerere.rikkahub.service.VoiceCallService.toggleMute(context) },
                    modifier = Modifier.size(30.dp)) {
                    Icon(if (callState.isMuted) HugeIcons.MicOff01 else HugeIcons.Mic01, if (callState.isMuted) "取消静音" else "静音",
                        tint = colors.ink, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = { me.rerere.rikkahub.service.VoiceCallService.toggleSpeaker(context) }, modifier = Modifier.size(30.dp)) {
                    Icon(HugeIcons.VolumeHigh,
                        if (callState.isSpeakerOn) "免提" else "扬声器", tint = colors.accent, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onEnd, modifier = Modifier.size(30.dp)) {
                    Icon(HugeIcons.Cancel01, "挂断", tint = colors.ink, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}
