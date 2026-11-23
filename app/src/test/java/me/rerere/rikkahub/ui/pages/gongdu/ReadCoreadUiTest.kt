package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.reading.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w375dp-h812dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadCoreadUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val book = ReadBook("c".repeat(64), "雨停以后", "测试作者", "txt", listOf(ReadChapter("第一章 窗边",
        listOf("雨停以后，窗外的树叶还在滴水。我们在同一页停下来，各自读着，也听见对方翻页的声音。".repeat(30)))))

    @Test fun tapTurnsSwipeStillWorksAndLongPressNeverTurns() {
        var progress by mutableStateOf(ReadProgress())
        var viewport: ReadViewport? = null
        var selected: ReadParagraphSelection? = null
        var controls = 0
        compose.setContent { Theme {
            ReadPage(book, progress, ReadSettings(), emptyList(), Modifier.fillMaxSize(), { _, _ -> }, { selected = it }, {}, {},
                onPosition = { progress = it }, onNavigatePosition = { progress = it }, onViewport = { viewport = it },
                onToggleControls = { controls++ })
        } }
        compose.waitUntil(10_000) { viewport != null }
        compose.onNodeWithTag("read-paragraph-0").performTouchInput { click(percentOffset(.9f, .4f)) }
        compose.waitForIdle()
        try { compose.waitUntil(10_000) { viewport?.pageIndex == 1 } }
        catch (failure: Throwable) {
            println("TAP progress=$progress viewport=$viewport controls=$controls")
            println(compose.onNodeWithTag("read-page").printToString())
            screenshot("reader-tap-failure")
            throw failure
        }
        val bookmark = progress
        compose.onNodeWithTag("read-paragraph-0").performTouchInput { longClick(percentOffset(.8f, .4f)) }
        compose.waitForIdle()
        assertEquals(bookmark, progress)
        assertEquals(bookmark.offset, selected?.start)
        compose.onNodeWithTag("read-paragraph-0").performTouchInput { click(center) }
        assertEquals(1, controls)
        compose.onNodeWithTag("read-turn-stage").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.waitUntil(10_000) { viewport?.pageIndex == 2 }
        screenshot("reader-day")
    }

    @Test fun swipeOnlyTapKeepsPositionAndTogglesControls() {
        var progress by mutableStateOf(ReadProgress())
        var viewport: ReadViewport? = null
        var controls = 0
        compose.setContent { Theme {
            ReadPage(book, progress, ReadSettings(tapTurn = false), emptyList(), Modifier.fillMaxSize(), { _, _ -> }, {}, {}, {},
                onPosition = { progress = it }, onNavigatePosition = { progress = it }, onViewport = { viewport = it },
                onToggleControls = { controls++ })
        } }
        compose.waitUntil(10_000) { viewport != null }
        compose.onNodeWithTag("read-paragraph-0").performTouchInput { click(percentOffset(.9f, .4f)) }
        assertEquals(0, viewport!!.pageIndex)
        assertEquals(1, controls)
    }

    @Test fun shelfUsesCoversWithRealProgressAndSettingsExposeEssentialControls() {
        val entries = (0..8).map { index -> book.entry().copy(id = index.toString().padStart(64, '0'),
            title = listOf("雨停以后", "窗边的书", "慢慢读")[index % 3], chapterCount = 8, progress = ReadProgress(index % 8)) }
        compose.setContent { Theme { Column {
            Row(Modifier.fillMaxWidth().padding(20.dp)) { Text("共读室", style = MaterialTheme.typography.headlineSmall) }
            ReadShelf(entries, false, Modifier.weight(1f), {}, {}, {})
        } } }
        compose.onNodeWithTag("read-book-${entries.first().id}").assertIsDisplayed()
        compose.onAllNodesWithText("第 1 / 8 章").onFirst().assertIsDisplayed()
        screenshot("shelf")
    }

    @Test @Config(qualifiers = "w375dp-h812dp-xxhdpi")
    fun settingsKeepAdvancedOptionsCollapsedAndSwitchDayNight() {
        var settings by mutableStateOf(ReadSettings())
        compose.setContent { Theme(settings) { Box(Modifier.padding(20.dp)) { ReadAppearance(settings, { settings = it }) {
            androidx.compose.material3.HorizontalDivider()
            ReadConsent(false, true) {}
        } } } }
        compose.onNodeWithTag("read-turn-options").assertExists()
        compose.onNodeWithContentDescription("阅读行距").assertDoesNotExist()
        compose.onNodeWithContentDescription("显示阅读尺").assertDoesNotExist()
        compose.onNodeWithText("更多排版").performClick()
        compose.onNodeWithContentDescription("显示阅读尺").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithContentDescription("让当前助手陪我读").performScrollTo()
        val rulerLabel = compose.onNodeWithText("阅读尺", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val switch = compose.onNodeWithTag("read-toggle-显示阅读尺", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val peerLabel = compose.onNodeWithText("让当前助手陪我读", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val peerSwitch = compose.onNodeWithTag("read-toggle-让当前助手陪我读", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(peerLabel.left, rulerLabel.left, 1f)
        assertEquals(peerSwitch.right, switch.right, 1f)
        compose.onNodeWithContentDescription("显示阅读尺").assertIsOn()
        assertTrue(settings.readingRuler)
        compose.onNodeWithContentDescription("阅读段距").assertExists()
        screenshot("settings-day")
        compose.onNodeWithText("夜间").performScrollTo().performClick()
        assertEquals("dark", settings.theme)
        compose.onNodeWithText("A+").performClick()
        assertEquals(21, settings.fontSize)
        compose.onNodeWithTag("read-font-default").performClick()
        assertEquals(20, settings.fontSize)
        compose.onNodeWithContentDescription("让当前助手陪我读").performScrollTo()
        screenshot("settings-night")
    }

    @Test fun statisticsSwitchToActualAssistantReceipts() {
        val timestamp = LocalDate.now().atTime(18, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val position = "第 1 / ${book.chapters.size} 章 · ${book.chapters.first().title}"
        val records = listOf(ReadRecord("human", book.id, "chat", "ai", "human_reading", timestamp, seconds = 3600),
            ReadRecord("assistant", book.id, "chat", "ai", "assistant_reading", timestamp, characters = 28, author = "assistant"))
        compose.setContent { Theme { Box(Modifier.padding(20.dp)) {
            ReadStatisticsContent(records, false, "chat", "ai", "小林", readingPosition = position)
        } } }
        compose.onAllNodesWithText("1 时 0 分").onFirst().assertExists()
        val plot = compose.onNodeWithContentDescription("近12周阅读时间分布", substring = true)
        plot.performScrollTo()
        val center = compose.onNodeWithTag("read-statistics").fetchSemanticsNode().boundsInRoot.center.x
        assertEquals(center, plot.fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.onNodeWithText("看小林的·这本书").performScrollTo().assertHasClickAction()
        compose.onNodeWithText("我的阅读统计").performScrollTo()
        compose.onNodeWithText(position, substring = true).assertIsDisplayed()
        screenshot("statistics")
        compose.onNodeWithText("看小林的·这本书").performClick()
        compose.onNodeWithText("28 字").assertIsDisplayed()
        compose.onNodeWithText("累计片段").assertIsDisplayed()
        compose.onNodeWithText("1 时 0 分").assertDoesNotExist()
        compose.onNodeWithText(position, substring = true).assertDoesNotExist()
        screenshot("statistics-assistant")
        compose.onNodeWithText("看我的·这本书").performClick()
        compose.onNodeWithText("我的阅读统计").assertIsDisplayed()
    }

    @Test fun statisticsSwitchFitsTheMeasuredHeadingInBothViews() {
        var name by mutableStateOf("小林")
        var allBooks by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        compose.setContent { Theme(ReadSettings(), scale) {
            ReadStatisticsContent(emptyList(), allBooks, "chat", "ai", name)
        } }
        fun checkBounds(title: String, label: String) {
            val heading = compose.onNodeWithText(title).getUnclippedBoundsInRoot()
            val button = compose.onNodeWithText(label).assertHasClickAction().getUnclippedBoundsInRoot()
            assertEquals(heading.left, button.left)
            assertTrue("Button must fit under the heading", button.right <= heading.right)
        }
        fun checkFullLabel(scope: String) {
            val label = "看小林的·$scope"
            checkBounds("我的阅读统计", label)
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults ->
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    assertTrue(getResults(results))
                    assertEquals(1, results.single().lineCount)
                    assertFalse(results.single().isLineEllipsized(0))
                }
        }
        checkFullLabel("这本书")
        compose.runOnIdle { allBooks = true }
        checkFullLabel("全部书籍")
        compose.runOnIdle { name = "很长很长的读书伙伴"; allBooks = true; scale = 1.6f }
        checkBounds("我的阅读统计", "看很长很长的读书伙伴的·全部书籍")
        compose.onNodeWithText("看很长很长的读书伙伴的·全部书籍").performClick()
        checkBounds("很长很长的读书伙伴的阅读记录", "看我的·全部书籍")
    }

    @Test fun miniBarCloseDisappearsAndRevokesAuthorizationWithoutDeletingBook() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            private val root = temporary.newFolder()
            private val prefix = UUID.randomUUID().toString()
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$prefix-$name", mode)
        }
        val repo = ReadRepository(context)
        val koin = koinApplication { modules(module { single { repo } }) }
        try {
            runBlocking { repo.importParsed(book); repo.openBook(book.id, "chat"); repo.authorize("chat", "ai", true) }
            compose.setContent { KoinIsolatedContext(koin) { Theme { ReadMiniBar("chat", {}) } } }
            compose.onNodeWithContentDescription("结束共读").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("read-mini-bar").fetchSemanticsNodes().isEmpty() }
            assertFalse(repo.isAuthorized("chat", "ai"))
            assertNull(repo.currentBook.value)
            assertEquals(1, repo.library.value.size)
        } finally { compose.activityRule.scenario.close(); koin.close(); repo.close() }
    }

    @Test @Config(qualifiers = "w812dp-h375dp-xxhdpi")
    fun landscapeLargeTextKeepsPageWithinViewport() = readerScreenshot("reader-landscape-large", 1.5f)

    @Test @Config(qualifiers = "w375dp-h812dp-xxhdpi")
    fun nightReaderSupportsLargestText() = readerScreenshot("reader-night-large", 2f, "dark")

    @Test @Config(qualifiers = "w812dp-h375dp-xxhdpi")
    fun landscapeControlsRemainReachableWithLargeText() = chromeScreenshot("controls-landscape-large", 2f, "paper")

    @Test @Config(qualifiers = "w375dp-h812dp-xxhdpi")
    fun nightControlsRemainReachableWithLargeText() = chromeScreenshot("controls-night-large", 2f, "dark")

    @Test fun chapterRabbitDragsAndKeepsAccessibleProgressInBothThemes() {
        val sample = book.copy(chapters = List(9) { book.chapters.single().copy(title = "第${it + 1}章") })
        var chapter by mutableIntStateOf(4)
        var theme by mutableStateOf("paper")
        compose.setContent { Theme(ReadSettings(theme = theme)) {
            Box(Modifier.fillMaxSize()) {
                ReadReaderControls(sample, chapter, theme == "dark", true, { chapter = it }, {}, {}, {}, {},
                    Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
            }
        } }
        val slider = compose.onNodeWithContentDescription("章节进度")
        val rabbit = compose.onNodeWithTag("read-chapter-rabbit", useUnmergedTree = true)
        slider.assertIsDisplayed()
        rabbit.assertIsDisplayed()
        screenshot("chapter-rabbit-day")
        slider.performTouchInput { swipe(center, percentOffset(1.15f, .5f)) }
        compose.runOnIdle { assertEquals(sample.chapters.lastIndex, chapter) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(4f)) }
        compose.runOnIdle {
            assertEquals(4, chapter)
            theme = "dark"
        }
        rabbit.assertIsDisplayed()
        screenshot("chapter-rabbit-night")
        slider.performTouchInput { swipe(center, percentOffset(-.15f, .5f)) }
        compose.runOnIdle { assertEquals(0, chapter) }
    }

    @Test fun captureChapterRabbitCrawlAndBlinkFromRealSliderGesture() {
        val sample = book.copy(chapters = List(9) { book.chapters.single().copy(title = "第${it + 1}章") })
        var chapter by mutableIntStateOf(1)
        var jumps = 0
        compose.setContent { Theme {
            Box(Modifier.fillMaxSize()) {
                ReadReaderControls(sample, chapter, false, true, { chapter = it; jumps++ }, {}, {}, {}, {},
                    Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
            }
        } }
        val slider = compose.onNodeWithContentDescription("章节进度").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            fun frame(index: Int) = screenshot("chapter-rabbit-motion-${index.toString().padStart(3, '0')}", bottomDp = 102)
            frame(0)
            slider.performTouchInput { down(percentOffset(.20f, .5f)) }
            repeat(18) { index ->
                slider.performTouchInput { moveTo(percentOffset(.22f + .038f * index, .5f)) }
                compose.mainClock.advanceTimeByFrame()
                frame(index + 1)
            }
            compose.runOnIdle { assertEquals(0, jumps); assertEquals(1, chapter) }
            slider.performTouchInput { moveTo(percentOffset(1.1f, .5f)); up() }
            repeat(88) { index ->
                shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS)
                compose.mainClock.advanceTimeByFrame()
                frame(index + 19)
            }
            compose.runOnIdle { assertEquals(1, jumps); assertEquals(8, chapter) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun longContentsOpensNearTheCurrentChapterAndSelectsAnother() {
        val chapters = List(168) { index ->
            val titles = listOf("远山来信", "旧城的风", "窗外一盏灯", "雨过长街", "夜色抵达")
            ReadChapter("第${index + 1}章 ${titles[index % titles.size]}", listOf("目录定位测试。"))
        }
        val sample = book.copy(title = "从春天寄出的一封信", chapters = chapters)
        var chapter by mutableIntStateOf(97)
        compose.setContent { Theme { ReadContents(sample, chapter, { chapter = it }, {}) } }
        compose.onNodeWithTag("read-chapter-97").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("168 章").assertIsDisplayed()
        compose.onNodeWithTag("read-chapter-0").assertDoesNotExist()
        val pinned = compose.onNodeWithTag("read-pinned-chapter")
        pinned.assertIsDisplayed().assertTextContains(chapters[97].title)
        val beforeScroll = pinned.fetchSemanticsNode().boundsInRoot
        val chapterList = compose.onNode(hasScrollToIndexAction())
        chapterList.performScrollToIndex(0)
        compose.onNodeWithTag("read-chapter-0").assertIsDisplayed()
        compose.onNodeWithTag("read-chapter-97").assertDoesNotExist()
        pinned.assertIsDisplayed().assertTextContains(chapters[97].title)
        assertEquals(beforeScroll, pinned.fetchSemanticsNode().boundsInRoot)
        screenshot("contents-long-book")
        chapterList.performScrollToIndex(98)
        compose.onNodeWithTag("read-chapter-98").assertIsNotSelected().performClick()
        compose.onNodeWithTag("read-chapter-98").assertIsSelected()
        pinned.assertTextContains(chapters[98].title)
        assertEquals(98, chapter)
    }

    private fun chromeScreenshot(name: String, fontScale: Float, theme: String) {
        var notesOpened = false
        compose.setContent { Theme(ReadSettings(theme = theme), fontScale) {
            Box(Modifier.fillMaxSize()) {
                ReadReaderControls(book, 0, theme == "dark", true, {}, {}, {}, {}, { notesOpened = true },
                    Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
            }
        } }
        compose.onNodeWithText("目录").assertIsDisplayed()
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("看批注").assertIsDisplayed().performClick()
        assertTrue(notesOpened)
        screenshot(name)
    }

    private fun readerScreenshot(name: String, fontScale: Float, theme: String = "paper") {
        var viewport: ReadViewport? = null
        val settings = ReadSettings(theme = theme)
        val note = ReadAnnotation("note", book.id, 0, 0, 5, 20, book.chapters[0].paragraphs[0].substring(5, 20), "", "chat", "ai")
        compose.setContent { Theme(settings, fontScale) {
            ReadPage(book, ReadProgress(), settings, listOf(note), Modifier.fillMaxSize(), { _, _ -> }, {}, {}, {}, onViewport = { viewport = it })
        } }
        compose.waitUntil(10_000) { viewport != null }
        compose.onNodeWithTag("read-page-number").assertIsDisplayed()
        screenshot(name)
    }

    @Composable private fun Theme(settings: ReadSettings = ReadSettings(), fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme { ReadTheme(settings) { Surface(Modifier.fillMaxSize()) { content() } } }
        }
    }

    private fun screenshot(name: String, bottomDp: Int? = null) {
        val target = File("build/reports/tests/gongdu-read-coread-screenshots/$name.png")
        target.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val height = bottomDp?.let { (it * view.resources.displayMetrics.density).toInt() } ?: view.height
            val bitmap = Bitmap.createBitmap(view.width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { translate(0f, (height - view.height).toFloat()) }
            view.draw(canvas)
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
