package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

// Many complete cycles allow 59 -> 00 and 23 -> 00 in either direction without a visible jump.
private const val WHEEL_CYCLES = 1001

@Composable
internal fun rememberTimeWheelState(value: Int, count: Int): LazyListState =
    rememberLazyListState(initialFirstVisibleItemIndex = (WHEEL_CYCLES / 2) * count + value)

internal fun LazyListState.centeredTimeIndex(): Int {
    val center = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    return layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
        ?: firstVisibleItemIndex
}

@Composable
internal fun TimeWheel(state: LazyListState, count: Int, label: String, modifier: Modifier = Modifier) {
    val selected by remember(state) { derivedStateOf { state.centeredTimeIndex() } }
    val scope = rememberCoroutineScope()
    val rowHeight = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val total = count * WHEEL_CYCLES
    fun step(delta: Int): Boolean {
        scope.launch { state.animateScrollToItem((selected + delta).coerceIn(0, total - 1)) }
        return true
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier.fillMaxWidth().height(rowHeight * 3).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = "%02d".format(selected % count)
                    customActions = listOf(
                        CustomAccessibilityAction("增加$label") { step(1) },
                        CustomAccessibilityAction("减少$label") { step(-1) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth().height(rowHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(vertical = rowHeight),
                flingBehavior = rememberSnapFlingBehavior(state),
            ) {
                items(total, key = { it }) { index ->
                    Box(
                        Modifier.fillMaxWidth().height(rowHeight)
                            .clickable { scope.launch { state.animateScrollToItem(index) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "%02d".format(index % count),
                            style = if (index == selected) MaterialTheme.typography.headlineMedium
                                else MaterialTheme.typography.titleLarge,
                            color = if (index == selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}
