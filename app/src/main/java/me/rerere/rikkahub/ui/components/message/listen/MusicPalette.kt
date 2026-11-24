package me.rerere.rikkahub.ui.components.message.listen

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

internal data class MusicPalette(
    val background: Color,
    val wash: Color,
    val accent: Color,
    val ink: Color,
    val muted: Color,
    val glass: Color,
    val glow: Color,
    val secondary: Color,
    val recordTint: Color,
)

/** Bounded, downsampled quantisation. Call only when a decoded cover changes, off the UI thread. */
internal fun extractMusicPalette(bitmap: Bitmap?): MusicPalette {
    if (bitmap == null) return paletteFromHue(192f, .22f)
    val sample = Bitmap.createScaledBitmap(bitmap, 40, 40, true)
    val weights = FloatArray(24)
    val saturation = FloatArray(24)
    val value = FloatArray(24)
    val lightTail = Array(24) { FloatArray(32) }
    val hueOffset = FloatArray(24)
    val hsv = FloatArray(3)
    for (y in 0 until sample.height) for (x in 0 until sample.width) {
        val pixel = sample.getPixel(x, y)
        if (AndroidColor.alpha(pixel) < 128) continue
        AndroidColor.colorToHSV(pixel, hsv)
        val weight = (.08f + hsv[1]) * (.25f + (1f - abs(hsv[2] - .55f)))
        val bucket = (hsv[0] / 15f).toInt().coerceIn(0, 23)
        // Neutral highlights and deep shadows never outweigh a smaller coloured area.
        if (hsv[1] > .08f && hsv[2] > .07f) {
            lightTail[bucket][(hsv[2]*31f).toInt().coerceIn(0,31)] += weight
            value[bucket] += hsv[2] * weight
            hueOffset[bucket] += (hsv[0] - bucket * 15f) * weight
            weights[bucket] += weight
            saturation[bucket] += hsv[1] * weight
        }
    }
    if (sample !== bitmap) sample.recycle()
    val index = weights.indices.maxByOrNull { weights[it] } ?: 12
    if (weights[index] < .01f) return paletteFromHue(200f, .08f)
    // Refraction transmits the lighter part of the dominant hue; ignore isolated white glare.
    var cumulative = 0f
    val upper = lightTail[index].indices.first { bin ->
        cumulative += lightTail[index][bin]
        cumulative >= weights[index] * .70f
    } / 31f
    return paletteFromHue(index * 15f + hueOffset[index] / weights[index],
        (saturation[index] / weights[index]).coerceIn(.08f, .75f), (.46f * value[index] / weights[index] + .54f * upper))
}

private fun hsv(h: Float, s: Float, v: Float) = Color(AndroidColor.HSVToColor(floatArrayOf((h + 360f) % 360f, s, v)))

private fun paletteFromHue(hue: Float, sourceSaturation: Float, sourceValue: Float = .73f): MusicPalette {
    val s = (.12f + sourceSaturation * .22f).coerceIn(.13f, .27f)
    val accent = hsv(hue, (s * 1.75f).coerceAtMost(.48f), .48f)
    return MusicPalette(
        background = hsv(hue, s * .22f, .987f),
        wash = hsv(hue, s * .64f, .96f),
        accent = accent,
        ink = hsv(hue, .16f, .19f),
        muted = hsv(hue, .12f, .40f),
        glass = Color.White.copy(alpha = .50f),
        glow = hsv(hue, (.30f + sourceSaturation * .28f).coerceIn(.32f,.50f), .94f),
        secondary = hsv(hue + 67f, .25f, .97f),
        recordTint = hsv(hue, (.02f + .95f*sourceSaturation).coerceIn(.12f,.48f),
            (.21f + .89f*sourceValue).coerceIn(.62f,.92f)),
    )
}
