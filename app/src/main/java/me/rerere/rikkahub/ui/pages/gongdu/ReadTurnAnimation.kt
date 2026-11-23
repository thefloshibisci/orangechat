package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

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
    neighbor: (Int) -> ReadTurnFrame? = { null },
    content: @Composable (ReadTurnFrame, Boolean) -> Unit,
) {
    var current by remember { mutableStateOf<ReadTurnFrame?>(null) }
    var outgoing by remember { mutableStateOf<ReadTurnFrame?>(null) }
    var direction by remember { mutableIntStateOf(1) }
    val amount = remember { Animatable(1f) }
    val settled by rememberUpdatedState(onSettled)
    val scope = rememberCoroutineScope()
    val sheetLayer = rememberGraphicsLayer()
    var dragSource by remember { mutableStateOf<ReadTurnFrame?>(null) }
    var dragTarget by remember { mutableStateOf<ReadTurnFrame?>(null) }
    var dragDirection by remember { mutableIntStateOf(1) }
    var dragAmount by remember { mutableFloatStateOf(0f) }
    var dragAnchor by remember { mutableFloatStateOf(.76f) }
    var dragging by remember { mutableStateOf(false) }
    var releaseJob by remember { mutableStateOf<Job?>(null) }
    var awaitingNavigation by remember { mutableStateOf<ReadTurnFrame?>(null) }
    val preview by rememberUpdatedState(neighbor)
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    LaunchedEffect(frame, effect) {
        settled(null)
        val next = frame ?: return@LaunchedEffect
        val delivered = awaitingNavigation
        if (delivered != null && delivered.position == next.position && delivered.sameGeometry(next)) {
            current = next
            outgoing = null
            dragSource = null
            dragTarget = null
            awaitingNavigation = null
            amount.snapTo(1f)
            settled(next)
            return@LaunchedEffect
        }
        releaseJob?.cancel()
        dragging = false
        dragSource = null
        dragTarget = null
        awaitingNavigation = null
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
    // Keep the completed preview on screen until the repository acknowledges navigation.
    // No progress or viewport is published from a provisional page.
    LaunchedEffect(awaitingNavigation) {
        if (awaitingNavigation != null) {
            kotlinx.coroutines.delay(1500)
            awaitingNavigation = null
            dragSource = null
            dragTarget = null
            settled(current)
        }
    }
    val active = if (dragSource != null) dragTarget else current
    val old = dragSource ?: outgoing
    val ready = frame != null && current == frame && old == null
    val canInteract = enabled && ready
    val canStart by rememberUpdatedState(canInteract)
    val paper = MaterialTheme.colorScheme.surfaceContainerLow
    val ink = MaterialTheme.colorScheme.onSurface
    val threshold = with(LocalDensity.current) { 44.dp.toPx() }
    fun release(commit: Boolean) {
        dragging = false
        if (dragSource == null) return
        releaseJob = scope.launch {
            val start = dragAmount
            val finish = if (commit) 1f else 0f
            animate(start, finish, animationSpec = tween(
                if (commit) (280 * (1f - start)).toInt().coerceIn(120, 280) else 170,
                easing = FastOutSlowInEasing)) { value, _ -> dragAmount = value }
            if (commit) {
                awaitingNavigation = dragTarget
                if (dragDirection > 0) next() else previous()
            } else {
                dragSource = null
                dragTarget = null
                settled(current)
            }
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().testTag("read-turn-stage")
        .semantics {
            stateDescription = if (frame == null) "loading" else if (dragging) "dragging:$effect"
                else if (ready) "settled:$effect" else "turning:$effect"
            this[ReadTurnFraction] = if (dragSource != null) dragAmount else amount.value
        }
        .pointerInput(effect, enabled) {
            if (!enabled) return@pointerInput
            var distance = 0f
            var accepted = false
            val velocity = VelocityTracker()
            val end: () -> Unit = {
                if (effect == "curl" && accepted) {
                    val speed = velocity.calculateVelocity().x
                    val toward = if (dragDirection > 0) -speed else speed
                    val travel = if (dragDirection > 0) -distance else distance
                    val commit = travel > size.width * .28f || travel > threshold && toward > threshold * 18f
                    if (dragSource != null) release(commit)
                    // A very fast swipe can beat adjacent-chapter pagination. Still honor navigation;
                    // the delivered frame will use the normal release animation in that case.
                    else if (commit) { if (dragDirection > 0) next() else previous() }
                } else if (accepted && abs(distance) >= threshold) { if (distance < 0) next() else previous() }
                distance = 0f
            }
            if (effect == "vertical") detectVerticalDragGestures(
                onDragStart = { distance = 0f; accepted = canStart }, onDragEnd = end, onDragCancel = { distance = 0f },
                onVerticalDrag = { change, delta -> if (accepted) { change.consume(); distance += delta } })
            else detectHorizontalDragGestures(
                onDragStart = { position ->
                    distance = 0f
                    accepted = canStart
                    velocity.resetTracking()
                    dragAnchor = (position.y / size.height).coerceIn(0f, 1f)
                }, onDragEnd = end, onDragCancel = { distance = 0f; if (effect == "curl" && accepted) release(false) },
                onHorizontalDrag = { change, delta ->
                    if (accepted) {
                        change.consume()
                        distance += delta
                        velocity.addPosition(change.uptimeMillis, change.position)
                        if (effect == "curl") {
                            if (dragSource == null) {
                                dragDirection = if (distance < 0) 1 else -1
                                dragTarget = preview(dragDirection)
                                if (dragTarget != null) { dragSource = current; dragging = true; settled(null) }
                            }
                            val toward = if (dragDirection > 0) -distance else distance
                            dragAmount = (toward / (size.width * 2f)).coerceIn(0f, .95f)
                            dragAnchor = (change.position.y / size.height).coerceIn(0f, 1f)
                        }
                    }
                })
        }) {
        if (active != null) {
            if (old == null) content(active, canInteract)
            else {
                val isDrag = dragSource != null
                val turnDirection = if (isDrag) dragDirection else direction
                val fraction = { if (isDrag) dragAmount else amount.value }
                if (effect == "curl") {
                    val underneath = if (turnDirection > 0) active else old
                    val turning = if (turnDirection > 0) old else active
                    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) { content(underneath, false) }
                    Box(Modifier.fillMaxSize().clearAndSetSemantics { testTag = "read-turn-outgoing" }
                        .curlPage({ fraction().let { if (turnDirection > 0) it else 1f - it } }, paper, ink,
                            { if (isDrag) dragAnchor else .76f }, sheetLayer)) { content(turning, false) }
                } else {
                    val oldModifier = Modifier.fillMaxSize().graphicsLayer {
                        val t = fraction()
                        when (effect) {
                            "vertical" -> translationY = -direction * t * size.height
                            "cover" -> translationX = if (direction > 0) -t * size.width else 0f
                            else -> translationX = -direction * t * size.width
                        }
                        shadowElevation = if (effect == "cover") 8.dp.toPx() else 0f
                    }
                    val newModifier = Modifier.fillMaxSize().graphicsLayer {
                        val t = fraction()
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

/** A cylindrical bend: adjacent strips project real glyphs onto the paper curve.
 * Each draw records one display list, shared by all strips without remeasuring text or making bitmaps.
 */
private fun Modifier.curlPage(fraction: () -> Float, paper: Color, ink: Color,
    grip: () -> Float, layer: GraphicsLayer) = drawWithContent {
    val progress = fraction()
    if (progress <= .0001f) { drawContent(); return@drawWithContent }
    if (progress >= .9999f) return@drawWithContent
    layer.record { this@drawWithContent.drawContent() }
    // Keep a visible strip at the top as well as the bottom of the turning sheet.
    val lean = (.085f + (grip() - .5f) * .32f).coerceIn(-.13f, .23f)
    val slope = lean * size.width / size.height
    // Previous-page turns unfold this same sheet from the left. Mirroring the axis as well
    // as reversing progress would incorrectly make the previous page enter from the right.
    // A diagonal hinge changes horizontal position by row. Each row stays on its original baseline.
    val origin = Offset(0f, size.height * .5f)
    fun coordinate(point: Offset) = point.x + slope * (point.y - origin.y)
    fun projected(point: Offset, scale: Float, shift: Float) =
        Offset(point.x + (scale - 1f) * coordinate(point) + shift, point.y)
    val corners = listOf(Offset.Zero, Offset(size.width, 0f), Offset(size.width, size.height), Offset(0f, size.height))
    val low = corners.minOf(::coordinate)
    val high = corners.maxOf(::coordinate)
    val travel = high - low
    val revealed = progress + .46f * progress * (1f - progress) * (1f - progress)
    val radius = minOf(140.dp.toPx(), size.width * .21f,
        travel * revealed * 2f / PI.toFloat()) * sqrt(sin(PI.toFloat() * progress).coerceAtLeast(0f))
    val bendLength = PI.toFloat() * radius
    val hinge = high - travel * revealed - bendLength / 2f
    fun clipped(points: List<Offset>, cut: Float, before: Boolean): List<Offset> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf<Offset>()
        points.indices.forEach { i ->
            val a = points[i]; val b = points[(i + 1) % points.size]
            val da = coordinate(a) - cut; val db = coordinate(b) - cut
            val insideA = if (before) da <= 0 else da >= 0
            val insideB = if (before) db <= 0 else db >= 0
            if (insideA) result += a
            if (insideA != insideB) result += a + (b - a) * (da / (da - db))
        }
        return result
    }
    fun polygon(points: List<Offset>) = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }
    val reversePaper = Path()
    fun projectBand(from: Float, to: Float, scale: Float, shift: Float): Path? {
        val points = clipped(clipped(corners, from, false), to, true)
        if (points.size < 3) return null
        return polygon(points.map { projected(it, scale, shift) })
    }
    val matrix = Matrix()
    fun band(shape: Path, scale: Float, shift: Float) {
        clipPath(shape) {
            matrix.apply {
                this[0, 0] = scale
                this[1, 0] = (scale - 1f) * slope
                this[3, 0] = shift - (scale - 1f) * slope * origin.y
            }
            val canvas = drawContext.canvas
            canvas.save()
            canvas.concat(matrix)
            drawLayer(layer)
            canvas.restore()
        }
    }
    projectBand(low, hinge, 1f, 0f)?.let { clipPath(it) { drawLayer(layer) } }

    data class BendStrip(val scale: Float, val shift: Float, val path: Path)
    val strips = 16
    fun curvedStrip(i: Int): BendStrip? {
        val a = PI.toFloat() * i / strips
        val b = PI.toFloat() * (i + 1) / strips
        val from = hinge + radius * a
        val to = hinge + radius * b
        val left = hinge + radius * sin(a)
        val right = hinge + radius * sin(b)
        val scale = (right - left) / (to - from).coerceAtLeast(.001f)
        val shift = left - scale * from
        return projectBand(from, to, scale, shift)?.let { BendStrip(scale, shift, it) }
    }

    // Near the leading corner some rows have too little paper to wrap the cylinder.
    // Fill their visible front before painting the back; otherwise the next page shows
    // through a second slit on the left of the raised sheet.
    val shortestRowEdge = minOf(coordinate(corners[1]), coordinate(corners[2]))
    if (shortestRowEdge < hinge + bendLength) {
        for (i in 0 until strips / 2) {
            curvedStrip(i)?.let { band(it.path, it.scale, it.shift) }
        }
    }
    val rear = projectBand(hinge + bendLength, high, -1f, 2f * hinge + bendLength)
    if (high > hinge + bendLength) {
        val backPoints = clipped(corners, hinge + bendLength, false).map {
            projected(it, -1f, 2f * hinge + bendLength)
        }
        // The raised paper casts a soft shadow on the old page, strongest at contact.
        for (step in 4 downTo 1) {
            val shadow = polygon(backPoints.map { it - Offset((step * 2.4f).dp.toPx(), 0f) })
            drawPath(shadow, ink.copy(alpha = .018f))
        }
        rear?.let(reversePaper::addPath)
    }
    val bend = (strips / 2 until strips).mapNotNull(::curvedStrip)
    bend.forEach { reversePaper.addPath(it.path) }
    if (rear == null && bend.isEmpty()) return@drawWithContent
    drawPath(reversePaper, paper)
    val canvas = drawContext.canvas
    // Fade all mirrored glyphs together; one bounded offscreen pass, not one per strip.
    canvas.saveLayer(reversePaper.getBounds(), Paint().apply { alpha = .17f })
    rear?.let { band(it, -1f, 2f * hinge + bendLength) }
    bend.forEach { band(it.path, it.scale, it.shift) }
    canvas.restore()
    // One light field across the back and the bend prevents a visible join between them.
    val gradientAxis = Offset(1f, slope) / (1f + slope * slope)
    drawPath(reversePaper, Brush.linearGradient(0f to ink.copy(alpha = .008f), .40f to ink.copy(alpha = .004f),
        .78f to ink.copy(alpha = .05f), 1f to ink.copy(alpha = .15f), start = origin + gradientAxis * (hinge - radius),
        end = origin + gradientAxis * (hinge + radius)))
}
