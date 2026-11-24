package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import me.rerere.rikkahub.R
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

internal val ReadChapterRabbitThumb = 48.dp

@Composable
internal fun ReadChapterRabbit(progress: Float, enabled: Boolean, modifier: Modifier = Modifier) {
    val palette = readSettingColors()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val target = if (rtl) 1f - progress else progress
    val latestTarget = rememberUpdatedState(target)
    val motion = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val nose = animateFloatAsState(target, spring(.94f, 570f, .0008f), label = "rabbit nose")
    val tail = animateFloatAsState(target, spring(.83f, 155f, .0008f), label = "rabbit tail")
    val eyelid = remember { Animatable(0f) }

    LaunchedEffect(enabled, motion) {
        eyelid.snapTo(0f)
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            nose.value == latestTarget.value && tail.value == latestTarget.value && (motion?.scaleFactor ?: 1f) > 0f
        }.collectLatest { settled ->
            eyelid.snapTo(0f)
            if (settled) {
                delay(460)
                while ((motion?.scaleFactor ?: 1f) > 0f) {
                    eyelid.animateTo(1f, tween(85))
                    delay(55)
                    eyelid.animateTo(0f, tween(115))
                    delay(3700)
                }
            }
        }
    }

    val paperMask = ImageBitmap.imageResource(R.drawable.read_chapter_rabbit_paper)
    val inkMask = ImageBitmap.imageResource(R.drawable.read_chapter_rabbit_ink)
    Box(modifier.testTag("read-chapter-rabbit").drawWithCache {
        val base = minOf(1.dp.toPx(), size.width / 48f)
        val travel = (size.width - ReadChapterRabbitThumb.toPx()).coerceAtLeast(0f)
        val ink = palette.ink.copy(alpha = if (enabled) 1f else .55f)
        val inkTint = ColorFilter.tint(ink)
        val paperTint = ColorFilter.tint(palette.paper)
        onDrawBehind {
            if (base <= 0f) return@onDrawBehind
            val front = nose.value.coerceIn(0f, 1f)
            val gap = ((front - tail.value) * travel / base).coerceIn(-22f, 22f)
            val stride = .5f - .5f * cos((front * travel / (21f * base)) * (2.0 * PI)).toFloat()
            val extension = abs(gap) * (.55f + .27f * stride)
            val width = ((40f + extension) * base).coerceAtMost(size.width)
            val height = (27.5f - extension * .33f) * base
            val centerX = (size.width / 2f + (front - .5f) * travel - gap * .38f * base)
                .coerceIn(width / 2f, size.width - width / 2f)
            val left = centerX - width / 2f
            val top = center.y + 14f * base - height
            val origin = IntOffset(left.roundToInt(), top.roundToInt())
            val bounds = IntSize(width.roundToInt(), height.roundToInt())
            drawImage(paperMask, dstOffset = origin, dstSize = bounds, colorFilter = paperTint)
            drawImage(inkMask, dstOffset = origin, dstSize = bounds, colorFilter = inkTint)

            val blink = eyelid.value
            val open = (abs(gap) / 6f).coerceIn(0f, 1f)
            fun eye(cx: Float, cy: Float, tilt: Float) {
                val eyeCenter = Offset(left + cx * width, top + cy * height)
                val delta = Offset(width * .054f, height * tilt) * (1f - .40f * blink)
                drawLine(ink, eyeCenter - delta, eyeCenter + delta, 1.24f * base,
                    cap = StrokeCap.Round, alpha = 1f - open)
                if (open > 0f) {
                    val radius = Offset(base, (1f + .75f * open) * base)
                    drawOval(ink, eyeCenter - radius, Size(radius.x * 2f, radius.y * 2f), alpha = open)
                }
            }
            eye(.426f, .584f, -.022f)
            eye(.775f, .54f, .014f)
        }
    })
}
