package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Keep the thumb local while dragging; persist once on release (also used by accessibility actions). */
@Composable
fun CommitSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    var draft by remember { mutableFloatStateOf(value.coerceIn(valueRange)) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value, valueRange) {
        if (!dragging) draft = value.coerceIn(valueRange)
    }
    Slider(
        value = draft,
        onValueChange = { dragging = true; draft = it },
        onValueChangeFinished = {
            dragging = false
            onValueChange(draft)
        },
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
    )
}
