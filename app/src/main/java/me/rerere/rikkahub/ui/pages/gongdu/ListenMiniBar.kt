package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Headphones
import me.rerere.hugeicons.stroke.Next
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.data.listen.ListenRepository
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/** Only the conversation holding the listening session shows its playback bar. */
@Composable
fun ListenMiniBar(
    conversationId: String,
    glass: HazeState? = null,
    backdropColor: Color = MaterialTheme.colorScheme.background,
    onOpen: () -> Unit,
) {
    val repo = koinInject<ListenRepository>()
    val state by repo.state.collectAsStateWithLifecycle()
    val item = state.current ?: return
    if (state.conversationId != conversationId) return
    // Putting a paused bar away keeps the queue; it returns with the next song or when playback resumes.
    val active = state.playing || state.loading
    var dismissed by rememberSaveable(conversationId, item.id, active) { mutableStateOf(false) }
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
    val chat = MaterialTheme.colorScheme
    val detail = state.error ?: when {
        state.loading -> "正在准备播放"
        item.origin == "assistant" -> item.message.ifBlank { "助手放给你的" }
        else -> item.song.artist
    }
    Box(Modifier.sessionCapsuleWidth().testTag("listen-mini-bar")
        .sessionCapsule(glass, backdropColor)) {
        ListenTheme {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClickLabel = "打开一起听", onClick = onOpen)
                    .heightIn(min = SessionCapsuleHeight).padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.song.cover.isNotBlank()) {
                        ListenArtwork(item.song.cover, Modifier.size(28.dp))
                    } else {
                        Box(Modifier.size(28.dp).testTag("listen-mini-fallback-art")
                            .background(chat.onSurface.copy(alpha = .08f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(HugeIcons.Headphones, contentDescription = null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(item.song.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                        color = chat.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (detail.isNotBlank()) Text("· $detail", Modifier.weight(.65f, fill = false),
                        style = MaterialTheme.typography.bodySmall, color = if (state.error != null) chat.error else chat.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ListenMiniControl(if (active) HugeIcons.Pause else HugeIcons.Play, if (active) "暂停音乐" else "播放音乐", chat.onSurface) {
                    transport { repo.toggle() }
                }
                if (active) ListenMiniControl(HugeIcons.Next, "下一首音乐", chat.onSurface) { transport { repo.next() } }
                else ListenMiniControl(HugeIcons.Cancel01, "收起播放条", chat.onSurface) { dismissed = true }
            }
            if (state.durationMs > 0) {
                LinearProgressIndicator(progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 19.dp).height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = chat.onSurface.copy(alpha = .14f))
            }
        }
    }
}

@Composable
private fun ListenMiniControl(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(width = 48.dp, height = SessionCapsuleHeight)) {
        Icon(icon, label, Modifier.size(19.dp), tint = tint)
    }
}
