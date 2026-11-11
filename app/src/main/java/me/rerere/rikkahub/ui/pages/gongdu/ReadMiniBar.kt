package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.reading.ReadRepository
import org.koin.compose.koinInject

@Composable
fun ReadMiniBar(conversationId: String, onOpen: () -> Unit) {
    val repository = koinInject<ReadRepository>()
    val session by repository.session.collectAsStateWithLifecycle()
    val book by repository.currentBook.collectAsStateWithLifecycle()
    val progress by repository.progress.collectAsStateWithLifecycle()
    val settings by repository.settings.collectAsStateWithLifecycle()
    val current = book ?: return
    if (session.conversationId != conversationId) return
    ReadTheme(settings) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("共读", fontFamily = GongduBook, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("${current.title} · ${current.chapters.getOrNull(progress.chapter)?.title.orEmpty()}",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("打开书架", style = MaterialTheme.typography.labelSmall)
        }
    }
    }
}
