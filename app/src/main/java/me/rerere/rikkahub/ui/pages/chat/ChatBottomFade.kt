package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/** Covers only the narrow bottom edge, including gesture/three-button navigation. */
@Composable
internal fun Modifier.chatBottomFade(): Modifier {
    val density = LocalDensity.current
    val navigationHeight = WindowInsets.navigationBars.getBottom(density)
    val tint = if (LocalDarkMode.current) MaterialTheme.colorScheme.surface else Color.White
    return drawWithCache {
        val height = (navigationHeight + 12.dp.toPx()).coerceAtMost(size.height)
        val top = size.height - height
        val brush = Brush.verticalGradient(listOf(tint.copy(alpha = 0f), tint.copy(alpha = 0.9f)), startY = top, endY = size.height)
        onDrawWithContent {
            drawContent()
            drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, height))
        }
    }
}
