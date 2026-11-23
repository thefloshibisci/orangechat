package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.reading.ReadRepository
import org.koin.compose.koinInject
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01

@Composable
internal fun ChatReadMiniBar(
    conversationId: String,
    readGlass: HazeState? = null,
    backdropColor: Color = MaterialTheme.colorScheme.background,
    onShare: (String) -> Boolean,
) {
    key(conversationId) {
        var requestedBookId by rememberSaveable { mutableStateOf<String?>(null) }
        ReadMiniBar(conversationId, { requestedBookId = it }, readGlass, backdropColor)
        requestedBookId?.let { id ->
            Dialog(
                onDismissRequest = { requestedBookId = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                GongduReadRoom(conversationId, onNavigateBack = { requestedBookId = null },
                    onShare = { if (onShare(it)) requestedBookId = null }, initialBookId = id)
            }
        }
    }
}

@Composable
fun ReadMiniBar(
    conversationId: String,
    onOpen: (String) -> Unit,
    readGlass: HazeState? = null,
    backdropColor: Color = MaterialTheme.colorScheme.background,
) {
    val repository = koinInject<ReadRepository>()
    val session by repository.session.collectAsStateWithLifecycle()
    val book by repository.currentBook.collectAsStateWithLifecycle()
    val progress by repository.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val current = book ?: return
    if (session.conversationId != conversationId || session.bookId != current.id) return
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.sessionCapsuleWidth().testTag("read-mini-bar")
            .sessionCapsule(readGlass, backdropColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f)
                .clickable(onClickLabel = "打开共读", onClick = { onOpen(current.id) })
                .heightIn(min = SessionCapsuleHeight)
                .padding(start = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("共读", style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
            Text("${current.title} · ${current.chapters.getOrNull(progress.chapter)?.title.orEmpty()}",
                style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(
            onClick = { scope.launch { repository.closeBook(conversationId) } },
            modifier = Modifier.size(width = 48.dp, height = SessionCapsuleHeight),
        ) {
            Icon(HugeIcons.Cancel01, "结束共读", Modifier.size(19.dp), tint = ink)
        }
    }
}
