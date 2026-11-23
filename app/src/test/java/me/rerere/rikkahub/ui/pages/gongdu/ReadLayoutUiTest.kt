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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
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
        compose.onNodeWithContentDescription("下一页").performClick()
        compose.waitForIdle()
        try { compose.waitUntil(10_000) { viewport?.pageIndex == 1 && progress.offset > 0 } }
        catch (failure: Throwable) {
            println("NARROW progress=$progress viewport=$viewport changes=$changes")
            println(compose.onNodeWithTag("read-page").printToString())
            snapshot("read-pagination-compact-failure")
            throw failure
        }
        compose.onNodeWithTag("read-paragraph-0").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
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
        compose.onNodeWithContentDescription("下一页").performClick()
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

    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun diagonalCurlShowsDistinctPagesOnBothSidesWhileFingerIsHeld() {
        val previousText = "“今天的雨停了。”她把书往这边推了一点。“这句话，想和你一起读。”".repeat(8)
        val followingText = "窗外又吹来一阵风。我们翻到下一页，读到了另一段故事。灯光照着纸，也照着你的手。".repeat(7)
        val sample = book.copy(chapters = listOf(ReadChapter("雨停以后", listOf(previousText)),
            ReadChapter("下一页的灯", listOf(followingText))))
        var progress by mutableStateOf(ReadProgress())
        var viewport: ReadViewport? = null
        val settings = ReadSettings(mode = "page", pageTurn = "curl")
        compose.setContent { Themed(settings) {
            ReadPage(sample, progress, settings, emptyList(), Modifier.fillMaxSize(), { _, _ -> }, {}, {},
                onNext = { progress = ReadProgress(1, 0, 0) }, onPosition = { progress = it },
                onNavigatePosition = { progress = it }, onViewport = { viewport = it }, showPageButtons = false)
        } }
        compose.waitUntil(10_000) { viewport != null }
        assertEquals("The reference fixture must turn between different chapters", 1, viewport!!.pageCount)
        compose.onNodeWithTag("read-page-number").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回书架").assertIsDisplayed()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("read-turn-stage").performTouchInput {
            down(percentOffset(.96f, .76f))
            moveTo(percentOffset(.51f, .76f), delayMillis = 180)
        }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        val early = compose.onNodeWithTag("read-turn-stage").fetchSemanticsNode().config[ReadTurnFraction]
        assertTrue(early in .16f.. .26f)
        assertEquals(ReadProgress(), progress)
        settledDrawSnapshot("read-curl-reference-held-early")
        compose.onNodeWithTag("read-turn-stage").performTouchInput {
            moveTo(percentOffset(.21f, .76f), delayMillis = 100)
        }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        val fraction = compose.onNodeWithTag("read-turn-stage").fetchSemanticsNode().config[ReadTurnFraction]
        assertTrue(fraction in .30f.. .40f)
        assertEquals(ReadProgress(), progress)
        assertNull(viewport)
        settledDrawSnapshot("read-curl-reference-held-37")
        compose.onNodeWithTag("read-turn-stage").performTouchInput {
            moveTo(percentOffset(.06f, .76f), delayMillis = 120)
        }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        settledDrawSnapshot("read-curl-reference-held-45")
        compose.onNodeWithTag("read-turn-stage").performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.waitUntil(10_000) { viewport != null }
        assertEquals(ReadProgress(), progress)
    }

    @Test fun pagedChromeKeepsSystemInsetsInsideTheTurningSheet() {
        val settings = ReadSettings(mode = "page", pageTurn = "curl")
        compose.setContent { Themed(settings) {
            ReadPage(book, ReadProgress(), settings, emptyList(), Modifier.fillMaxSize(),
                { _, _ -> }, {}, {}, {}, showPageButtons = false, paperTopInset = 36.dp, paperBottomInset = 28.dp)
        } }
        val stage = compose.onNodeWithTag("read-turn-stage")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("read-page-number").fetchSemanticsNodes().isNotEmpty()
        }
        val sheet = stage.getUnclippedBoundsInRoot()
        val fullPage = compose.onNodeWithTag("read-page").getUnclippedBoundsInRoot()
        assertEquals(fullPage.top, sheet.top)
        assertEquals(fullPage.bottom, sheet.bottom)
        val heading = compose.onNodeWithContentDescription("返回书架").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("read-page-number").getUnclippedBoundsInRoot()
        assertTrue(heading.top >= sheet.top + 36.dp)
        assertTrue(footer.bottom <= sheet.bottom - 28.dp)
    }

    @Test fun partiallyWrappedRowsHideTheNextPageUntilTheOuterEdge() {
        val pages = listOf(ReadLayoutPage(listOf(ReadPageFragment(0, 0, 1))),
            ReadLayoutPage(listOf(ReadPageFragment(0, 1, 2))))
        val frame = ReadTurnFrame(0, book.chapters.first(), pages, 0, 1, 320.dp, 100.dp,
            TextStyle.Default, 0, 600)
        compose.setContent {
            ReadTurnStage(frame, "curl", true, {}, {}, {}, neighbor = { frame.copy(start = 1) }) { page, _ ->
                Box(Modifier.fillMaxSize().drawBehind {
                    if (page.start == 1) drawRect(Color.Green) else {
                        drawRect(Color.White)
                        repeat(10) { line ->
                            drawRect(Color.Red, Offset(0f, size.height * (line + .3f) / 10f),
                                Size(size.width, size.height * .02f))
                        }
                    }
                })
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val stage = compose.onNodeWithTag("read-turn-stage")
        stage.performTouchInput {
            down(percentOffset(.96f, .76f))
            moveTo(percentOffset(.50f, .76f), delayMillis = 180)
        }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        assertTrue(stage.fetchSemanticsNode().config[ReadTurnFraction] in .16f.. .26f)
        fun drawFrame(): Bitmap {
            val content = compose.activity.findViewById<android.view.View>(android.R.id.content)
            return Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888).also {
                content.draw(Canvas(it))
            }
        }
        compose.runOnIdle { drawFrame().recycle() }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            val bitmap = drawFrame()
            try {
                for (row in 1 until 95) {
                    val y = bitmap.height * row / 100
                    var revealedPixels = 0
                    for (x in 0 until bitmap.width step 2) {
                        val pixel = bitmap.getPixel(x, y)
                        val red = android.graphics.Color.red(pixel)
                        val green = android.graphics.Color.green(pixel)
                        if (red > 200 && green < 50) {
                            assertTrue("Front ink moved off its baseline at ($x,$y)", row % 10 in 3..5)
                        }
                        val nextPage = green > 220 && red < 45
                        if (nextPage) revealedPixels++ else {
                            assertTrue("Next page visible inside sheet at ($x,$y)", revealedPixels <= 2)
                            revealedPixels = 0
                        }
                    }
                }
                assertEquals("Page faces must both be visible", android.graphics.Color.GREEN,
                    bitmap.getPixel(bitmap.width - 4, bitmap.height / 2))
                assertEquals("Front ink retains its original row", android.graphics.Color.RED,
                    bitmap.getPixel(bitmap.width / 8, bitmap.height * 34 / 100))
            } finally {
                bitmap.recycle()
            }
        }
        stage.performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
    }

    @Test fun curlCrossesChaptersOnceAndReturnsToLastPageWithoutSavingPreview() {
        val chapters = book.copy(chapters = book.chapters + ReadChapter("第二章 灯下", listOf("下一章的第一句话。")))
        var progress by mutableStateOf(ReadProgress(1, 0, 0))
        var viewport: ReadViewport? = null
        var previousCalls = 0
        var nextCalls = 0
        val settings = ReadSettings(mode = "page", pageTurn = "curl")
        compose.setContent { Themed(settings) {
            ReadPage(chapters, progress, settings, emptyList(), Modifier.fillMaxSize(), { _, _ -> }, {},
                onPrevious = { previousCalls++; progress = ReadProgress(0, 0, source.length) },
                onNext = { nextCalls++; progress = ReadProgress(1, 0, 0) },
                onPosition = { progress = it }, onNavigatePosition = { progress = it }, onViewport = { viewport = it })
        } }
        compose.waitUntil(10_000) { viewport?.chapter == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("read-turn-stage").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.waitUntil(10_000) { viewport?.let { it.chapter == 0 && it.pageIndex == it.pageCount - 1 } == true }
        assertEquals(1, previousCalls)
        assertEquals(0, nextCalls)
        val lastPosition = progress
        assertTrue(lastPosition.offset > 0)

        // On the boundary, a forward preview must stay provisional until the finger releases.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("read-turn-stage").performTouchInput {
            down(center)
            moveTo(center - Offset(width * .36f, 0f), delayMillis = 250)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("read-turn-stage").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "dragging:curl"))
        assertEquals(lastPosition, progress)
        assertEquals(0, nextCalls)
        assertNull(viewport)
        snapshot("read-turn-curl-forward-held")
        compose.onNodeWithTag("read-turn-stage").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.waitUntil(10_000) { viewport?.chapter == 1 }
        assertEquals(1, nextCalls)
        assertEquals(ReadProgress(1, 0, 0), progress)
        compose.onNodeWithTag("read-turn-stage").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "settled:curl"))
    }

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
        compose.onNodeWithContentDescription("下一页").assertIsEnabled()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("下一页").performClick()
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
        if (effect == "curl") {
            // Hold a real pointer down: the paper must move before release, without publishing a new position.
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("read-turn-stage").performTouchInput {
                down(center)
                moveTo(center + Offset(width * .20f, 0f), delayMillis = 180)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("read-turn-stage").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "dragging:curl"))
            val firstFraction = compose.onNodeWithTag("read-turn-stage").fetchSemanticsNode().config[ReadTurnFraction]
            assertTrue(firstFraction > 0f)
            assertEquals(firstEnd, progress.offset)
            assertNull(viewport)
            compose.onNodeWithTag("read-turn-stage").performTouchInput {
                moveTo(center + Offset(width * .34f, 0f), delayMillis = 180)
            }
            compose.mainClock.advanceTimeByFrame()
            assertTrue(compose.onNodeWithTag("read-turn-stage").fetchSemanticsNode().config[ReadTurnFraction] > firstFraction)
            snapshot("read-turn-curl-held")
            compose.onNodeWithTag("read-turn-stage").performTouchInput {
                moveTo(center + Offset(width * .04f, 0f), delayMillis = 200)
                up()
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.waitUntil(10_000) { viewport?.pageIndex == 1 }
            assertEquals(firstEnd, progress.offset)
            // Reversing past the starting point must not commit the initial direction.
            compose.onNodeWithTag("read-turn-stage").performTouchInput {
                down(center)
                moveTo(center + Offset(width * .32f, 0f), delayMillis = 180)
                moveTo(center - Offset(width * .35f, 0f), delayMillis = 240)
                up()
            }
            compose.waitForIdle()
            assertEquals(firstEnd, progress.offset)
        }
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

    // A GraphicsLayer records into RenderNodes. Flush a draw, then the next frame, before
    // exporting a held gesture so the bitmap and the semantic progress describe the same frame.
    private fun settledDrawSnapshot(name: String) {
        repeat(2) {
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val scratch = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(scratch))
                scratch.recycle()
            }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
        }
        snapshot(name)
    }
}
