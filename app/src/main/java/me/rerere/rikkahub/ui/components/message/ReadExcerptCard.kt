package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.reading.ReadShare

/** The card displays the exact persisted excerpt; it does not grant access to the book. */
@Composable
fun ReadExcerptCard(share: ReadShare) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.widthIn(max = 360.dp).padding(vertical = 4.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("共度 / 我们读到这里", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("《${share.title}》", style = MaterialTheme.typography.titleMedium)
            Text(listOf(share.author, share.chapterTitle).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.width(3.dp).heightIn(min = 48.dp).background(MaterialTheme.colorScheme.primary))
                Text(share.quote, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
            if (share.note.isNotBlank()) {
                HorizontalDivider()
                Text(share.note, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
