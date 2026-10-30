package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Scale
import java.io.File

/**
 * The drawer background image, shared so list rows can show the part of it that sits behind them.
 * Rows draw a center-cropped slice aligned to the drawer, so the picture looks fixed while the list scrolls.
 */
@Stable
class DrawerBackdrop internal constructor(val painter: Painter) {
    internal var root: LayoutCoordinates? = null
    internal var rootSize by mutableStateOf(Size.Zero)
}

val LocalDrawerBackdrop = compositionLocalOf<DrawerBackdrop?> { null }

/** Background image strength inside a row; the selected row adds its own tint on top. */
private const val BackdropAlpha = 0.45f

@Composable
fun rememberDrawerBackdrop(path: String): DrawerBackdrop? {
    if (path.isEmpty()) return null
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // Request at drawer size up front; otherwise Coil sizes it to the first (tiny) row that draws it.
    val request = remember(context, path, configuration.screenHeightDp) {
        with(density) {
            ImageRequest.Builder(context)
                .data(File(path))
                .size(300.dp.roundToPx(), configuration.screenHeightDp.dp.roundToPx())
                .scale(Scale.FILL)
                .build()
        }
    }
    val painter = rememberAsyncImagePainter(request)
    return remember(painter) { DrawerBackdrop(painter) }
}

/** Marks the area the backdrop image covers. */
fun Modifier.drawerBackdropRoot(backdrop: DrawerBackdrop?): Modifier =
    if (backdrop == null) this else onGloballyPositioned {
        backdrop.root = it
        backdrop.rootSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
    }

/** Draws the slice of the drawer background behind this element. Clip the element before applying this. */
fun Modifier.drawerBackdrop(): Modifier = composed {
    val backdrop = LocalDrawerBackdrop.current ?: return@composed this
    var offset by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { coordinates ->
            backdrop.root?.takeIf { it.isAttached }?.let { offset = it.localPositionOf(coordinates, Offset.Zero) }
        }
        .drawBehind {
            val root = backdrop.rootSize
            val image = backdrop.painter.intrinsicSize
            if (root.isEmpty() || image.isUnspecified() || image.isEmpty()) return@drawBehind
            val scale = maxOf(root.width / image.width, root.height / image.height)
            val drawn = Size(image.width * scale, image.height * scale)
            translate(
                left = (root.width - drawn.width) / 2f - offset.x,
                top = (root.height - drawn.height) / 2f - offset.y,
            ) {
                with(backdrop.painter) { draw(drawn, alpha = BackdropAlpha) }
            }
        }
}

private fun Size.isUnspecified() = this == Size.Unspecified
