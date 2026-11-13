package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Next
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.data.listen.ListenRepository
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/** Only the conversation holding the listening session shows its playback bar. */
@Composable
fun ListenMiniBar(conversationId: String, onOpen: () -> Unit) {
    val repo = koinInject<ListenRepository>()
    val state by repo.state.collectAsStateWithLifecycle()
    val item = state.current ?: return
    if (state.conversationId != conversationId) return
    // Putting a paused bar away keeps the queue; it returns with the next song or when playback resumes.
    var dismissed by rememberSaveable(item.id) { mutableStateOf(false) }
    val active = state.playing || state.loading
    if (dismissed && !active) return
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    fun transport(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { toaster.show(error.message ?: "播放未完成，请重试") }
        }
    }
    ListenTheme {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(onClickLabel = "打开一起听", onClick = onOpen)
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ListenArtwork(item.song.cover, Modifier.size(36.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(item.song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.error ?: if (state.loading) "正在准备播放" else if (item.origin == "assistant") item.message.ifBlank { "助手放给你的" } else item.song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    ListenIcon(if (active) HugeIcons.Pause else HugeIcons.Play, if (active) "暂停音乐" else "播放音乐") {
                        transport { repo.toggle() }
                    }
                    if (active) ListenIcon(HugeIcons.Next, "下一首音乐") { transport { repo.next() } }
                    else ListenIcon(HugeIcons.Cancel01, "收起播放条") { dismissed = true }
                }
                LinearProgressIndicator(progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer)
            }
        }
    }
}
