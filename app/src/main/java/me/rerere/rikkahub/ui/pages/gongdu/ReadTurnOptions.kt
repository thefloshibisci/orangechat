package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.reading.ReadSettings

@Composable
internal fun ReadTurnOptions(settings: ReadSettings, onChange: (ReadSettings) -> Unit) {
    val choices = listOf("slide" to "平移翻页", "cover" to "覆盖翻页",
        "curl" to "仿真翻页", "vertical" to "上下翻页")
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth().testTag("read-turn-options"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("翻页效果", style = MaterialTheme.typography.titleMedium)
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
            val columns = if ((maxWidth - 24.dp) / 4 >= 78.dp * fontScale) 4 else 2
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (value, label) ->
                            val selected = settings.mode != "scroll" && settings.pageTurn == value
                            val shape = RoundedCornerShape(14.dp)
                            Surface(
                                modifier = Modifier.weight(1f).heightIn(min = 96.dp).clip(shape)
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                        onChange(settings.copy(pageTurn = value,
                                            mode = if (settings.mode == "scroll") "page" else settings.mode))
                                    }).testTag("read-turn-$value"),
                                shape = shape,
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                border = BorderStroke(if (selected) 1.5.dp else .7.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    ReadTurnIcon(value, selected)
                                    Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(if (settings.mode == "scroll") "选择一种效果，进入单页翻读。" else "滑动书页或点底部按钮，翻到下一页。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small page diagrams echo the reader reference while inheriting the room's day/night palette. */
@Composable
private fun ReadTurnIcon(value: String, selected: Boolean) {
    val ink = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val paper = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLowest
    val shade = MaterialTheme.colorScheme.primary.copy(alpha = .14f)
    Canvas(Modifier.size(44.dp)) {
        val unit = size.width / 44f
        val line = 1.5.dp.toPx()
        val rounded = CornerRadius(3 * unit)
        val dashed = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * unit, 3 * unit)))
        fun page(x: Float, y: Float, width: Float, height: Float, ghost: Boolean = false) {
            val origin = Offset(x * unit, y * unit)
            val extent = Size(width * unit, height * unit)
            if (!ghost) drawRoundRect(paper, origin, extent, rounded)
            drawRoundRect(ink, origin, extent, rounded, style = if (ghost) dashed else Stroke(line))
        }
        when (value) {
            "slide" -> {
                page(3f, 5f, 25f, 32f, ghost = true)
                page(16f, 5f, 25f, 32f)
                drawLine(ink, Offset(21f, 21f) * unit, Offset(35f, 21f) * unit, line, StrokeCap.Round)
                drawLine(ink, Offset(21f, 21f) * unit, Offset(25f, 17f) * unit, line, StrokeCap.Round)
                drawLine(ink, Offset(21f, 21f) * unit, Offset(25f, 25f) * unit, line, StrokeCap.Round)
            }
            "cover" -> {
                page(4f, 5f, 27f, 34f, ghost = true)
                drawRoundRect(shade, Offset(13f, 5f) * unit, Size(6f, 34f) * unit, rounded)
                page(18f, 5f, 23f, 34f)
            }
            "curl" -> {
                page(6f, 5f, 31f, 34f, ghost = true)
                val sheet = Path().apply {
                    moveTo(9 * unit, 5 * unit)
                    lineTo(34 * unit, 5 * unit)
                    quadraticTo(37 * unit, 5 * unit, 37 * unit, 8 * unit)
                    quadraticTo(35 * unit, 31 * unit, 9 * unit, 39 * unit)
                    quadraticTo(6 * unit, 39 * unit, 6 * unit, 36 * unit)
                    lineTo(6 * unit, 8 * unit)
                    quadraticTo(6 * unit, 5 * unit, 9 * unit, 5 * unit)
                    close()
                }
                drawPath(sheet, paper)
                drawPath(sheet, ink, style = Stroke(line))
                val fold = Path().apply {
                    moveTo(9 * unit, 39 * unit)
                    quadraticTo(29 * unit, 34 * unit, 37 * unit, 8 * unit)
                    quadraticTo(22 * unit, 20 * unit, 9 * unit, 39 * unit)
                    close()
                }
                drawPath(fold, shade)
            }
            else -> {
                page(7f, 3f, 30f, 27f, ghost = true)
                page(7f, 15f, 30f, 27f)
                drawLine(ink, Offset(22f, 21f) * unit, Offset(22f, 35f) * unit, line, StrokeCap.Round)
                drawLine(ink, Offset(22f, 21f) * unit, Offset(18f, 25f) * unit, line, StrokeCap.Round)
                drawLine(ink, Offset(22f, 21f) * unit, Offset(26f, 25f) * unit, line, StrokeCap.Round)
            }
        }
    }
}
