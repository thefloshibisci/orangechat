package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.reading.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w320dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val source = "窗外的蓝色慢慢落在书页上。我们读到同一句话，也留下不一样的想法。🫧".repeat(45)
    private val book = ReadBook("b".repeat(64), "合成排版测试", "", "txt", listOf(ReadChapter("第一章 蓝笺", listOf(source))))

    @Test fun measuredPagesRetainAllSourceCoordinatesAndFitAvailableHeight() {
        var result: List<ReadLayoutPage>? = null
        var heights: List<Int> = emptyList()
        compose.setContent {
            val measurer = rememberTextMeasurer()
            val style = TextStyle(fontSize = 18.sp, lineHeight = 30.sp, letterSpacing = .5.sp,
                fontFamily = GongduBook, platformStyle = PlatformTextStyle(includeFontPadding = false))
            LaunchedEffect(Unit) {
                val pages = withContext(Dispatchers.Default) { paginateReadChapter(book.chapters[0], measurer,
                    style, 600, 540, 36, 400) }
                heights = pages.map { page -> page.fragments.sumOf {
                    measurer.measure(source.substring(it.start, it.end).trimEnd('\n', '\r'), style,
                        constraints = Constraints(maxWidth = 600)).size.height
                } + 36 * (page.fragments.size - 1) }
                result = pages
            }
        }
        compose.waitUntil(10_000) { result != null }
        val pages = checkNotNull(result)
        assertTrue(pages.size > 3)
        assertTrue(heights.all { it <= 540 })
        assertEquals(source, pages.flatMap { it.fragments }.joinToString("") { source.substring(it.start, it.end) })
        pages.flatMap { it.fragments }.forEach {
            assertFalse(it.start > 0 && source[it.start].isLowSurrogate() && source[it.start - 1].isHighSurrogate())
            assertFalse(it.end < source.length && source[it.end].isLowSurrogate() && source[it.end - 1].isHighSurrogate())
        }
    }

    @Test fun narrowSpreadUsesSinglePageAndNextPageSharesPreciseOriginalOffsets() {
        var progress by mutableStateOf(ReadProgress())
        var viewport: ReadViewport? = null
        var selected: ReadParagraphSelection? = null
        val changes = mutableListOf<String>()
        val settings = ReadSettings(mode = "spread", readingRuler = true)
        compose.setContent { Themed(settings) {
            ReadPage(book, progress, settings, emptyList(), Modifier.fillMaxSize(), { _, _ -> }, { selected = it }, {}, {},
                onPosition = { changes += "head:$it"; progress = it },
                onNavigatePosition = { changes += "navigate:$it"; progress = it }, onViewport = { viewport = it })
        } }
        compose.waitUntil(10_000) { viewport != null }
        compose.onNodeWithTag("read-single-page").assertIsDisplayed()
        compose.onNodeWithTag("read-spread").assertDoesNotExist()
        compose.onNodeWithTag("read-ruler").assertIsDisplayed()
        compose.onNodeWithText("下一页").performClick()
        compose.waitForIdle()
        try { compose.waitUntil(10_000) { viewport?.pageIndex == 1 && progress.offset > 0 } }
        catch (failure: Throwable) {
            println("NARROW progress=$progress viewport=$viewport changes=$changes")
            println(compose.onNodeWithTag("read-page").printToString())
            snapshot("read-pagination-compact-failure")
            throw failure
        }
        compose.onNodeWithTag("read-paragraph-0").performClick()
        assertEquals(source, selected?.text)
        assertEquals(progress.offset, selected?.start)
        assertEquals(viewport?.fragments?.first()?.end, selected?.end)
        snapshot("read-pagination-compact-day")
    }

    @Test @Config(qualifiers = "w900dp-h700dp-xxhdpi")
    fun wideSpreadShowsTwoMeasuredPagesAndMovesForwardByTwo() {
        var progress by mutableStateOf(ReadProgress())
        var viewport: ReadViewport? = null
        val changes = mutableListOf<String>()
        val settings = ReadSettings(theme = "bluecard-night", mode = "spread")
        compose.setContent { Themed(settings) {
            ReadPage(book, progress, settings, emptyList(), Modifier.fillMaxSize(), { _, _ -> }, {}, {}, {},
                onPosition = { changes += "head:$it"; progress = it },
                onNavigatePosition = { changes += "navigate:$it"; progress = it }, onViewport = { viewport = it })
        } }
        compose.waitUntil(10_000) { viewport != null }
        compose.onNodeWithTag("read-spread").assertIsDisplayed()
        compose.onNodeWithTag("read-paper-0").assertIsDisplayed()
        compose.onNodeWithTag("read-paper-1").assertIsDisplayed()
        val firstEnd = viewport!!.fragments.last().end
        compose.onNodeWithText("下一页").performClick()
        compose.waitForIdle()
        try { compose.waitUntil(10_000) { viewport?.pageIndex == 2 } }
        catch (failure: Throwable) {
            println("WIDE progress=$progress viewport=$viewport changes=$changes")
            println(compose.onNodeWithTag("read-page").printToString())
            snapshot("read-pagination-wide-failure")
            throw failure
        }
        assertEquals(firstEnd, progress.offset)
        snapshot("read-pagination-wide-night")
    }

    @Test fun fragmentSelectionSavesOffsetsWithinOriginalParagraph() {
        val selection = ReadParagraphSelection(book.id, 0, 0, "第一章", source, 12, 35)
        var saved: Pair<Int, Int>? = null
        compose.setContent { Themed(ReadSettings()) { ReadAnnotationEditor(selection, false,
            { start, end, _ -> saved = start to end }, { _, _ -> }) } }
        compose.onNodeWithText("保存划线与批注").performScrollTo().performClick()
        assertEquals(12 to 35, saved)
    }

    @Test fun localEmbeddedCoverAndImageDecodeWithoutRemoteRequests() {
        val png = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        val image = ReadImage("c".repeat(64), "image/png", Base64.encodeToString(png, Base64.NO_WRAP), "合成测试插图")
        val illustrated = book.copy(chapters = listOf(ReadChapter("插图测试", listOf("这是一幅合成插图", "插图后面的正文。"), mapOf(0 to image.id))),
            images = mapOf(image.id to image), coverImageId = image.id)
        compose.setContent { Themed(ReadSettings()) {
            ReadPage(illustrated, ReadProgress(), ReadSettings(), emptyList(), Modifier.fillMaxSize(), { _, _ -> }, {}, {}, {})
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("read-inline-image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("合成测试插图").assertIsDisplayed()
        compose.onNodeWithText("插图后面的正文。").assertIsDisplayed()
        snapshot("read-inline-local-image")
    }

    @Composable private fun Themed(settings: ReadSettings, content: @Composable () -> Unit) {
        MaterialTheme { ReadTheme(settings) { Surface { Box(Modifier.fillMaxSize()) { content() } } } }
    }

    @Test fun slideTurnAnimatesAndSwipeReturnsToOriginalPage() = verifyTurn("slide")
    @Test fun coverTurnAnimatesAndSwipeReturnsToOriginalPage() = verifyTurn("cover")
    @Test fun curlTurnAnimatesAndSwipeReturnsToOriginalPage() = verifyTurn("curl")
    @Test fun verticalTurnAnimatesAndSwipeReturnsToOriginalPage() = verifyTurn("vertical")

    private fun verifyTurn(effect: String) {
        var progress by mutableStateOf(ReadProgress())
        var settings by mutableStateOf(ReadSettings(mode = "page", pageTurn = effect))
        var viewport: ReadViewport? = null
        var selected: ReadParagraphSelection? = null
        compose.setContent { Themed(settings) {
            ReadPage(book, progress, settings, emptyList(), Modifier.fillMaxSize(), { _, _ -> }, { selected = it }, {}, {},
                onPosition = { progress = it }, onNavigatePosition = { progress = it }, onViewport = { viewport = it })
        } }
        compose.waitUntil(10_000) { viewport != null }
        val firstEnd = viewport!!.fragments.last().end
        compose.waitForIdle()
        compose.onNodeWithText("下一页").assertIsEnabled()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("下一页").performClick()
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("read-turn-stage").fetchSemanticsNodes().any {
                it.config[ReadTurnFraction] > 0f && it.config[ReadTurnFraction] < 1f
            }
        }
        compose.mainClock.advanceTimeBy(144)
        compose.onNodeWithTag("read-turn-stage").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "turning:$effect"))
        assertNull(viewport)
        snapshot("read-turn-$effect-mid")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.waitUntil(10_000) { viewport?.pageIndex == 1 }
        assertEquals(firstEnd, progress.offset)
        // A short drag cancels without changing the saved location or opening a note.
        compose.onNodeWithTag("read-turn-stage").performTouchInput {
            val shortDrag = 32f * compose.activity.resources.displayMetrics.density
            if (effect == "vertical") swipe(center, center + Offset(0f, shortDrag))
            else swipe(center, center + Offset(shortDrag, 0f))
        }
        compose.waitForIdle()
        assertEquals(firstEnd, progress.offset)
        assertNull(selected)
        compose.onNodeWithTag("read-turn-stage").performTouchInput {
            down(center)
            moveTo(if (effect == "vertical") center + Offset(0f, 200f) else center + Offset(200f, 0f))
            cancel()
        }
        compose.waitForIdle()
        assertEquals(firstEnd, progress.offset)
        compose.onNodeWithTag("read-turn-stage").performTouchInput {
            if (effect == "vertical") swipeDown() else swipeRight()
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) { viewport?.pageIndex == 0 }
        assertEquals(0, progress.offset)
        compose.runOnIdle { settings = settings.copy(theme = "bluecard-night", readingRuler = true) }
        compose.waitForIdle()
        compose.waitUntil(10_000) { viewport?.pageIndex == 0 }
        compose.onNodeWithTag("read-turn-stage").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "settled:$effect"))
        snapshot("read-turn-$effect-night-settled")
    }

    private fun snapshot(name: String) {
        val output = File("build/reports/tests/gongdu-read-screenshots/$name.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
