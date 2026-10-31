package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import androidx.compose.ui.unit.offset
import me.rerere.rikkahub.data.datastore.BubbleStickerCorner
import me.rerere.rikkahub.data.datastore.BubbleFrameSetting
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** A full, unstretched sticker placed above the bubble, straddling a chosen corner. */
@Composable
internal fun BubbleFrame(
    setting: BubbleFrameSetting,
    modifier: Modifier = Modifier,
    isUser: Boolean = false,
    content: @Composable () -> Unit,
) {
    val frame = setting.normalized()
    val file = remember(frame.path) { File(frame.path).takeIf { frame.path.isNotBlank() && it.isFile } }
    if (file == null) { content(); return }
    val context = LocalContext.current
    val request = remember(file, context) {
        ImageRequest.Builder(context).data(file).size(512).allowHardware(false).build()
    }
    val painter = rememberAsyncImagePainter(request)
    val state by painter.state.collectAsState()
    val bitmap = remember(state) {
        (((state as? AsyncImagePainter.State.Success)?.result?.image) as? BitmapImage)?.bitmap?.asImageBitmap()
    }
    val corner = if (frame.corner == BubbleStickerCorner.AUTO) {
        if (isUser) BubbleStickerCorner.TOP_LEFT else BubbleStickerCorner.BOTTOM_RIGHT
    } else frame.corner
    Layout(modifier = modifier, content = {
        Box { content() }
        Canvas(Modifier) {
            bitmap?.let { image ->
                val ratio = min(size.width / image.width, size.height / image.height)
                val w = (image.width * ratio).roundToInt().coerceAtLeast(1)
                val h = (image.height * ratio).roundToInt().coerceAtLeast(1)
                drawImage(image, dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()), dstSize = IntSize(w, h))
            }
        }
    }) { measurables, constraints ->
        val stickerSize = (32.dp.toPx() * frame.scale).roundToInt().coerceAtLeast(1)
        val dx = frame.offsetX.dp.roundToPx()
        val dy = frame.offsetY.dp.roundToPx()
        // Horizontal overflow is decorative: it must not narrow or shift the bubble.
        val maxVerticalOverflow = (stickerSize + 1) / 2 + abs(dy)
        val bubble = measurables[0].measure(constraints.offset(vertical = -maxVerticalOverflow).copy(minWidth = 0, minHeight = 0))
        val sticker = measurables[1].measure(Constraints.fixed(stickerSize, stickerSize))
        val right = corner == BubbleStickerCorner.TOP_RIGHT || corner == BubbleStickerCorner.BOTTOM_RIGHT
        val bottom = corner == BubbleStickerCorner.BOTTOM_LEFT || corner == BubbleStickerCorner.BOTTOM_RIGHT
        val stickerX = (if (right) bubble.width else 0) - stickerSize / 2 + dx
        val stickerY = (if (bottom) bubble.height else 0) - stickerSize / 2 + dy
        val above = (-stickerY).coerceAtLeast(0)
        val below = (stickerY + stickerSize - bubble.height).coerceAtLeast(0)
        layout(constraints.constrainWidth(bubble.width), constraints.constrainHeight(bubble.height + above + below)) {
            bubble.place(0, above)
            // Only actual vertical overflow contributes to message spacing.
            sticker.place(stickerX, stickerY + above)
        }
    }
}
