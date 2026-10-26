package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn

internal fun chatInputShouldStop(loading: Boolean, isEmpty: Boolean): Boolean = loading && isEmpty

@Composable
internal fun ChatSendButton(
    loading: Boolean,
    isEmpty: Boolean,
    onSend: () -> Unit,
    onSendWithoutAnswer: () -> Unit,
    onStop: () -> Unit,
) {
    val stop = chatInputShouldStop(loading, isEmpty)
    // Typing the next message must not release the screen-on lease of the running reply.
    if (loading) KeepScreenOn()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(30.dp).clip(CircleShape).combinedClickable(
            enabled = loading || !isEmpty,
            onClick = { if (stop) onStop() else onSend() },
            onLongClick = { if (stop) onStop() else onSendWithoutAnswer() },
        ),
    ) {
        val containerColor = when {
            stop -> MaterialTheme.colorScheme.errorContainer
            isEmpty -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.primary
        }
        val contentColor = when {
            stop -> MaterialTheme.colorScheme.onErrorContainer
            isEmpty -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            else -> MaterialTheme.colorScheme.onPrimary
        }
        Surface(Modifier.fillMaxSize(), shape = CircleShape, color = containerColor, content = {})
        Icon(
            imageVector = if (stop) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
            contentDescription = stringResource(if (stop) R.string.stop else R.string.send),
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
    }
}
