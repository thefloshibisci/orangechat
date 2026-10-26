package me.rerere.rikkahub.ui.components.charts

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import java.io.File
import kotlin.math.ceil

/** Robolectric does not dispatch the device frame callback awaited by PixelCopy. */
internal fun captureChartRegion(activity: Activity, boundsInWindow: Rect, name: String): Bitmap {
    val decor = activity.window.decorView
    check(boundsInWindow.width > 0 && boundsInWindow.height > 0)
    val bitmap = Bitmap.createBitmap(ceil(boundsInWindow.width).toInt(), ceil(boundsInWindow.height).toInt(),
        Bitmap.Config.ARGB_8888)
    val decorPosition = IntArray(2).also(decor::getLocationInWindow)
    val canvas = Canvas(bitmap)
    canvas.translate(decorPosition[0] - boundsInWindow.left, decorPosition[1] - boundsInWindow.top)
    // Crop the actual chart node. Its mode-toggle icon is outside this bitmap.
    decor.draw(canvas)
    val directory = File("../.codex/charts-ssh-ui-review").apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return bitmap
}

internal fun Bitmap.redChartPixelCount(): Int {
    var count = 0
    for (y in 0 until height step 2) for (x in 0 until width step 2) {
        val pixel = getPixel(x, y)
        if (Color.red(pixel) / 255f > 0.5f && Color.green(pixel) / 255f < 0.4f && Color.blue(pixel) / 255f < 0.4f) count++
    }
    return count
}
