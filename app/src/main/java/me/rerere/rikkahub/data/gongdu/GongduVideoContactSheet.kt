package me.rerere.rikkahub.data.gongdu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

internal const val GONGDU_FRAMES_PER_SHEET = 6
internal const val GONGDU_FRAME_MAX_EDGE = 720
internal const val GONGDU_SHEET_MAX_EDGE = 2304

internal data class GongduContactSheetLayout(
    val columns: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val inset: Float,
    val labelHeight: Float,
) {
    val width: Int get() = columns * tileWidth
    val height: Int get() = rows * tileHeight

    fun frameRect(index: Int, frameWidth: Int, frameHeight: Int): RectF {
        val x = (index % columns) * tileWidth.toFloat()
        val y = (index / columns) * tileHeight.toFloat()
        val availableWidth = tileWidth - inset * 2
        val availableHeight = tileHeight - inset * 2 - labelHeight
        val scale = minOf(availableWidth / frameWidth, availableHeight / frameHeight)
        val width = frameWidth * scale
        val height = frameHeight * scale
        val left = x + (tileWidth - width) / 2
        val top = y + inset + (availableHeight - height) / 2
        return RectF(left, top, left + width, top + height)
    }
}

/** Dimensions come from decoded pixels, so rotation metadata cannot swap the aspect ratio twice. */
internal fun gongduContactSheetLayout(count: Int, frameWidth: Int, frameHeight: Int): GongduContactSheetLayout {
    require(count in 1..GONGDU_FRAMES_PER_SHEET && frameWidth > 0 && frameHeight > 0)
    val scale = GONGDU_FRAME_MAX_EDGE.toFloat() / maxOf(frameWidth, frameHeight)
    val tileWidth = maxOf(128, (frameWidth * scale).roundToInt()) + 8
    val tileHeight = maxOf(1, (frameHeight * scale).roundToInt()) + 8 + 28
    val columns = (1..count).minBy { columns ->
        val rows = (count + columns - 1) / columns
        val aspect = columns * tileWidth.toDouble() / (rows * tileHeight)
        val unused = (columns * rows - count).toDouble() / (columns * rows)
        abs(ln(aspect)) + unused * 1.5
    }
    val rows = (count + columns - 1) / columns
    val boardScale = minOf(1f, GONGDU_SHEET_MAX_EDGE.toFloat() / maxOf(columns * tileWidth, rows * tileHeight))
    return GongduContactSheetLayout(
        columns, rows, (tileWidth * boardScale).toInt(), (tileHeight * boardScale).toInt(),
        inset = 4f * boardScale, labelHeight = 28f * boardScale,
    )
}

/** One bounded canvas plus the caller's current frame; missing frames keep their original slots. */
internal class GongduVideoContactSheet(frameWidth: Int, frameHeight: Int, times: List<Long>) : Closeable {
    val layout = gongduContactSheetLayout(times.size, frameWidth, frameHeight)
    val bitmap: Bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        canvas.drawColor(Color.rgb(22, 22, 22))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = layout.labelHeight * 20f / 28f
        }
        times.forEachIndexed { index, time ->
            val seconds = String.format(Locale.ROOT, "%.3f", time / 1000.0).trimEnd('0').trimEnd('.')
            canvas.drawText(
                "$seconds s",
                (index % layout.columns) * layout.tileWidth + layout.inset,
                (index / layout.columns + 1) * layout.tileHeight - layout.inset * 2,
                paint,
            )
        }
    }

    fun drawFrame(index: Int, frame: Bitmap) {
        canvas.drawBitmap(frame, null, layout.frameRect(index, frame.width, frame.height), framePaint)
    }

    override fun close() = bitmap.recycle()
}

internal data class GongduContactSheetPage(
    val number: Int,
    val total: Int,
    val times: List<Long>,
    val extracted: Int,
)

/** Emit and release each group before decoding the next; even failed groups keep their time range. */
internal suspend fun renderGongduVideoContactSheets(
    times: List<Long>,
    frameAt: suspend (Long) -> Bitmap?,
    emit: suspend (GongduContactSheetPage, Bitmap?) -> Unit,
): Int {
    require(times.size in 1..24)
    val groups = times.chunked(GONGDU_FRAMES_PER_SHEET)
    var totalExtracted = 0
    for ((groupIndex, groupTimes) in groups.withIndex()) {
        var sheet: GongduVideoContactSheet? = null
        var extracted = 0
        try {
            for ((index, time) in groupTimes.withIndex()) {
                currentCoroutineContext().ensureActive()
                val frame = frameAt(time) ?: continue
                try {
                    val currentSheet = sheet ?: GongduVideoContactSheet(frame.width, frame.height, groupTimes)
                        .also { sheet = it }
                    currentSheet.drawFrame(index, frame)
                    extracted++
                } finally { frame.recycle() }
            }
            currentCoroutineContext().ensureActive()
            emit(GongduContactSheetPage(groupIndex + 1, groups.size, groupTimes, extracted), sheet?.bitmap)
            totalExtracted += extracted
        } finally { sheet?.close() }
    }
    return totalExtracted
}
