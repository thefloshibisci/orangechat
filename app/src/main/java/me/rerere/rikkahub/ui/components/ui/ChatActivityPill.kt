package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp

/** A thin scrim over the chat backdrop, not a separate card or a faded content layer. */
@Composable
internal fun ChatActivityPill(
    radius: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val chat = MaterialTheme.colorScheme
    val color = chat.surface.copy(alpha = if (chat.surface.luminance() < .5f) .66f else .54f)
    val shape = RoundedCornerShape(radius)
    if (onClick == null) {
        Surface(modifier = modifier, shape = shape, color = color, contentColor = chat.onSurface, content = content)
    } else {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color,
            contentColor = chat.onSurface, content = content)
    }
}
