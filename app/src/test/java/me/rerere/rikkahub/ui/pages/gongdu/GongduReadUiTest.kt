package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.reading.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.koinInject
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GongduReadUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val book = ReadBook("a".repeat(64), "合成书籍 · 慢慢读", "测试作者", "txt", listOf(
        ReadChapter("第一章 雨停以后", listOf("这是一段合成测试原文。窗外的雨停了，窗台上留着一小片安静。", "第二段合成原文，留给下一页。")),
        ReadChapter("第二章 风来到窗边", listOf("第三段合成原文。")),
    ))

    @Test fun emptyShelfInvitesRealImportAndContainsNoInventedBook() {
        var imports = 0
        compose.setContent { Themed { ReadShelf(emptyList(), false, Modifier.fillMaxSize(), { imports++ }, {}, {}) } }
        compose.onNodeWithText("书架还空着").assertIsDisplayed()
        compose.onNodeWithText("导入一本书").performClick()
        assertEquals(1, imports)
        compose.onNodeWithText("继续读").assertDoesNotExist()
        snapshot("read-shelf-empty")
    }

    @Test fun importedBookShowsActualProgressAndDistinctOpenAndDeleteActions() {
        var opened: String? = null
        var deleted: String? = null
        val entry = ReadBookEntry(book.id, book.title, book.author, book.format, 2, 3, ReadProgress(1, 0))
        compose.setContent { Themed { ReadShelf(listOf(entry), false, Modifier.fillMaxSize(), {},
            { opened = it.id }, { deleted = it.id }) } }
        compose.onNodeWithText("2 章 · 读到第 2 章，第 1 段").assertIsDisplayed()
        compose.onNodeWithText("继续读").performClick()
        assertEquals(book.id, opened)
        assertNull(deleted)
        compose.onNodeWithText("移出书架").performClick()
        assertEquals(book.id, deleted)
        snapshot("read-shelf-book")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun compactLargePageCanSelectActualParagraphAndTurnForward() {
        var selected: ReadParagraphSelection? = null
        var next = 0
        val settings = ReadSettings(theme = "dark", fontSize = 24, mode = "page")
        compose.setContent { Themed(settings, 1.3f) {
            ReadPage(book, ReadProgress(), settings, emptyList(), Modifier.fillMaxSize(), { _, _ -> },
                { selected = it }, {}, { next++ }, onNavigatePosition = { next++ })
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("read-paragraph-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("上一页").assertIsNotEnabled()
        compose.onNodeWithText("下一页").assertIsDisplayed().performClick()
        assertEquals(1, next)
        compose.onNodeWithTag("read-paragraph-0").performClick()
        assertEquals(book.id, selected?.bookId)
        assertEquals(0, selected?.chapter)
        assertEquals(0, selected?.paragraph)
        assertEquals(book.chapters[0].paragraphs[0], selected?.text)
        snapshot("read-page-night-large")
    }

    @Test fun finalPageDisablesOnlyForwardNavigation() {
        val settings = ReadSettings(mode = "page")
        var previous = 0
        compose.setContent { Themed(settings) {
            ReadPage(book, ReadProgress(1, 0), settings, emptyList(), Modifier.fillMaxSize(), { _, _ -> }, {}, { previous++ }, {})
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("read-paragraph-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("下一页").assertIsNotEnabled()
        compose.onNodeWithText("上一页").assertIsEnabled().performClick()
        assertEquals(1, previous)
    }

    @Test fun annotationEditorKeepsMultilineNoteAndSavesExactSelectedSource() {
        val source = book.chapters[0].paragraphs[0]
        var saved: Triple<Int, Int, String>? = null
        val selection = ReadParagraphSelection(book.id, 0, 0, book.chapters[0].title, source)
        compose.setContent { Themed { ReadAnnotationEditor(selection, false,
            { start, end, note -> saved = Triple(start, end, note) }, { _, _ -> }) } }
        compose.onNodeWithText("我的想法（可留空）").performTextInput("第一行想法\n第二行想法")
        compose.onNodeWithText("保存划线与批注").performScrollTo().performClick()
        assertEquals(Triple(0, source.length, "第一行想法\n第二行想法"), saved)
        snapshot("read-annotation-editor")
    }

    @Test fun annotationEditorPreservesNoteBeyondTwoThousandCharactersWhenSharing() {
        val note = "想".repeat(3500)
        var shared: Pair<String, String>? = null
        val selection = ReadParagraphSelection(book.id, 0, 0, book.chapters[0].title, book.chapters[0].paragraphs[0])
        compose.setContent { Themed { ReadAnnotationEditor(selection, false, { _, _, _ -> },
            { quote, thought -> shared = quote to thought }) } }
        compose.onNodeWithText("我的想法（可留空）").performTextInput(note)
        compose.onNodeWithText("和助手聊这句").performScrollTo().performClick()
        assertEquals(selection.text to note, shared)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun completeCompactRoomPersistsDarkAppearanceAndShowsShareErrorsInsideEditor() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            private val root = temporary.newFolder()
            private val prefix = UUID.randomUUID().toString()
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$prefix-$name", mode)
        }
        val repository = ReadRepository(context)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val appScope = AppScope()
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), appScope)
        val conversations = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database, files, MessageFtsManager(database))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val isolatedKoin = koinApplication { modules(module {
            single { repository }
            single { conversations }
        }) }
        try {
            runBlocking {
                repository.importParsed(book)
                conversations.insertConversation(conversation)
            }
            compose.setContent {
                KoinIsolatedContext(isolatedKoin) {
                    assertSame("Room must inject this test's reading repository", repository, koinInject<ReadRepository>())
                    assertSame("Room must inject this test's conversation repository", conversations, koinInject<ConversationRepository>())
                    Themed(fontScale = 1.1f) {
                        GongduReadRoom(conversation.id.toString(), {}, { error("请先完成当前消息的编辑，再分享书摘") })
                    }
                }
            }
            compose.onNodeWithText("继续读").performScrollTo().performClick()
            compose.waitForIdle()
            try {
                compose.waitUntil(10_000) {
                    // The IO open finishes on Android's main queue; a data-only poll cannot pump it.
                    val roomVisible = compose.onAllNodesWithContentDescription("返回书架")
                        .fetchSemanticsNodes().isNotEmpty()
                    roomVisible && repository.session.value.active
                }
            } catch (failure: Throwable) {
                println("COMPACT_ROOM_OPEN session=${repository.session.value} " +
                    "book=${repository.currentBook.value?.id} progress=${repository.progress.value} " +
                    "error=${repository.error.value} lifecycle=${compose.activity.lifecycle.currentState} " +
                    "repo=${System.identityHashCode(repository)} clock=${compose.mainClock.currentTime}")
                runCatching { println(compose.onRoot(useUnmergedTree = true).printToString()) }
                runCatching { snapshot("read-room-compact-open-failure") }
                throw failure
            }
            compose.onNodeWithContentDescription("返回书架").assertIsDisplayed()
            compose.onNodeWithText(book.title).assertIsDisplayed()
            compose.onNodeWithText("目录").assertIsDisplayed()
            compose.onNodeWithText("批注 0").assertIsDisplayed()
            compose.onNodeWithText("聊这一段").assertIsDisplayed()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("让当前助手陪我读") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("让当前助手陪我读").assertIsEnabled().performClick()
            assertTrue(repository.isAuthorized(conversation.id.toString(), conversation.assistantId.toString()))
            snapshot("read-room-compact-full")

            compose.onNodeWithText("外观").performClick()
            compose.onNodeWithText("蓝笺·夜间").performScrollTo().performClick()
            compose.waitUntil(10_000) { repository.settings.value.theme == "bluecard-night" }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                .performSemanticsAction(SemanticsActions.Dismiss) { it() }
            compose.waitForIdle()
            snapshot("read-room-compact-dark")

            compose.onNodeWithTag("read-paragraph-0").performClick()
            compose.onNodeWithText("我的想法（可留空）").performTextInput("保留这句想法")
            compose.onNodeWithText("和助手聊这句").performScrollTo().performClick()
            compose.onNodeWithTag("read-sheet-error").assertTextEquals("请先完成当前消息的编辑，再分享书摘").assertIsDisplayed()
            compose.onNodeWithText("保留这句想法").assertExists()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                .performSemanticsAction(SemanticsActions.Dismiss) { it() }
            compose.waitForIdle()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(repository.session.value.active)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("返回书架").fetchSemanticsNodes().isNotEmpty() &&
                    repository.session.value.active
            }
        } finally {
            compose.activityRule.scenario.close()
            isolatedKoin.close()
            repository.close()
            runBlocking { appScope.coroutineContext[Job]?.cancelAndJoin() }
            database.close()
        }
    }

    @Test fun consentIsRevocableAndDoesNotDependOnAnotherBusyOperation() {
        var enabled: Boolean? = null
        compose.setContent { Themed { ReadConsent(true, true) { enabled = it } } }
        compose.onNodeWithContentDescription("让当前助手陪我读").assertIsOn().assertIsEnabled().performClick()
        assertEquals(false, enabled)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun completeRoomSelectsTwentyAndCurlThenTurnsWithoutLosingViewportAcrossThemes() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            private val root = temporary.newFolder()
            private val prefix = UUID.randomUUID().toString()
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$prefix-$name", mode)
        }
        val repository = ReadRepository(context)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val appScope = AppScope()
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), appScope)
        val conversations = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database, files, MessageFtsManager(database))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val isolatedKoin = koinApplication { modules(module {
            single { repository }
            single { conversations }
        }) }
        val chatId = conversation.id.toString()
        val assistantId = conversation.assistantId.toString()
        val source = "窗外的蓝色慢慢落在书页上。我们读到同一句话，也留下不一样的想法。🫧".repeat(40)
        val longBook = book.copy(chapters = listOf(ReadChapter("第一章 翻动蓝笺", listOf(source))))
        fun viewport() = repository.authorizedSnapshot(chatId, assistantId)?.viewport
        fun dismissAppearance() {
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                .performSemanticsAction(SemanticsActions.Dismiss) { it() }
            compose.waitForIdle()
        }
        val settled = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "settled:curl")
        try {
            runBlocking {
                repository.importParsed(longBook)
                repository.updateSettings(ReadSettings(fontSize = 25))
                conversations.insertConversation(conversation)
            }
            compose.setContent {
                KoinIsolatedContext(isolatedKoin) {
                    assertSame("Room must inject this test's reading repository", repository, koinInject<ReadRepository>())
                    assertSame("Room must inject this test's conversation repository", conversations, koinInject<ConversationRepository>())
                    Themed { GongduReadRoom(chatId, {}, {}) }
                }
            }
            compose.onNodeWithText("继续读").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("让当前助手陪我读") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("让当前助手陪我读").performClick()
            compose.onNodeWithText("外观").performClick()
            compose.onNodeWithTag("read-font-default").performScrollTo().performClick()
            compose.waitUntil(10_000) { repository.settings.value.fontSize == 20 }
            for (style in listOf("slide", "cover", "curl", "vertical")) {
                compose.onNodeWithTag("read-turn-$style").performScrollTo().assertIsDisplayed().assertHasClickAction()
            }
            compose.onNodeWithTag("read-turn-curl").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                repository.settings.value.pageTurn == "curl" && repository.settings.value.mode == "page"
            }
            dismissAppearance()
            compose.waitUntil(10_000) { viewport()?.pageIndex == 0 }
            compose.onNodeWithTag("read-turn-stage").assert(settled)
            val firstPage = checkNotNull(viewport())
            assertTrue(firstPage.pageCount > 2)
            val firstEnd = firstPage.fragments.last().end

            // Freeze time to verify the actual room keeps its outgoing page during navigation.
            compose.onNodeWithText("下一页").assertIsEnabled()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("下一页").performClick()
            val turning = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "turning:curl")
            val inMotion = SemanticsMatcher("page turn fraction is strictly between zero and one") { node ->
                node.config.contains(ReadTurnFraction) && node.config[ReadTurnFraction] > 0f &&
                    node.config[ReadTurnFraction] < 1f
            }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.onAllNodes(hasTestTag("read-turn-stage") and turning and inMotion)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("read-turn-outgoing", useUnmergedTree = true).assertExists()
            assertNull("A partly turned page must not grant the next page to the assistant", viewport())
            compose.mainClock.advanceTimeBy(160)
            compose.waitForIdle()
            compose.onNodeWithTag("read-turn-stage").assert(turning and inMotion)
            snapshot("read-room-curl-turning")
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.waitUntil(10_000) { viewport()?.pageIndex == 1 }
            compose.onNodeWithTag("read-turn-stage").assert(settled)
            compose.onNodeWithTag("read-turn-outgoing", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(ReadProgress(0, 0, firstEnd), repository.progress.value)
            assertEquals(firstEnd, viewport()!!.fragments.first().start)
            snapshot("read-room-curl-twenty-day")

            for ((theme, label) in listOf("bluecard-night" to "蓝笺·夜间", "bluecard" to "蓝笺·日间")) {
                compose.onNodeWithText("外观").performClick()
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.waitUntil(10_000) { repository.settings.value.theme == theme }
                dismissAppearance()
                compose.waitUntil(10_000) { viewport()?.pageIndex == 1 }
                compose.onNodeWithTag("read-turn-stage").assert(settled)
                assertEquals(ReadProgress(0, 0, firstEnd), repository.progress.value)
                assertEquals(20, repository.settings.value.fontSize)
                assertEquals("curl", repository.settings.value.pageTurn)
                snapshot("read-room-curl-twenty-$theme")
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.activityRule.scenario.close()
            isolatedKoin.close()
            repository.close()
            runBlocking { appScope.coroutineContext[Job]?.cancelAndJoin() }
            database.close()
        }
    }

    @Test fun annotationSourceMustStillMatchBeforeItGetsHighlighted() {
        val text = "今天窗外有一只小鸟。"
        val valid = ReadAnnotation("note", book.id, 0, 0, 0, 2, "今天", "", "chat", "assistant")
        val stale = valid.copy(id = "stale", start = 2, end = 4)
        val invalid = valid.copy(id = "out-of-bounds", end = text.length + 1)
        val annotated = readHighlightedText(text, listOf(valid, stale, invalid))
        assertEquals(text, annotated.text)
        assertEquals(1, annotated.spanStyles.size)
        assertEquals(0, annotated.spanStyles.single().start)
        assertEquals(2, annotated.spanStyles.single().end)
    }

    @Test fun humanAnnotationAndAssistantReplyAreBothReadable() {
        val annotation = ReadAnnotation("note", book.id, 0, 0, 0, 2, "这是", "我留下的合成想法", "chat", "assistant",
            replies = listOf(ReadReply("这是助手的合成回应。", "assistant")))
        var located: String? = null
        compose.setContent { Themed { Column { ReadAnnotationList(listOf(annotation), false,
            { located = it.id }, {}, {}) } } }
        compose.onNodeWithText("我留下的合成想法").assertIsDisplayed()
        compose.onNodeWithText("这是助手的合成回应。").assertIsDisplayed()
        compose.onNodeWithText("回到原文").performClick()
        assertEquals(annotation.id, located)
        snapshot("read-annotations-replied")
    }

    @Composable
    private fun Themed(settings: ReadSettings = ReadSettings(), fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme { ReadTheme(settings) { Surface { Box(Modifier.fillMaxSize()) { content() } } } }
        }
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
