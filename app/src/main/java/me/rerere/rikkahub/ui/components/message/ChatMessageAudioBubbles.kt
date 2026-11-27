/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PauseCircle
import me.rerere.hugeicons.stroke.PlayCircle
import java.util.Random

@Composable
@Suppress("UnusedCrossTarget")
internal fun AudioPlayerBubble(
    url: String,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentMs by remember { mutableIntStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }

    // Generate pseudo-random waveform bar heights (deterministic per url)
    val waveformBars = remember(url) {
        val rnd = Random(url.hashCode().toLong())
        List(40) { 0.15f + rnd.nextFloat() * 0.85f }
    }

    val progress = if (durationMs > 0) currentMs.toFloat() / durationMs else 0f

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // Progress ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    currentMs = it.currentPosition
                }
            }
            delay(50)
        }
    }

    // Animate waveform bars when playing
    val animatedBars = remember { mutableStateOf(waveformBars) }
    LaunchedEffect(isPlaying, progress) {
        if (isPlaying) {
            val rnd = Random()
            val newBars = waveformBars.mapIndexed { index, base ->
                val playedRatio = if (progress > 0f) index.toFloat() / waveformBars.size else 0f
                if (playedRatio <= progress) {
                    // Already played bars stay at original height
                    base
                } else {
                    // Upcoming bars get slight animation
                    base * (0.85f + rnd.nextFloat() * 0.3f)
                }
            }
            animatedBars.value = newBars
        } else {
            animatedBars.value = waveformBars
        }
    }

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Play / Pause button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .combinedClickable(
                    onLongClick = onLongClick,
                    onDoubleClick = onDoubleClick,
                    onClick = {
                        if (isPlaying) {
                            mediaPlayer?.pause()
                            isPlaying = false
                        } else {
                            if (mediaPlayer == null || !isPrepared) {
                                val mp = MediaPlayer()
                                try {
                                    val uri = Uri.parse(url)
                                    mp.setDataSource(context, uri)
                                    mp.prepare()
                                    durationMs = mp.duration
                                    mp.setOnCompletionListener {
                                        isPlaying = false
                                        currentMs = 0
                                    }
                                    mp.start()
                                    isPlaying = true
                                    isPrepared = true
                                    mediaPlayer = mp
                                } catch (e: Exception) {
                                    mp.release()
                                }
                            } else {
                                mediaPlayer?.start()
                                isPlaying = true
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Waveform bars
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongClick, onDoubleClick = onDoubleClick),
        ) {
            val barCount = animatedBars.value.size
            val totalWidth = size.width
            val barWidth = 2.5f
            val gap = (totalWidth - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
            val playedBarCount = (progress * barCount).toInt()

            animatedBars.value.forEachIndexed { index, barRatio ->
                val barHeight = size.height * barRatio.coerceIn(0.15f, 1f)
                val x = index * (barWidth + gap)
                val y = (size.height - barHeight) / 2f
                drawRoundRect(
                    color = if (index < playedBarCount) activeColor else inactiveColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(1.5f, 1.5f),
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Duration text
        val displaySec = if (isPlaying || currentMs > 0) {
            val remaining = (durationMs - currentMs) / 1000
            remaining.coerceAtLeast(0)
        } else {
            durationMs / 1000
        }
        Text(
            text = String.format("%d:%02d", displaySec / 60, displaySec % 60),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 13.sp,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun VoiceMessageBubble(
    voiceMessage: UIMessagePart.VoiceMessage,
    isUser: Boolean,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
) {
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    val voiceColors = interactionColors(if (isUser) "sakura" else "ice")
    val durationSec = (voiceMessage.duration / 1000).coerceAtLeast(1)

    DisposableEffect(voiceMessage.url) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    isPlaying = false
                }
            }
            delay(50)
        }
    }

    Surface(
        modifier = Modifier.combinedClickable(
            onLongClick = onLongClick,
            onDoubleClick = onDoubleClick,
            onClick = {
                if (isPlaying) {
                    mediaPlayer?.let {
                        it.stop()
                        it.reset()
                    }
                    isPlaying = false
                } else {
                    try {
                        val mp = MediaPlayer()
                        mp.setDataSource(voiceMessage.url)
                        mp.prepare()
                        mp.setOnCompletionListener {
                            isPlaying = false
                        }
                        mp.start()
                        isPlaying = true
                        mediaPlayer?.release()
                        mediaPlayer = mp
                    } catch (e: Exception) {
                        // File might not exist
                    }
                }
            },
        ),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .85f)),
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            voiceColors.top.copy(alpha = .88f),
                            voiceColors.bottom.copy(alpha = .70f),
                        ),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = voiceColors.ink,
                    modifier = Modifier.size(24.dp),
                )
                // Waveform bars
                val waveformBars = remember(voiceMessage.url) {
                    val rnd = Random(voiceMessage.url.hashCode().toLong())
                    List(24) { 0.2f + rnd.nextFloat() * 0.8f }
                }
                val waveformColor = voiceColors.accent
                Canvas(modifier = Modifier.width(112.dp).height(24.dp)) {
                    val barCount = waveformBars.size
                    val barWidth = 2.5f
                    val gap = (size.width - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
                    waveformBars.forEachIndexed { index, barRatio ->
                        val barHeight = size.height * barRatio.coerceIn(0.2f, 1f)
                        val x = index * (barWidth + gap)
                        val y = (size.height - barHeight) / 2f
                        drawRoundRect(
                            color = waveformColor,
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(1.5f, 1.5f),
                        )
                    }
                }
                Text(
                    text = "${durationSec}″",
                    style = MaterialTheme.typography.labelMedium,
                    color = voiceColors.ink,
                )
            }
            // Show transcript text below the voice bubble (like WeChat)
            if (voiceMessage.transcript.isNotBlank()) {
                Text(
                    text = voiceMessage.transcript,
                    style = MaterialTheme.typography.bodySmall,
                    color = voiceColors.ink.copy(alpha = .8f),
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
