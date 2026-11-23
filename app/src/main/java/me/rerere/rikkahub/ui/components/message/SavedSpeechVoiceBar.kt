package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play

@Composable
internal fun SavedSpeechVoiceBar(
    clips: List<UIMessagePart.Audio>,
    loading: Boolean,
    generationError: String? = null,
    playbackFactory: (android.content.Context, List<UIMessagePart.Audio>) -> SavedSpeechPlayback = { context, audio ->
        SavedSpeechPlayer(context, audio)
    },
) {
    val context = LocalContext.current
    val playback = remember(clips) { playbackFactory(context.applicationContext, clips) }
    val state by playback.state.collectAsState()
    DisposableEffect(playback) { onDispose { playback.close() } }
    val bars = remember(clips) { val random = java.util.Random(clips.hashCode().toLong()); List(24) { .2f + random.nextFloat() * .8f } }
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .35f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f)).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = playback::toggle, enabled = clips.isNotEmpty() && !loading,
                modifier = Modifier.size(48.dp).semantics {
                    stateDescription = when {
                        loading -> "语音生成中"
                        state.buffering -> "正在加载本地音频"
                        state.playing -> "正在播放"
                        else -> "未播放"
                    }
                }) {
                if (loading || state.buffering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(if (state.playing) HugeIcons.Pause else HugeIcons.Play,
                    contentDescription = if (state.playing) "暂停语音" else "播放已保存的语音", tint = active,
                    modifier = Modifier.size(20.dp))
            }
            Canvas(Modifier.weight(1f).height(18.dp)) {
                val gap = size.width / bars.size
                bars.forEachIndexed { index, ratio ->
                    val height = size.height * ratio
                    drawRoundRect(if (index < progress * bars.size) active else inactive,
                        Offset(index * gap, (size.height - height) / 2), Size((gap * .45f).coerceAtLeast(1f), height),
                        CornerRadius(1f, 1f))
                }
            }
            Text(speechDurationLabel(state.durationMs), modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        val notice = when {
            loading -> "语音生成中，完成后点击播放"
            state.error != null -> state.error
            generationError != null -> generationError + if (clips.isNotEmpty()) "（已保存的片段仍可播放）" else ""
            clips.isEmpty() -> "这条旧语音未保存音频，无法重播。重新生成可能计费。"
            else -> null
        }
        notice?.let { Text(it, style = MaterialTheme.typography.labelSmall,
            color = if (state.error != null || generationError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
