package me.rerere.ai.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Rect
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
    const val MAX_INPUT_BYTES = 12_000_000
    private const val MAX_SOURCE_PIXELS = 6_000_000L
    private const val MAX_FRAMES = 9
    private const val CELL = 240
    private const val GAP = 4
    private const val FRAME_STEP_MS = 100

    class Sheet(val png: ByteArray, val frames: Int, val durationMs: Int)

    fun render(gif: ByteArray): Sheet {
        require(gif.size in 13..MAX_INPUT_BYTES) { "Invalid or oversized GIF" }
        val header = gif.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        require(header == "GIF87a" || header == "GIF89a") { "Invalid GIF header" }
        fun word(offset: Int) = (gif[offset].toInt() and 255) or ((gif[offset + 1].toInt() and 255) shl 8)
        val width = word(6)
        val height = word(8)
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= MAX_SOURCE_PIXELS) {
            "GIF dimensions exceed the decode budget"
        }
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
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(gif, 0, gif.size, options)
        options.inSampleSize = 1
        while (max(options.outWidth, options.outHeight) / options.inSampleSize > CELL * 2) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        val decoded = BitmapFactory.decodeByteArray(gif, 0, gif.size, options) ?: error("Unsupported image format")
        try {
            val scale = min(1f, CELL.toFloat() / max(decoded.width, decoded.height))
            val flat = Bitmap.createBitmap(max(1, (decoded.width * scale).roundToInt()),
                max(1, (decoded.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
            try {
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(decoded, null, Rect(0, 0, flat.width, flat.height), null)
                }
                return Sheet(encode(flat), 1, 0)
            } finally {
                flat.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "GIF sheet encoding failed" }
        out.toByteArray()
    }
}
