package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h500dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListenRecordTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var origin = Offset.Zero
    private var width = 0
    private val discModifier get() = Modifier.size(168.dp).onGloballyPositioned {
        origin = it.positionInWindow(); width = it.size.width
    }

    private fun cover(warm: Boolean): Bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { image ->
        val c = Canvas(image)
        c.drawColor(if (warm) 0xffcf6154.toInt() else 0xff358dba.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (warm) 0xffffce93.toInt() else 0xffacddeb.toInt()
        c.drawCircle(if (warm) 124f else 66f, if (warm) 65f else 77f, 25f, paint)
        paint.color = if (warm) 0xff993f46.toInt() else 0xff287a9c.toInt()
        c.drawPath(Path().apply { moveTo(0f, 149f); quadTo(64f, 96f, 130f, 134f); lineTo(192f, 110f)
            lineTo(192f, 192f); lineTo(0f, 192f); close() }, paint)
    }

    @Test fun realLocalCoverChangeDissolvesLabelAndDyesInnerGlassBeforeOuterGlass() {
        fun album(warm: Boolean) = File(compose.activity.cacheDir, "cover-$warm.png").apply {
            outputStream().use { cover(warm).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.absolutePath
        val blue = album(false); val red = album(true)
        var url by mutableStateOf(blue)
        var playerVisible by mutableStateOf(true)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xfff6f3ef)), Alignment.Center) {
                val display = remember { RecordDisplayState() }
                if (playerVisible) ListenRecord(rememberRecordTransition(display, url, true), display,
                    discModifier, turntable = false)
            }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("专辑封面").fetchSemanticsNodes().size == 1 }
        val before = frame()
        compose.mainClock.autoAdvance = false
        // Queue/search removes the player from composition. Return with the new track:
        // the very first frame must still show the old art, not an empty label.
        compose.runOnIdle { playerVisible = false }; compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { url = red; playerVisible = true }; compose.mainClock.advanceTimeByFrame()
        assertEquals(pixel(before, .10f), pixel(frame(), .10f))
        val frames = mutableListOf(before)
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeByFrame()
            delta(pixel(frame(), .10f), pixel(before, .10f)) > 3
        }
        repeat(52) { frames += frame(); compose.mainClock.advanceTimeBy(32) }
        val after = frame(); frames += after
        System.getProperty("listen.record.frames")?.let { path ->
            File(path).mkdirs()
            frames.forEachIndexed { index, bitmap ->
                File(path, "frame-%02d.png".format(index)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        fun distance(a: Bitmap, b: Bitmap, r: Float) = delta(pixel(a, r), pixel(b, r))
        // Ring radii are relative to the actual glass mask, not the rectangular image bounds.
        for (r in listOf(.64f, .94f)) assertTrue("distinct endpoint tints at $r", distance(before, after, r) > 12)
        val wave = frames.filter {
            distance(it, before, .64f) > 10 && distance(it, before, .94f) <= 3
        }
        assertTrue("inner tint visibly leads the rim for at least 5 successive 32ms samples", wave.size >= 5)
        assertTrue("outer edge still changing after 800ms", distance(frames[26], after, .94f) > 3)
        assertTrue("label has an intermediate colour", frames.any {
            distance(it, before, .10f) > 12 && distance(it, after, .10f) > 12
        })
        // Outside the physical rim stays transparent throughout.
        assertEquals(before.getPixel(2, 2), wave.first().getPixel(2, 2))
    }

    @Test fun staleLoadCannotOverwriteNewestCoverAndBlankOrFailureClearsLabel() {
        val a = cover(false); val c = cover(true)
        val slow = CompletableDeferred<Bitmap?>()
        val calls = mutableListOf<String>()
        val load: suspend (Context, String) -> Bitmap? = { _, url ->
            calls += url
            when (url) { "A" -> a; "B" -> slow.await(); "C" -> c; else -> null }
        }
        var url by mutableStateOf("A")
        var tick by mutableStateOf(0)
        compose.setContent {
            val display = remember { RecordDisplayState() }
            ListenRecord(rememberRecordTransition(display, url, true, load), display,
                discModifier.padding(if (tick == 0) 0.dp else 1.dp), turntable = false)
        }
        fun hasLabel() = compose.onAllNodesWithContentDescription("专辑封面").fetchSemanticsNodes().isNotEmpty()
        compose.waitUntil(10000) { hasLabel() }
        compose.runOnIdle { tick++ }
        compose.runOnIdle { url = "B" }; compose.waitForIdle()
        assertTrue("keep A while B loads", hasLabel())
        compose.runOnIdle { url = "C" }
        compose.waitUntil(10000) { val p = pixel(frame(), .10f); Color.red(p) > Color.blue(p) + 50 }
        compose.runOnIdle { slow.complete(a) }; compose.waitForIdle()
        val p = pixel(frame(), .10f)
        assertTrue("late B must not restore A", Color.red(p) > Color.blue(p) + 50)
        compose.runOnIdle { url = "error" }; compose.waitUntil(10000) { !hasLabel() }
        compose.runOnIdle { url = "A" }; compose.waitUntil(10000) { hasLabel() }
        compose.runOnIdle { url = "" }; compose.waitUntil(10000) { !hasLabel() }
        assertEquals(listOf("A", "B", "C", "error", "A"), calls)
    }

    private fun frame(): Bitmap = compose.runOnIdle {
        val decor = compose.activity.window.decorView
        val pixels = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(pixels); canvas.translate(-origin.x, -origin.y); decor.draw(canvas)
        pixels
    }
    private fun pixel(b: Bitmap, radius: Float) = b.getPixel((b.width * (.5f + .465f * radius)).roundToInt(), b.height / 2)
    private fun delta(a: Int, b: Int) = abs(Color.red(a)-Color.red(b)) + abs(Color.green(a)-Color.green(b)) + abs(Color.blue(a)-Color.blue(b))
}
