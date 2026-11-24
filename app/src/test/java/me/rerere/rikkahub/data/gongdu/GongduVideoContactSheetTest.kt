package me.rerere.rikkahub.data.gongdu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GongduVideoContactSheetTest {
    @Test fun `portrait frames fill their cells instead of shrinking into landscape tiles`() {
        val layout = gongduContactSheetLayout(6, 360, 720)
        val rect = layout.frameRect(0, 360, 720)
        assertEquals(0.5f, rect.width() / rect.height(), 0.001f)
        assertTrue(rect.width() * rect.height() / (layout.tileWidth * layout.tileHeight) > .8f)
        assertTrue(layout.width.toFloat() / layout.height in .7f..1.5f)
    }

    @Test fun `every frame count stays bounded for portrait landscape and extreme ratios`() {
        for ((width, height) in listOf(360 to 720, 1920 to 1080, 100 to 3000, 3000 to 100, 720 to 720)) {
            for (count in 1..6) {
                val layout = gongduContactSheetLayout(count, width, height)
                assertTrue(layout.width in 1..2304 && layout.height in 1..2304)
                for (index in 0 until count) {
                    val rect = layout.frameRect(index, width, height)
                    assertTrue(rect.width() > 0 && rect.height() > 0)
                    assertEquals(width.toFloat() / height, rect.width() / rect.height(), .005f)
                    assertTrue(rect.left >= 0 && rect.right <= layout.width + .01f)
                    assertTrue(rect.top >= 0 && rect.bottom <= layout.height)
                    assertTrue(rect.bottom <= (index / layout.columns + 1) * layout.tileHeight - layout.labelHeight)
                }
            }
        }
    }

    @Test fun `six full size portrait landscape and square frames retain 720 pixel detail`() {
        for ((width, height) in listOf(360 to 720, 720 to 360, 720 to 720)) {
            val layout = gongduContactSheetLayout(6, width, height)
            repeat(6) { index ->
                val rect = layout.frameRect(index, width, height)
                assertEquals(720f, maxOf(rect.width(), rect.height()), .01f)
            }
            assertTrue(layout.width.toLong() * layout.height < 6_000_000)
        }
    }

    @Test fun `grouping retains every timestamp and the remainder for one through 24 frames`() = runBlocking {
        for (count in 1..24) {
            val times = (0 until count).map { 1234L + it * 7553L }
            val decoded = mutableListOf<Long>()
            val pages = mutableListOf<GongduContactSheetPage>()
            val emitted = mutableListOf<Bitmap>()
            var lastFrame: Bitmap? = null
            val extracted = renderGongduVideoContactSheets(times, { time ->
                assertTrue(lastFrame?.isRecycled != false)
                if (decoded.size % 6 == 0) assertTrue(emitted.all { it.isRecycled })
                decoded += time
                Bitmap.createBitmap(36, 72, Bitmap.Config.ARGB_8888).also { lastFrame = it }
            }) { page, bitmap ->
                pages += page
                assertNotNull(bitmap)
                assertFalse(bitmap!!.isRecycled)
                emitted += bitmap
            }
            assertEquals(count, extracted)
            assertEquals(times, decoded)
            assertEquals(times, pages.flatMap { it.times })
            assertEquals((count + 5) / 6, pages.size)
            assertEquals((1..pages.size).toList(), pages.map { it.number })
            assertTrue(pages.all { it.total == pages.size && it.times.size in 1..6 && it.extracted == it.times.size })
            assertTrue(emitted.all { it.isRecycled })
            assertTrue(lastFrame!!.isRecycled)
        }
    }

    @Test fun `failed groups and failed slots never shift later timestamps`() = runBlocking {
        val times = (0 until 13).map(Int::toLong)
        val pages = mutableListOf<GongduContactSheetPage>()
        val extracted = renderGongduVideoContactSheets(times, { time ->
            if (time < 7) null else Bitmap.createBitmap(36, 72, Bitmap.Config.ARGB_8888)
        }) { page, bitmap ->
            pages += page
            when (page.number) {
                1 -> { assertNull(bitmap); assertEquals(0, page.extracted) }
                2 -> {
                    val layout = gongduContactSheetLayout(6, 36, 72)
                    val missing = layout.frameRect(0, 36, 72)
                    assertEquals(Color.rgb(22, 22, 22), bitmap!!.getPixel(missing.centerX().toInt(), missing.centerY().toInt()))
                    assertEquals(5, page.extracted)
                }
                3 -> { assertNotNull(bitmap); assertEquals(listOf(12L), page.times) }
            }
        }
        assertEquals(6, extracted)
        assertEquals(times, pages.flatMap { it.times })
    }

    @Test fun `cancellation while saving releases current canvas and stops further decoding`() = runBlocking {
        val frames = mutableListOf<Bitmap>()
        var emitted: Bitmap? = null
        try {
            renderGongduVideoContactSheets((0 until 24).map(Int::toLong), {
                Bitmap.createBitmap(36, 72, Bitmap.Config.ARGB_8888).also { frames += it }
            }) { _, bitmap ->
                emitted = bitmap
                throw CancellationException("cancel fixture save")
            }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(6, frames.size)
            assertTrue(frames.all { it.isRecycled })
            assertTrue(emitted!!.isRecycled)
        }
    }

    @Test fun `landscape and rotated frames use decoded dimensions without cropping`() {
        for ((width, height) in listOf(720 to 360, 360 to 720)) {
            val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            val paint = Paint()
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            colors.forEachIndexed { index, color ->
                paint.color = color
                val left = (index % 2) * width / 2f
                val top = (index / 2) * height / 2f
                canvas.drawRect(left, top, left + width / 2f, top + height / 2f, paint)
            }
            try {
                GongduVideoContactSheet(width, height, listOf(0, 500, 1000)).use { sheet ->
                    sheet.drawFrame(1, frame)
                    val rect = sheet.layout.frameRect(1, width, height)
                    colors.forEachIndexed { index, color ->
                        val x = (rect.left + rect.width() * (if (index % 2 == 0) .02f else .98f)).toInt()
                        val y = (rect.top + rect.height() * (if (index / 2 == 0) .02f else .98f)).toInt()
                        assertEquals(color, sheet.bitmap.getPixel(x, y))
                    }
                    val missing = sheet.layout.frameRect(0, width, height)
                    assertEquals(Color.rgb(22, 22, 22), sheet.bitmap.getPixel(missing.centerX().toInt(), missing.centerY().toInt()))
                }
            } finally { frame.recycle() }
        }
    }

    @Test fun `timestamp strips remain separate and readable including failed slots`() {
        GongduVideoContactSheet(360, 720, listOf(0, 7553, 166178)).use { sheet ->
            val layout = sheet.layout
            for (index in 0..2) {
                val x = (index % layout.columns) * layout.tileWidth
                val y = (index / layout.columns + 1) * layout.tileHeight
                var white = 0
                for (py in (y - layout.labelHeight.toInt()) until y) {
                    for (px in x until x + layout.tileWidth) {
                        val color = sheet.bitmap.getPixel(px, py)
                        if (Color.red(color) > 180 && Color.green(color) > 180 && Color.blue(color) > 180) white++
                    }
                }
                assertTrue("timestamp absent in slot $index", white > 20)
            }
        }
    }

    @Test fun `closing sheet releases the canvas bitmap`() {
        val sheet = GongduVideoContactSheet(360, 720, listOf(0L))
        sheet.close()
        assertTrue(sheet.bitmap.isRecycled)
    }

    @Test fun `render compact preview with optional screenshot frames`() = runBlocking {
        val fixture = System.getProperty("gongdu.contact.fixture")?.let(BitmapFactory::decodeFile)
        val frames = if (fixture != null) {
            // Crops from the supplied existing sheet, used only for a local visual comparison.
            (0 until 23).map { index ->
                Bitmap.createBitmap(fixture, index % 4 * 320 + 114, index / 4 * 222 + 5, 90, 185)
            }
        } else listOf(Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(180, 130, 150)) })
        try {
            var nextFrame = 0
            val extracted = renderGongduVideoContactSheets((0 until 23).map { it * 166178L / 22 }, {
                frames[nextFrame++ % frames.size].copy(Bitmap.Config.ARGB_8888, false)
            }) { page, bitmap ->
                val output = File("build/reports/gongdu-six-frames/portrait-${page.number}.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(output.length() > 0)
            }
            assertEquals(23, extracted)
        } finally {
            frames.forEach { it.recycle() }
            fixture?.recycle()
        }
    }
}
