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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
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
            { opened = it.id }, { deleted = it.id }, managing = true) } }
        compose.onNodeWithText("第 2 / 2 章").assertIsDisplayed()
        compose.onNodeWithTag("read-book-${book.id}").performClick()
        assertEquals(book.id, opened)
        assertNull(deleted)
        compose.onNodeWithTag("read-delete-${book.id}").performClick()
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
        compose.onNodeWithContentDescription("上一页").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一页").assertIsDisplayed().performClick()
        assertEquals(1, next)
        compose.onNodeWithTag("read-paragraph-0").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
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
        compose.onNodeWithContentDescription("下一页").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上一页").assertIsEnabled().performClick()
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
        var sharedCard: ReadShare? = null
        var rejectShare = false
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val appScope = AppScope()
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), appScope)
        val conversations = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database, files, MessageFtsManager(database))
        // Seed the UI settings stream without exercising Android's keystore in a reader UI test.
        val appSettings = SettingsStore(context, AppScope().also { it.coroutineContext[Job]?.cancel() })
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val isolatedKoin = koinApplication { modules(module {
            single { repository }
            single { conversations }
            single { appSettings }
        }) }
        try {
            runBlocking {
                repository.importParsed(book)
                appSettings.settingsFlow.value = appSettings.settingsFlow.value.copy(
                    assistants = listOf(Assistant(id = conversation.assistantId, name = "南枳")))
                conversations.insertConversation(conversation)
            }
            compose.setContent {
                KoinIsolatedContext(isolatedKoin) {
                    assertSame("Room must inject this test's reading repository", repository, koinInject<ReadRepository>())
                    assertSame("Room must inject this test's conversation repository", conversations, koinInject<ConversationRepository>())
                    Themed(fontScale = 1.1f) {
                        GongduReadRoom(conversation.id.toString(), {}, { card ->
                            if (rejectShare) error("请先完成当前消息的编辑，再分享书摘")
                            sharedCard = checkNotNull(parseReadShare(card))
                        })
                    }
                }
            }
            compose.onNodeWithContentDescription("书架菜单").performClick()
            compose.onNodeWithText("阅读统计").assertIsDisplayed()
            compose.onNodeWithText("管理书籍").assertIsDisplayed()
            compose.onNodeWithText("备份与恢复").assertIsDisplayed()
            snapshot("read-room-quark-shelf-menu")
            compose.onNodeWithText("阅读统计").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("看南枳的·全部书籍").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("看南枳的·全部书籍").performClick()
            compose.onNodeWithText("南枳的阅读记录").assertIsDisplayed()
            compose.onNodeWithText("看我的·全部书籍").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭阅读统计").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("书架菜单").performClick()
            compose.onNodeWithText("管理书籍").performClick()
            compose.onNodeWithTag("read-delete-${book.id}").assertIsDisplayed()
            compose.onNodeWithContentDescription("书架菜单").performClick()
            compose.onNodeWithText("完成管理").performClick()
            compose.onNodeWithTag("read-delete-${book.id}").assertDoesNotExist()
            compose.onNodeWithContentDescription("导入一本书").assertExists()
            compose.onNodeWithTag("read-book-${book.id}").performClick()
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
            compose.onNodeWithTag("read-controls").assertDoesNotExist()
            snapshot("read-room-coread-day")
            val beforeControls = repository.progress.value
            showControls()
            assertEquals(beforeControls, repository.progress.value)
            compose.onNodeWithText("目录").assertIsDisplayed()
            compose.onNodeWithContentDescription("看批注").assertIsDisplayed()
            compose.onNodeWithText("看批注").assertDoesNotExist()
            snapshot("read-room-quark-controls")
            compose.onNodeWithText("目录").performClick()
            compose.onNodeWithTag("read-contents-drawer").assertIsDisplayed()
            compose.onNodeWithTag("read-chapter-0").assertIsSelected()
            compose.onNodeWithTag("read-chapter-1").assertIsNotSelected()
            snapshot("read-room-quark-contents")
            compose.onNodeWithText(book.chapters[1].title).performClick()
            compose.waitForIdle()
            compose.waitUntil(10_000) { repository.progress.value.chapter == 1 }
            compose.onNodeWithTag("read-contents-drawer").assertIsNotDisplayed()
            compose.onNodeWithText("目录").performClick()
            compose.onNodeWithText(book.chapters[0].title).performClick()
            compose.waitForIdle()
            compose.waitUntil(10_000) { repository.progress.value.chapter == 0 }
            runBlocking { repository.addAnnotation(book.id, conversation.id.toString(), conversation.assistantId.toString(),
                0, 0, 0, 13, "一起读的时候，连这样平常的一句话，也想留给你。") }
            compose.onNodeWithContentDescription("看批注").performClick()
            compose.onNodeWithText("全部批注").assertIsDisplayed()
            compose.onNodeWithText("一起读的时候，连这样平常的一句话，也想留给你。").assertIsDisplayed()
            val sheetBounds = compose.onNodeWithTag("read-annotation-sheet").fetchSemanticsNode().boundsInRoot
            val listBounds = compose.onNodeWithTag("read-annotations").fetchSemanticsNode().boundsInRoot
            // A single note should make a compact drawer, rather than stretching an almost-full empty sheet.
            assertTrue(sheetBounds.height < compose.activity.window.decorView.height * .75f)
            assertTrue(listBounds.height < sheetBounds.height)
            snapshot("read-room-quark-annotations")
            compose.onNodeWithContentDescription("关闭批注").performClick()
            compose.waitForIdle()
            openSettings()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("让当前助手陪我读") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("让当前助手陪我读").performScrollTo().assertIsEnabled().performClick()
            assertTrue(repository.isAuthorized(conversation.id.toString(), conversation.assistantId.toString()))
            compose.onNodeWithText("聊这一页").performScrollTo().assertIsEnabled().performClick()
            assertEquals(book.chapters[0].paragraphs.joinToString("\n"), sharedCard?.quote)
            snapshot("read-room-compact-full")

            compose.onNode(hasText("夜间") and hasAnyAncestor(hasTestTag("read-appearance"))).performScrollTo().performClick()
            compose.waitUntil(10_000) { repository.settings.value.theme == "dark" }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                .performSemanticsAction(SemanticsActions.Dismiss) { it() }
            compose.waitForIdle()
            snapshot("read-room-compact-dark")

            compose.runOnIdle { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(7)) }
            openSettings()
            compose.onNodeWithText("本书统计").performScrollTo().performClick()
            compose.onNodeWithText("我的进度 · 第 1 / 2 章 · 第一章 雨停以后").assertIsDisplayed()
            assertTrue(runBlocking { repository.readingRecords(book.id).any { it.kind == "human_reading" && it.seconds >= 7 } })
            compose.onNodeWithContentDescription("关闭阅读统计").performClick()
            compose.waitForIdle()

            rejectShare = true

            compose.onNodeWithTag("read-paragraph-0").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
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
            compose.onNodeWithContentDescription("返回书架").performClick()
            compose.onNodeWithTag("read-shelf").assertIsDisplayed()
            compose.onNodeWithContentDescription("书架菜单").performClick()
            compose.onNodeWithText("备份与恢复").performClick()
            compose.onNodeWithTag("read-extras").assertIsDisplayed()
            compose.onNodeWithTag("read-journal-input").assertDoesNotExist()
            compose.onNodeWithText("保存阅读备份").assertIsEnabled()
            compose.onNodeWithText("从备份恢复").assertIsEnabled()
            compose.onNodeWithContentDescription("关闭备份与恢复").performClick()
        } finally {
            appScope.coroutineContext[Job]?.cancel()
            compose.waitForIdle()
            compose.activityRule.scenario.close()
            isolatedKoin.close()
            repository.close()
            database.close()
        }
    }

    @Test fun consentIsRevocableAndDoesNotDependOnAnotherBusyOperation() {
        var enabled: Boolean? = null
        compose.setContent { Themed { ReadConsent(true, true) { enabled = it } } }
        compose.onNodeWithContentDescription("让当前助手陪我读").assertIsOn().assertIsEnabled().performClick()
        assertEquals(false, enabled)
    }

    @Test fun threeColumnShelfShowsCoverArtBehindCompactManagementMenu() {
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
        val titles = listOf("潮线", "去山里寄信", "漫长的午后", "林中来信", "落日航班", "第七个雨季", "夜行札记", "河流图志", "群星之间")
        val colors = listOf("#165769", "#BE8456", "#E0CB9A", "#516951", "#C4573B", "#507488", "#263746", "#687E70", "#6B566A")
        fun illustratedBook(index: Int): ReadBook {
            val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.parseColor(colors[index]))
            val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xffF7E8C9.toInt() }
            canvas.drawCircle(155f - index * 4, 140f + index * 5, 48f, ink)
            ink.style = android.graphics.Paint.Style.STROKE
            ink.strokeWidth = 2f
            repeat(13) { n ->
                val y = 194f + n * 10
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(-6f, y); cubicTo(80f, y - 64f, 154f, y + 40f - index * 6, 252f, y - 23f)
                }, ink)
            }
            ink.style = android.graphics.Paint.Style.FILL
            ink.textSize = 29f
            canvas.drawText(titles[index], 18f, 57f, ink)
            val raw = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val image = ReadImage(readSha256(raw), "image/png", java.util.Base64.getEncoder().encodeToString(raw))
            return book.copy(id = readSha256(titles[index].toByteArray()), title = titles[index], images = mapOf(image.id to image), coverImageId = image.id)
        }
        try {
            runBlocking {
                conversations.insertConversation(conversation)
                for (index in titles.indices.reversed()) repository.importParsed(illustratedBook(index))
            }
            compose.setContent { KoinIsolatedContext(isolatedKoin) { Themed { GongduReadRoom(conversation.id.toString(), {}, {}) } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("read-cover-image", useUnmergedTree = true).fetchSemanticsNodes().size >= 9 }
            val covers = compose.onAllNodesWithTag("read-cover-image", useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(3, covers.count { it.boundsInRoot.top == covers.first().boundsInRoot.top })
            compose.onNodeWithContentDescription("书架菜单").performClick()
            compose.onNodeWithText("管理书籍").performClick()
            compose.onNodeWithContentDescription("书架菜单").performClick()
            compose.onNodeWithText("完成管理").assertIsDisplayed()
            snapshot("read-shelf-glass-managed")
        } finally {
            compose.activityRule.scenario.close()
            appScope.coroutineContext[Job]?.cancel()
            isolatedKoin.close()
            repository.close()
            database.close()
        }
    }

    @Test fun chatIslandRestoresTheBookmarkedPageAndPaintsBothDialogEdges() {
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
        val activeChat = androidx.compose.runtime.mutableStateOf(chatId)
        val offset = 800
        val longBook = book.copy(chapters = book.chapters + ReadChapter("第三章 夜读", listOf("月光落在书上。".repeat(300))))
        fun assertResumed() {
            compose.onNodeWithText("共读").performClick()
            compose.onNodeWithTag("read-page").assertIsDisplayed()
            compose.waitUntil(10_000) {
                val viewport = repository.authorizedSnapshot(chatId, assistantId)?.viewport
                viewport != null && viewport.chapter == 2 && viewport.pageIndex > 0 &&
                    viewport.fragments.any { it.paragraph == 0 && offset in it.start until it.end }
            }
        }
        fun checkEdges(paper: Int) {
            compose.waitForIdle()
            compose.runOnIdle {
                val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                val view = requireNotNull(dialog.window).decorView
                val host = compose.activity.window.decorView
                assertEquals(host.width, view.width)
                assertEquals(host.height, view.height)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                assertEquals(paper, bitmap.getPixel(view.width / 2, 0))
                assertEquals(paper, bitmap.getPixel(view.width / 2, view.height - 1))
                bitmap.recycle()
            }
        }
        try {
            runBlocking {
                conversations.insertConversation(conversation)
                repository.importParsed(longBook)
                repository.openBook(book.id, chatId)
                repository.updateProgress(book.id, 2, 0, chatId, offset = offset)
                repository.authorize(chatId, assistantId, true)
            }
            compose.setContent { KoinIsolatedContext(isolatedKoin) { Themed { ChatReadMiniBar(activeChat.value) { true } } } }
            assertResumed()
            checkEdges(0xFFF1E8D9.toInt())
            compose.onNodeWithContentDescription("返回书架").performClick()
            compose.onNodeWithTag("read-book-${book.id}").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回共度").performClick()
            runBlocking { repository.updateSettings(repository.settings.value.copy(theme = "dark")) }
            assertResumed()
            checkEdges(0xFF1A1A1A.toInt())
            compose.runOnIdle { activeChat.value = Uuid.random().toString() }
            compose.onNodeWithTag("read-mini-bar").assertDoesNotExist()
            compose.onNodeWithTag("read-page").assertDoesNotExist()
        } finally {
            compose.activityRule.scenario.close()
            appScope.coroutineContext[Job]?.cancel()
            isolatedKoin.close()
            repository.close()
            database.close()
        }
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
        val source = "窗外的蓝色慢慢落在书页上。我们读到同一句话，也留下不一样的想法。".repeat(40)
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
            compose.onNodeWithTag("read-book-${book.id}").performClick()
            openSettings()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("让当前助手陪我读") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("让当前助手陪我读").performScrollTo().performClick()
            compose.onNodeWithTag("read-font-default").performScrollTo().performClick()
            compose.waitUntil(10_000) { repository.settings.value.fontSize == 20 }
            snapshot("read-room-settings-day-sheet")
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
            compose.onNodeWithTag("read-paragraph-0").performTouchInput { click(center) }
            compose.onNodeWithTag("read-controls").assertDoesNotExist()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("read-paragraph-0").performTouchInput { click(percentOffset(.9f, .3f)) }
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

            for ((theme, label) in listOf("dark" to "夜间", "paper" to "日间")) {
                openSettings()
                compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("read-appearance"))).performScrollTo().performClick()
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
            appScope.coroutineContext[Job]?.cancel()
            compose.waitForIdle()
            compose.activityRule.scenario.close()
            isolatedKoin.close()
            repository.close()
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
        compose.onNodeWithContentDescription("批注操作").performClick()
        compose.onNodeWithText("回到原文").performClick()
        assertEquals(annotation.id, located)
        snapshot("read-annotations-replied")
    }

    private fun showControls() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("read-paragraph-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("read-controls").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("read-paragraph-0").performTouchInput { click(center) }
            compose.waitForIdle()
        }
    }

    private fun openSettings() {
        showControls()
        compose.onNodeWithText("设置").performClick()
        compose.waitForIdle()
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
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            // ModalBottomSheet lives in a separate Android dialog window. Include it in captures.
            val manager = org.robolectric.util.ReflectionHelpers.callStaticMethod<Any>(
                Class.forName("android.view.WindowManagerGlobal"), "getInstance")
            val windows = org.robolectric.util.ReflectionHelpers.getField<List<android.view.View>>(manager, "mViews")
            windows.filter { it !== view && it.isShown && it.width > 0 && it.height > 0 }.forEach { window ->
                val params = window.layoutParams as? android.view.WindowManager.LayoutParams
                if (params != null && params.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                }
                val origin = IntArray(2); val base = IntArray(2)
                window.getLocationOnScreen(origin); view.getLocationOnScreen(base)
                canvas.save()
                canvas.translate((origin[0] - base[0]).toFloat(), (origin[1] - base[1]).toFloat())
                window.draw(canvas)
                canvas.restore()
            }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
