package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.rerere.rikkahub.data.gongdu.gongduShareSummary
import me.rerere.rikkahub.data.gongdu.splitGongduShareText
import me.rerere.rikkahub.data.listen.parseListenShare
import me.rerere.rikkahub.data.reading.parseReadShare

/** Old combined text and new separate Text parts use the same lossless presentation. */
@Composable
internal fun GongduShareMessageContent(
    text: String,
    conversationId: String?,
    readOnly: Boolean,
    plainText: @Composable (String) -> Unit,
) {
    val segments = remember(text) { splitGongduShareText(text) }
    if (segments.size == 1 && gongduShareSummary(segments.single()) == null) {
        plainText(text)
        return
    }
    Column {
        segments.forEach { segment ->
            val song = remember(segment) { parseListenShare(segment) }
            val book = remember(segment) { parseReadShare(segment) }
            when {
                song != null -> ListenSongCard(song, conversationId, readOnly)
                book != null -> ReadExcerptCard(book)
                else -> plainText(segment)
            }
        }
    }
}
