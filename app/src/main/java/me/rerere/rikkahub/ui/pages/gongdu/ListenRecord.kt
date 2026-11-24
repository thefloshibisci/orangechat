package me.rerere.rikkahub.ui.pages.gongdu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.listen.MusicPalette
import me.rerere.rikkahub.ui.components.message.listen.extractMusicPalette

internal data class RecordArt(val source: String, val bitmap: Bitmap?, val palette: MusicPalette)
private val neutralArt = RecordArt("", null, extractMusicPalette(null))
private const val CHANGE_MS = 1550

/** Transient room UI only, retained across player subpages. Never enters the playback or share payload. */
internal class RecordDisplayState {
    var current by mutableStateOf(neutralArt)
        internal set
    var custom by mutableStateOf<RecordArt?>(null)
    var angle by mutableFloatStateOf(0f)
    var coverError by mutableStateOf(false)
}

internal class RecordTransition(val current: RecordArt, val previous: RecordArt?, val phase: State<Float>) {
    fun blend(tone: (MusicPalette) -> Color): Color = previous?.let {
        lerp(tone(it.palette), tone(current.palette), phase.value)
    } ?: tone(current.palette)
}

private const val COVER_PIXELS = 512

private suspend fun loadRecordCover(context: Context, cover: String): Bitmap? {
    val request = ImageRequest.Builder(context).data(cover).size(COVER_PIXELS).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
    return (result?.image as? BitmapImage)?.bitmap
}

// Read the one-time picker grant directly. No copied file, persistent permission or shared image cache.
private fun decodeLocalCover(context: Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val scale = minOf(1f, COVER_PIXELS.toFloat() / maxOf(width, height))
            decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        }
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    val longest = maxOf(options.outWidth, options.outHeight)
    require(longest > 0)
    var sample = 1
    while (longest / sample > COVER_PIXELS) sample *= 2
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    return requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) })
}

