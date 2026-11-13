package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.data.listen.ListenRepository
import me.rerere.rikkahub.data.listen.ListenShare
import me.rerere.rikkahub.ui.pages.gongdu.ListenRecord
import org.koin.compose.koinInject

@Composable
fun ListenSongCard(share: ListenShare, conversationId: String?, readOnly: Boolean = false) {
    val repository = koinInject<ListenRepository>()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.widthIn(max = 340.dp).padding(vertical = 4.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("共度 / 一首给你的歌", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ListenRecord(share.song.cover, Modifier.size(64.dp))
                Column(Modifier.weight(1f)) {
                    Text(share.song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(share.song.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else FilledTonalIconButton(enabled = !readOnly && conversationId != null, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            check(repository.config.value.configured) { "请先在一起听房间连接音乐服务" }
                            val song = repository.song(share.song.id)
                            repository.play(song, origin = "user", message = share.message,
                                conversationId = checkNotNull(conversationId))
                            error = null
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "暂时无法播放"
                        } finally { busy = false }
                    }
                }) { Icon(HugeIcons.Play, contentDescription = "播放这首歌", modifier = Modifier.size(20.dp)) }
            }
            if (share.message.isNotBlank()) Text(share.message, style = MaterialTheme.typography.bodyMedium)
            share.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
