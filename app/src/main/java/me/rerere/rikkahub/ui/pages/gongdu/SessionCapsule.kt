package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

internal val SessionCapsuleHeight = 42.dp

// Match the centered call pill on both phones and wider windows.
internal fun Modifier.sessionCapsuleWidth() = fillMaxWidth().padding(horizontal = 24.dp)
    .wrapContentWidth().widthIn(max = 380.dp).fillMaxWidth()

@Composable
internal fun Modifier.sessionCapsule(glass: HazeState?, backdropColor: Color): Modifier {
    val colors = MaterialTheme.colorScheme
    val tint = colors.surface.copy(alpha = if (colors.surface.luminance() < .5f) .40f else .34f)
    return clip(CircleShape)
        .border(1.dp, colors.onSurface.copy(alpha = .26f), CircleShape)
        .then(if (glass == null) Modifier.background(tint) else Modifier.hazeEffect(
            state = glass,
            style = HazeStyle(backgroundColor = backdropColor, tint = HazeTint(tint),
                fallbackTint = HazeTint(tint), blurRadius = 5.dp, noiseFactor = 0f),
        ))
}