@Composable
internal fun rememberListenCoverPicker(display: RecordDisplayState): () -> Unit {
    val context = LocalContext.current
    var selection by remember(display) { mutableStateOf<Pair<Int, Uri>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) selection = ((selection?.first ?: 0) + 1) to uri
    }
    LaunchedEffect(display, selection) {
        val (id, uri) = selection ?: return@LaunchedEffect
        display.coverError = false
        try {
            display.custom = withContext(Dispatchers.IO) {
                val image = decodeLocalCover(context, uri)
                RecordArt("local:$id", image, extractMusicPalette(image))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            display.coverError = true
        }
    }
    return { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
internal fun rememberRecordTransition(
    display: RecordDisplayState,
    cover: String,
    hasTrack: Boolean,
    loadCover: suspend (Context, String) -> Bitmap? = ::loadRecordCover,
): RecordTransition = key(display) {
    val context = LocalContext.current
    val ready = if (!hasTrack || cover.isBlank()) null else key(cover) {
        produceState<RecordArt?>(null, context, loadCover) {
            val visible = display.current
            if (visible.source == cover && visible.bitmap != null) {
                value = visible
            } else {
                val bitmap = try {
                    loadCover(context, cover)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                value = if (bitmap == null) neutralArt.copy(source = cover) else withContext(Dispatchers.Default) {
                    RecordArt(cover, bitmap, extractMusicPalette(bitmap))
                }
            }
        }.value
    }
    val target by rememberUpdatedState(
        when {
            !hasTrack -> display.custom ?: neutralArt
            cover.isBlank() -> neutralArt
            else -> ready
        },
    )
    var previous by remember { mutableStateOf<RecordArt?>(null) }
    val progress = remember { Animatable(1f) }
    val phase = remember { derivedStateOf { progress.value } }
    LaunchedEffect(Unit) {
        // Complete the visible wave, then take only the newest ready result.
        snapshotFlow { target }.collect { next ->
            if (next == null || next != target || next == display.current) return@collect
            val old = display.current
            if (old.bitmap == next.bitmap && old.palette == next.palette) {
                display.current = next
                return@collect
            }
            progress.snapTo(0f)
            previous = old
            display.current = next
            progress.animateTo(1f, tween(CHANGE_MS, easing = LinearEasing))
            previous = null
        }
    }
    val current = display.current
    remember(current, previous, phase) { RecordTransition(current, previous, phase) }
}

@Composable
internal fun ListenRecord(
    art: RecordTransition,
    display: RecordDisplayState,
    modifier: Modifier = Modifier,
    spinning: Boolean = false,
    turntable: Boolean = true,
    onChoose: (() -> Unit)? = null,
    onLyrics: (() -> Unit)? = null,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val motion = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    LaunchedEffect(display, spinning, lifecycle) {
        if (!spinning || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        var last = 0L
        while (true) withInfiniteAnimationFrameNanos { now ->
            if (last != 0L && (motion?.scaleFactor ?: 1f) > 0f) {
                display.angle = (display.angle + ((now - last) / 1_000_000_000f).coerceAtMost(.05f) * 9f) % 360f
            }
            last = now
        }
    }
    val palette = art.current.palette
    val dark = me.rerere.rikkahub.ui.theme.LocalDarkMode.current
    val cover = art.current.bitmap
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize(if (turntable) .94f else 1f), contentAlignment = Alignment.Center) {
            val sheet = Modifier.fillMaxSize()
            Box(sheet.drawWithCache {
                val r = size.minDimension * .465f
                val under = Offset(size.width / 2f + .031f * r, size.height / 2f + .079f * r)
                val shade = Color(0xFF233543)
                val shadow = Brush.radialGradient(
                    0f to Color.Transparent, .49f to shade.copy(alpha = .035f), .72f to shade.copy(alpha = .065f),
                    .83f to shade.copy(alpha = .044f), .915f to shade.copy(alpha = .022f), 1f to Color.Transparent,
                    center = under, radius = r * 1.14f,
                )
                onDrawBehind { drawCircle(shadow, r * 1.14f, under) }
            })
            val mask = ImageBitmap.imageResource(R.drawable.listen_player_absorption).asAndroidBitmap()
            val tint = palette.recordTint
            Box(sheet.glassTint(mask, art.previous?.palette?.recordTint ?: tint, tint, art.phase))
            Image(painterResource(R.drawable.listen_player_light), null, sheet, alpha = if (dark) .76f else 1f)
            Image(painterResource(if (turntable) R.drawable.listen_player_flecks else R.drawable.listen_record_flecks_small),
                null, sheet.graphicsLayer { rotationZ = display.angle })
            val lyricsTarget = if (onLyrics == null) Modifier else Modifier.clip(CircleShape)
                .testTag("listen-disc-lyrics").semantics { contentDescription = "查看完整歌词" }
                .clickable(role = Role.Button, onClick = onLyrics)
            Box(sheet.then(lyricsTarget), contentAlignment = Alignment.Center) {
                val label = Modifier.fillMaxSize(.55f).clip(CircleShape)
                val pickerTarget = if (onChoose == null) Modifier else Modifier
                    .testTag("listen-disc-cover")
                    .semantics { contentDescription = if (cover == null) "选择唱片封面" else "更换唱片封面" }
                    .clickable(role = Role.Button, onClick = onChoose)
                Box(label.then(pickerTarget)) {
                    art.previous?.let { DiscLabel(it, display, Modifier.fillMaxSize(), false, null, dark) }
                    DiscLabel(art.current, display, Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (art.previous == null) 1f else (art.phase.value / .60f).coerceIn(0f, 1f)
                    }, onChoose != null, if (cover != null) {
                        if (art.current.source.startsWith("local:")) "自选唱片封面" else "专辑封面"
                    } else null, dark)
                }
            }
        }
        if (turntable) RecordArm(Modifier.fillMaxSize())
    }
}

@Composable
private fun DiscLabel(
    art: RecordArt, display: RecordDisplayState, modifier: Modifier, plus: Boolean, description: String?, dark: Boolean,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val cover = art.bitmap
        if (cover != null) {
            Image(remember(cover) { cover.asImageBitmap() }, description,
                Modifier.fillMaxSize().graphicsLayer { rotationZ = display.angle }, contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().drawWithCache {
                val night = Color(0xFF252D37)
                val colors = with(art.palette) {
                    if (dark) listOf(lerp(night, wash, .16f), lerp(night, recordTint, .22f)) else listOf(background, wash)
                }
                val glass = Brush.radialGradient(colors)
                onDrawBehind { drawCircle(glass) }
            })
            if (plus) Icon(HugeIcons.Add01, null, Modifier.size(22.dp),
                tint = if (dark) lerp(art.palette.accent, Color.White, .68f) else art.palette.accent)
        }
    }
}

