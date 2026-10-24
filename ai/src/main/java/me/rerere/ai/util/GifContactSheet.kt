package me.rerere.ai.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns an animated GIF into one PNG still that vision models can read.
 * Shared by chat image attachments ([compressAndEncode]) and plugin image results.
 *
 * Providers only accept a single still image, so an animated GIF would otherwise be
 * reduced to its first frame (or rejected). Instead, up to [MAX_FRAMES] frames are sampled
 * evenly over the animation and laid out left-to-right, top-to-bottom in time order on a
 * white background (GIF transparency would otherwise render black in most viewers).
 */
object GifContactSheet {
    private const val MAX_FRAMES = 9
    private const val CELL = 240
    private const val GAP = 4
    private const val FRAME_STEP_MS = 100

    class Sheet(val png: ByteArray, val frames: Int, val durationMs: Int)

    fun render(gif: ByteArray): Sheet {
        @Suppress("DEPRECATION")
        val movie = Movie.decodeByteArray(gif, 0, gif.size)
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) return firstFrame(gif)

        val duration = movie.duration()
        // Movie does not expose a frame count; sample by time instead.
        val frames = if (duration <= 0) 1 else min(MAX_FRAMES, max(1, ceil(duration / FRAME_STEP_MS.toDouble()).toInt()))
        if (frames == 1) return firstFrame(gif)

        val w = movie.width()
        val h = movie.height()
        val scale = min(1f, CELL.toFloat() / max(w, h))
        val cw = max(1, (w * scale).roundToInt())
        val ch = max(1, (h * scale).roundToInt())
        val cols = ceil(sqrt(frames.toDouble())).toInt()
        val rows = ceil(frames / cols.toDouble()).toInt()
        val sheet = Bitmap.createBitmap(cols * cw + (cols - 1) * GAP, rows * ch + (rows - 1) * GAP, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(200, 200, 200))
            for (i in 0 until frames) {
                val left = (i % cols) * (cw + GAP).toFloat()
                val top = (i / cols) * (ch + GAP).toFloat()
                canvas.save()
                canvas.clipRect(left, top, left + cw, top + ch)
                canvas.drawColor(Color.WHITE)
                canvas.translate(left, top)
                canvas.scale(scale, scale)
                // Sample from the first to the last moment of the animation.
                movie.setTime(((duration - 1).toLong() * i / (frames - 1)).toInt())
                movie.draw(canvas, 0f, 0f)
                canvas.restore()
            }
            return Sheet(encode(sheet), frames, duration)
        } finally {
            sheet.recycle()
        }
    }

    private fun firstFrame(gif: ByteArray): Sheet {
        val decoded = BitmapFactory.decodeByteArray(gif, 0, gif.size) ?: error("Unsupported image format")
        val flat = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(decoded, 0f, 0f, null)
            }
            return Sheet(encode(flat), 1, 0)
        } finally {
            decoded.recycle()
            flat.recycle()
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}
