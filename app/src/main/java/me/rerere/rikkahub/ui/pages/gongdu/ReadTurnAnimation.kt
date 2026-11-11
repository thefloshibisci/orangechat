package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.reading.ReadChapter
import me.rerere.rikkahub.data.reading.ReadProgress
import kotlin.math.abs

internal val ReadTurnFraction = SemanticsPropertyKey<Float>("ReadTurnFraction")

/** Immutable render data: the outgoing page has no progress, selection, or viewport side effects. */
internal data class ReadTurnFrame(
    val chapterIndex: Int, val chapter: ReadChapter, val pages: List<ReadLayoutPage>,
    val start: Int, val columns: Int, val pageWidth: Dp, val imageHeight: Dp,
    val style: TextStyle, val paragraphSpacing: Int, val layoutHeight: Int,
) {
    val position: ReadProgress get() = pages[start].position(chapterIndex)
    fun sameGeometry(other: ReadTurnFrame) = columns == other.columns && pageWidth == other.pageWidth &&
        layoutHeight == other.layoutHeight && style == other.style && paragraphSpacing == other.paragraphSpacing
}

@Composable
internal fun ReadTurnStage(
    frame: ReadTurnFrame?, effect: String, enabled: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, onSettled: (ReadTurnFrame?) -> Unit,
    content: @Composable (ReadTurnFrame, Boolean) -> Unit,
) {
    var current by remember { mutableStateOf<ReadTurnFrame?>(null) }
    var outgoing by remember { mutableStateOf<ReadTurnFrame?>(null) }
    var direction by remember { mutableIntStateOf(1) }
    val amount = remember { Animatable(1f) }
    val settled by rememberUpdatedState(onSettled)
    LaunchedEffect(frame, effect) {
        settled(null)
        val next = frame ?: return@LaunchedEffect
        val before = current
        val animate = before != null && before.sameGeometry(next) && before.position != next.position
        if (animate) {
            direction = if (next.chapterIndex > before.chapterIndex ||
                next.chapterIndex == before.chapterIndex && next.start > before.start) 1 else -1
            outgoing = before
            current = next
            amount.snapTo(0f)
            // Animatable honors the system duration scale, including disabled animations.
            amount.animateTo(1f, tween(if (effect == "curl") 420 else 260, easing = FastOutSlowInEasing))
        } else { current = next; amount.snapTo(1f) }
        outgoing = null
        settled(next)
    }
    val active = current
    val old = outgoing
    val ready = frame != null && active == frame && old == null && amount.value == 1f
    val canInteract = enabled && ready
    val paper = MaterialTheme.colorScheme.surfaceContainerLow
    val ink = MaterialTheme.colorScheme.onSurface
    val threshold = with(LocalDensity.current) { 44.dp.toPx() }
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    Box(Modifier.fillMaxSize().clipToBounds().testTag("read-turn-stage")
        .semantics {
            stateDescription = if (frame == null) "loading" else if (ready) "settled:$effect" else "turning:$effect"
            this[ReadTurnFraction] = amount.value
        }
        .pointerInput(effect, canInteract) {
            if (!canInteract) return@pointerInput
            var distance = 0f
            val end: () -> Unit = {
                if (abs(distance) >= threshold) { if (distance < 0) next() else previous() }
                distance = 0f
            }
            if (effect == "vertical") detectVerticalDragGestures(
                onDragStart = { distance = 0f }, onDragEnd = end, onDragCancel = { distance = 0f },
                onVerticalDrag = { change, delta -> change.consume(); distance += delta })
            else detectHorizontalDragGestures(
                onDragStart = { distance = 0f }, onDragEnd = end, onDragCancel = { distance = 0f },
                onHorizontalDrag = { change, delta -> change.consume(); distance += delta })
        }) {
        if (active != null) {
            if (old == null) content(active, canInteract)
            else {
                val t = amount.value
                if (effect == "curl") {
                    val underneath = if (direction > 0) active else old
                    val turning = if (direction > 0) old else active
                    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) { content(underneath, false) }
                    Box(Modifier.fillMaxSize().clearAndSetSemantics { testTag = "read-turn-outgoing" }
                        .curlPage(if (direction > 0) t else 1f - t, direction < 0, paper, ink)) { content(turning, false) }
                } else {
                    val oldModifier = Modifier.fillMaxSize().graphicsLayer {
                        when (effect) {
                            "vertical" -> translationY = -direction * t * size.height
                            "cover" -> translationX = if (direction > 0) -t * size.width else 0f
                            else -> translationX = -direction * t * size.width
                        }
                        shadowElevation = if (effect == "cover") 8.dp.toPx() else 0f
                    }
                    val newModifier = Modifier.fillMaxSize().graphicsLayer {
                        when (effect) {
                            "vertical" -> translationY = direction * (1f - t) * size.height
                            "cover" -> translationX = if (direction < 0) -(1f - t) * size.width else 0f
                            else -> translationX = direction * (1f - t) * size.width
                        }
                        shadowElevation = if (effect == "cover") 8.dp.toPx() else 0f
                    }
                    if (effect == "cover" && direction > 0) {
                        Box(newModifier.clearAndSetSemantics {}) { content(active, false) }
                        Box(oldModifier.clearAndSetSemantics { testTag = "read-turn-outgoing" }) { content(old, false) }
                    } else {
                        Box(oldModifier.clearAndSetSemantics { testTag = "read-turn-outgoing" }) { content(old, false) }
                        Box(newModifier.clearAndSetSemantics {}) { content(active, false) }
                    }
                }
            }
        }
        if (frame == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

/** Fold the actual outgoing sheet around a moving diagonal, showing its shaded paper back. */
private fun Modifier.curlPage(progress: Float, backwards: Boolean, paper: Color, ink: Color) = drawWithContent {
    val cut = (size.width + size.height) * (1f - progress)
    val corners = listOf(Offset.Zero, Offset(size.width, 0f), Offset(size.width, size.height), Offset(0f, size.height))
    fun clipped(keepFront: Boolean): List<Offset> {
        val result = mutableListOf<Offset>()
        corners.indices.forEach { i ->
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            val da = a.x + a.y - cut; val db = b.x + b.y - cut
            val insideA = if (keepFront) da <= 0 else da >= 0
            val insideB = if (keepFront) db <= 0 else db >= 0
            if (insideA) result += a
            if (insideA != insideB) result += a + (b - a) * (da / (da - db))
        }
        return result
    }
    fun path(points: List<Offset>) = Path().apply {
        points.forEachIndexed { i, p ->
            val x = if (backwards) size.width - p.x else p.x
            if (i == 0) moveTo(x, p.y) else lineTo(x, p.y)
        }; close()
    }
    clipPath(path(clipped(true))) { this@drawWithContent.drawContent() }
    val back = path(clipped(false).map { Offset(cut - it.y, cut - it.x) })
    val edge = Offset(if (backwards) size.width - cut / 2 else cut / 2, cut / 2)
    val shadeEnd = edge + Offset(if (backwards) 70.dp.toPx() else -70.dp.toPx(), -70.dp.toPx())
    drawPath(back, Brush.linearGradient(listOf(ink.copy(alpha = .18f), Color.Transparent), edge, shadeEnd))
    drawPath(back, Brush.linearGradient(listOf(paper, paper.copy(alpha = .96f), paper), edge, shadeEnd))
    drawPath(back, Brush.linearGradient(listOf(ink.copy(alpha = .20f), Color.Transparent, ink.copy(alpha = .07f)), edge, shadeEnd))
}