/** The shader is allocated per pair and size. A frame only moves its local matrix. */
private fun Modifier.glassTint(mask: Bitmap, from: Color, to: Color, phase: State<Float>) = drawWithCache {
    val source = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    source.setLocalMatrix(Matrix().apply { setScale(size.width / mask.width, size.height / mask.height) })
    val radial = RadialGradient(0f, 0f, 1f, intArrayOf(to.toArgb(), to.toArgb(), from.toArgb()),
        floatArrayOf(0f, .72f, 1f), Shader.TileMode.CLAMP)
    val shader = ComposeShader(source, radial, PorterDuff.Mode.SRC_IN)
    val matrix = Matrix()
    val moving = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
    val old = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(from.toArgb(), PorterDuff.Mode.SRC_IN)
    }
    val settled = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(to.toArgb(), PorterDuff.Mode.SRC_IN)
    }
    val bounds = RectF(0f, 0f, size.width, size.height)
    onDrawBehind {
        val fraction = if (from == to) 1f else phase.value
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            when {
                fraction >= 1f -> native.drawBitmap(mask, null, bounds, settled)
                fraction <= 0f -> native.drawBitmap(mask, null, bounds, old)
                else -> {
                    val radius = size.minDimension * .465f * (.52f + .90f * fraction)
                    matrix.setScale(radius, radius)
                    matrix.postTranslate(center.x, center.y)
                    radial.setLocalMatrix(matrix)
                    native.drawRect(bounds, moving)
                }
            }
        }
    }
}

@Composable
private fun RecordArm(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun point(x: Float, y: Float) = Offset(w * x, h * y)
        val pivot = point(.949f, .125f)
        val tip = point(.764f, .754f)
        val arm = Path().apply {
            moveTo(pivot.x, pivot.y)
            lineTo(w * .867f, h * .598f)
            cubicTo(w * .855f, h * .658f, w * .825f, h * .705f, tip.x, tip.y)
        }
        val shadow = Color(0x041D2A37)
        translate(w * .004f, h * .006f) {
            for (band in 3 downTo 0) {
                drawPath(arm, shadow, style = Stroke(w * (.011f + band * .004f), cap = StrokeCap.Round))
            }
        }
        drawPath(arm, Color(0x7096A6AC), style = Stroke(w * .0105f, cap = StrokeCap.Round))
        drawPath(arm, Color(0xE2E8EEEE), style = Stroke(w * .008f, cap = StrokeCap.Round))
        drawPath(arm, Color(0xF0FFFFFF), style = Stroke(w * .0034f, cap = StrokeCap.Round))
        fun block(x: Float, y: Float, width: Float, height: Float) {
            val corner = CornerRadius(w * .0035f)
            val blockSize = Size(w * width, h * height)
            for (band in 3 downTo 0) {
                val spread = w * band * .0018f
                drawRoundRect(shadow, point(x + .003f, y + .004f) - Offset(spread, spread),
                    Size(blockSize.width + 2f * spread, blockSize.height + 2f * spread),
                    CornerRadius(corner.x + spread))
            }
            drawRoundRect(Brush.verticalGradient(
                .22f to Color(0xFFFCFDFC), .64f to Color(0xFFF0F4F2), 1f to Color(0xFFE1E7E5),
                startY = h * y, endY = h * (y + height),
            ), point(x, y), blockSize, corner)
        }
        rotate(8f, pivot) {
            block(.937f, .089f, .024f, .014f)
            block(.920f, .101f, .058f, .047f)
            for (i in 0..2) {
                val y = .108f + i * .011f
                drawLine(Color(0xB6FFFFFF), point(.924f, y), point(.973f, y), w * .004f)
            }
            block(.929f, .179f, .040f, .078f)
        }
        rotate(-27f, tip) {
            block(.723f, .733f, .082f, .038f)
            drawLine(Color.White, point(.730f, .740f), point(.796f, .740f), w * .0029f)
        }
    }
}
