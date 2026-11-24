package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.BitmapImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.listen.*
import me.rerere.rikkahub.ui.components.message.listen.*
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.compose.koinInject

@Composable
fun ListenSongCard(share: ListenShare, conversationId: String?, readOnly: Boolean = false) = key(conversationId, share) {
    // History/export surfaces need only the payload; never subscribe to the player there.
    val repository = if (!readOnly && conversationId != null) koinInject<ListenRepository>() else null
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    fun active(state: ListenState) = state.conversationId == conversationId &&
        state.current?.song?.id == share.song.id && (state.playing || state.loading)
    val playing = if (repository == null) false else remember(repository, conversationId, share.song.id) {
        repository.state.map(::active).distinctUntilChanged()
    }.collectAsStateWithLifecycle(active(repository.state.value)).value
    val context = LocalContext.current
    val bitmap = if (share.song.cover.isBlank()) null else {
        val request = remember(context, share.song.cover) {
            ImageRequest.Builder(context).data(share.song.cover).size(192).allowHardware(false).build()
        }
        val painter = rememberAsyncImagePainter(request)
        val imageState by painter.state.collectAsState()
        ((imageState as? AsyncImagePainter.State.Success)?.result?.image as? BitmapImage)?.bitmap
    }
    val palette by produceState(remember { extractMusicPalette(null) }, bitmap) {
        value = withContext(Dispatchers.Default) { extractMusicPalette(bitmap) }
    }
    Column(Modifier.widthIn(max = 340.dp).padding(vertical = 4.dp)) {
        MusicGiftCard(bitmap, palette, share.lines, expanded, { expanded = !expanded }, onPlay = {
            if (!busy && repository != null && conversationId != null) {
                busy = true; error = null
                scope.launch {
                    try {
                        check(repository.config.value.configured) { "请先在一起听房间连接音乐服务" }
                        val current = repository.state.value
                        if (current.conversationId == conversationId && current.current?.song?.id == share.song.id) {
                            repository.toggle()
                        } else {
                            val song = repository.song(share.song.id)
                            repository.play(song, origin = "user", message = share.message, conversationId = conversationId)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "暂时无法播放" }
                    finally { busy = false }
                }
            }
        }, dark = LocalDarkMode.current, songTitle = share.song.title, artist = share.song.artist,
            message = share.message, playing = playing, enabled = !readOnly && conversationId != null, busy = busy)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
