package me.rerere.rikkahub.data.reading

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ReadRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }
    private val book = ReadBook("a".repeat(64), "合成测试书", "合成作者", "txt", listOf(
        ReadChapter("第一章", listOf("月光落在窗台上。", "她翻开下一页。")),
        ReadChapter("第二章", listOf("这里是尚未读到的原文。")),
    ), importedAt = 123L)

    @Test fun legacySettingsWithoutPageTurnKeepDefaultTwentyAndExistingCustomFont() {
        val fresh = ReadRepository(isolatedContext())
        try {
            assertEquals(20, fresh.settings.value.fontSize)
            assertEquals("slide", fresh.settings.value.pageTurn)
        } finally { fresh.close() }

        val context = isolatedContext()
        val legacy = """{"theme":"bluecard-night","fontSize":25,"lineHeight":2.0,"mode":"spread","paragraphSpacing":24,"letterSpacing":1.0,"readingRuler":true}"""
        val prefs = context.getSharedPreferences("gongdu_read", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("settings", legacy).commit())
        val restored = ReadRepository(context)
        try {
            assertEquals(ReadSettings(theme = "bluecard-night", fontSize = 25, lineHeight = 2f,
                mode = "spread", paragraphSpacing = 24, letterSpacing = 1f, readingRuler = true,
                pageTurn = "slide"), restored.settings.value)
            assertEquals(legacy, prefs.getString("settings", null))
        } finally { restored.close() }
    }

    @Test fun everyPageTurnStyleSurvivesRestartWithoutResettingOtherReadingPreferences() = runBlocking<Unit> {
        val context = isolatedContext()
        for (style in listOf("slide", "cover", "curl", "vertical")) {
            val expected = ReadSettings(theme = "bluecard-night", fontSize = 25, lineHeight = 2f,
                mode = "spread", paragraphSpacing = 24, letterSpacing = 1f, readingRuler = true, pageTurn = style)
            val writer = ReadRepository(context)
            try { writer.updateSettings(expected) } finally { writer.close() }
            val restarted = ReadRepository(context)
            try {
                assertEquals("Page turn $style must survive repository recreation", expected, restarted.settings.value)
                assertFalse(restarted.session.value.authorized)
            } finally { restarted.close() }
        }
    }

    @Test fun restartRestoresBookProgressNotesRepliesAndSettingsButNeverConsent() = runBlocking<Unit> {
        val context = isolatedContext()
        val first = ReadRepository(context)
        lateinit var savedAnnotation: ReadAnnotation
        try {
            first.importParsed(book)
            first.openBook(book.id, "chat-a")
            first.updateProgress(book.id, 0, 1)
            first.updateSettings(ReadSettings("dark", 25, 2f, "page"))
            val note = first.addAnnotation(book.id, "chat-a", "assistant-a", 0, 0, 0, 2, "这句让我想到你")
            first.authorize("chat-a", "assistant-a", true)
            first.setActive("chat-a", true)
            savedAnnotation = first.reply(note.id, "我也看到这一束月光。", "chat-a", "assistant-a")
            // Every timestamp must have a value on disk, including dynamic constructor defaults.
            assertEquals(book.importedAt, json.parseToJsonElement(bookFile(context, book.id).readText())
                .jsonObject.getValue("importedAt").jsonPrimitive.long)
            val storedNote = json.parseToJsonElement(notesFile(context, book.id).readText())
                .jsonArray.single().jsonObject
            assertEquals(savedAnnotation.createdAt, storedNote.getValue("createdAt").jsonPrimitive.long)
            assertEquals(savedAnnotation.replies.single().createdAt,
                storedNote.getValue("replies").jsonArray.single().jsonObject.getValue("createdAt").jsonPrimitive.long)
        } finally { first.close() }
        val restored = ReadRepository(context)
        try {
            assertFalse(restored.session.value.authorized)
            restored.openBook(book.id, "chat-a")
            assertEquals(book, restored.currentBook.value)
            assertEquals(ReadProgress(0, 1), restored.progress.value)
            assertEquals(ReadSettings("dark", 25, 2f, "page"), restored.settings.value)
            assertEquals("这句让我想到你", restored.annotations.value.single().note)
            assertEquals("我也看到这一束月光。", restored.annotations.value.single().replies.single().text)
            assertEquals(savedAnnotation, restored.annotations.value.single())
            assertFalse(restored.isAuthorized("chat-a", "assistant-a"))
            assertFalse(restored.session.value.active)
        } finally { restored.close() }
    }

    @Test fun duplicateSourceIdentityPreservesOriginalContentAndProgress() = fixture { repo, context ->
        repo.importParsed(book)
        repo.openBook(book.id, "chat-a")
        repo.updateProgress(book.id, 1, 0)
        val originalBytes = bookFile(context, book.id).readBytes()
        val duplicate = repo.importParsed(book.copy(title = "新的文件名", chapters = listOf(ReadChapter("被替换的章", listOf("不是原文")))))
        assertEquals(book, duplicate)
        assertEquals(1, repo.library.value.size)
        assertEquals(ReadProgress(1, 0), repo.library.value.single().progress)
        assertArrayEquals(originalBytes, bookFile(context, book.id).readBytes())
    }

    @Test fun consentRequiresOpenBookAndCannotBeTransferredByStaleSwitches() = fixture { repo, _ ->
        assertFailure { repo.authorize("chat-a", "assistant-a", true) }
        repo.importParsed(book)
        repo.openBook(book.id, "chat-a")
        repo.authorize("chat-a", "assistant-a", true)
        repo.authorize("chat-b", "assistant-b", false)
        assertTrue(repo.isAuthorized("chat-a", "assistant-a"))
        assertFalse(repo.isAuthorized("chat-b", "assistant-a"))
        assertFailure { repo.authorize("chat-b", "assistant-b", true) }
        repo.openBook(book.id, "chat-b")
        assertFalse(repo.isAuthorized("chat-a", "assistant-a"))
        assertFalse(repo.isAuthorized("chat-b", "assistant-b"))
        repo.authorize("chat-b", "assistant-b", true)
        repo.authorize("chat-a", "assistant-a", false)
        assertTrue(repo.isAuthorized("chat-b", "assistant-b"))
    }

    @Test fun snapshotsFilterOtherChatsOtherAssistantsAndUnreadAnnotations() = fixture { repo, _ ->
        repo.importParsed(book)
        repo.openBook(book.id, "chat-a")
        val visible = repo.addAnnotation(book.id, "chat-a", "assistant-a", 0, 0, 0, 2, "当前")
        val future = repo.addAnnotation(book.id, "chat-a", "assistant-a", 1, 0, 0, 2, "未读")
        repo.addAnnotation(book.id, "chat-b", "assistant-a", 0, 0, 0, 2, "另一聊天")
        repo.addAnnotation(book.id, "chat-a", "assistant-b", 0, 0, 0, 2, "另一助手")
        assertNull(repo.authorizedSnapshot("chat-a", "assistant-a"))
        repo.authorize("chat-a", "assistant-a", true)
        assertEquals(listOf(visible.id), repo.authorizedSnapshot("chat-a", "assistant-a")!!.annotations.map { it.id })
        repo.updateProgress(book.id, 1, 0)
        assertEquals(listOf(visible.id, future.id), repo.authorizedSnapshot("chat-a", "assistant-a")!!.annotations.map { it.id })
        repo.authorize("chat-a", "assistant-a", false)
        assertNull(repo.authorizedSnapshot("chat-a", "assistant-a"))
    }

    @Test fun replyingRequiresLiveConsentOwnershipAndAnAlreadyReadPosition() = fixture { repo, _ ->
        repo.importParsed(book)
        repo.openBook(book.id, "chat-a")
        val note = repo.addAnnotation(book.id, "chat-a", "assistant-a", 0, 0, 0, 2, "当前")
        val future = repo.addAnnotation(book.id, "chat-a", "assistant-a", 1, 0, 0, 2, "未读")
        assertSuspendFailure { repo.reply(note.id, "未授权", "chat-a", "assistant-a") }
        repo.authorize("chat-a", "assistant-a", true)
        assertSuspendFailure { repo.reply(note.id, "别的聊天", "chat-b", "assistant-a") }
        assertSuspendFailure { repo.reply(future.id, "提前剧透", "chat-a", "assistant-a") }
        assertEquals("读到这里", repo.reply(note.id, "读到这里", "chat-a", "assistant-a").replies.single().text)
        repo.authorize("chat-a", "assistant-a", false)
        assertSuspendFailure { repo.reply(note.id, "撤销后", "chat-a", "assistant-a") }
        assertEquals(1, repo.annotations.value.first { it.id == note.id }.replies.size)
    }

    @Test fun brokenCatalogBlocksImportsAndKeepsOriginalBytes() = runBlocking<Unit> {
        val context = isolatedContext()
        val file = catalogFile(context).apply { parentFile!!.mkdirs(); writeText("{broken") }
        val original = file.readBytes()
        val repo = ReadRepository(context)
        try {
            assertSuspendFailure { repo.importParsed(book) }
            assertNotNull(repo.error.value)
            assertArrayEquals(original, file.readBytes())
            assertFalse(bookFile(context, book.id).exists())
        } finally { repo.close() }
    }

    @Test fun invalidCatalogSchemaIsRetainedRatherThanNormalizedAndOverwritten() = runBlocking<Unit> {
        val context = isolatedContext()
        val badEntry = book.entry().copy(title = "", chapterCount = -1, paragraphCount = 0, progress = ReadProgress(-1, -1))
        val file = catalogFile(context).apply { parentFile!!.mkdirs(); writeText(json.encodeToString(listOf(badEntry))) }
        val original = file.readBytes()
        val repo = ReadRepository(context)
        try {
            assertSuspendFailure { repo.importParsed(book.copy(id = "b".repeat(64))) }
            assertArrayEquals(original, file.readBytes())
        } finally { repo.close() }
    }

    @Test fun corruptNotesCannotBeOverwrittenByOpeningOrAddingAnotherNote() = fixture { repo, context ->
        repo.importParsed(book)
        val file = notesFile(context, book.id).apply { writeText("{broken") }
        val original = file.readBytes()
        assertSuspendFailure { repo.openBook(book.id, "chat-a") }
        assertSuspendFailure { repo.addAnnotation(book.id, "chat-a", "assistant-a", 0, 0, 0, 2, "new") }
        assertArrayEquals(original, file.readBytes())
        assertNull(repo.currentBook.value)
    }

    @Test fun invalidAnnotationCoordinatesOrInventedQuoteAreNotAcceptedAsRealSource() = fixture { repo, context ->
        repo.importParsed(book)
        val bad = ReadAnnotation("bad-note", book.id, -1, 0, -1, 20, "编造原文", "保留原件", "chat-a", "assistant-a")
        val file = notesFile(context, book.id).apply { writeText(json.encodeToString(listOf(bad))) }
        val original = file.readBytes()
        assertSuspendFailure { repo.openBook(book.id, "chat-a") }
        assertSuspendFailure { repo.addAnnotation(book.id, "chat-a", "assistant-a", 0, 0, 0, 2, "new") }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun orphanedBookIdentityMismatchDoesNotRewriteCatalog() = fixture { repo, context ->
        // Await the initial catalog before introducing an orphaned interrupted-import file.
        repo.importParsed(book.copy(id = "b".repeat(64)))
        val orphan = bookFile(context, book.id).apply { writeText(json.encodeToString(book.copy(id = "c".repeat(64)))) }
        val original = orphan.readBytes()
        val catalogBefore = catalogFile(context).readBytes()
        assertSuspendFailure { repo.importParsed(book) }
        assertArrayEquals(original, orphan.readBytes())
        assertArrayEquals(catalogBefore, catalogFile(context).readBytes())
        assertEquals(listOf("b".repeat(64)), repo.library.value.map { it.id })
    }

    @Test fun deletingCurrentBookClearsConsentAndAllLocalBookRecords() = fixture { repo, context ->
        repo.importParsed(book)
        repo.openBook(book.id, "chat-a")
        repo.addAnnotation(book.id, "chat-a", "assistant-a", 0, 0, 0, 2, "删除测试")
        repo.authorize("chat-a", "assistant-a", true)
        repo.deleteBook(book.id)
        assertTrue(repo.library.value.isEmpty())
        assertNull(repo.currentBook.value)
        assertTrue(repo.annotations.value.isEmpty())
        assertFalse(repo.isAuthorized("chat-a", "assistant-a"))
        assertFalse(bookFile(context, book.id).exists())
        assertFalse(notesFile(context, book.id).exists())
        assertSuspendFailure { repo.openBook(book.id, "chat-a") }
    }

    @Test fun invalidPositionsAndSettingsLeavePersistedDataUnchanged() = fixture { repo, context ->
        repo.importParsed(book)
        repo.openBook(book.id, "chat-a")
        val before = catalogFile(context).readBytes()
        assertSuspendFailure { repo.updateProgress(book.id, 3, 0) }
        assertSuspendFailure { repo.updateProgress(book.id, 0, -1) }
        assertSuspendFailure { repo.updateSettings(ReadSettings(fontSize = 200)) }
        assertSuspendFailure { repo.updateSettings(ReadSettings(lineHeight = Float.NaN)) }
        assertSuspendFailure { repo.updateSettings(ReadSettings(pageTurn = "unknown")) }
        assertArrayEquals(before, catalogFile(context).readBytes())
        assertEquals(ReadProgress(), repo.progress.value)
        assertEquals(ReadSettings(), repo.settings.value)
    }

    @Test fun delayedScrollCannotOverwriteNewerChapterOrParagraphNavigation() = fixture { repo, context ->
        val scrollBook = book.copy(chapters = listOf(book.chapters[0],
            book.chapters[1].copy(paragraphs = book.chapters[1].paragraphs + "再往下一段。")))
        repo.importParsed(scrollBook)
        repo.openBook(book.id, "chat-a")
        repo.updateProgress(book.id, 0, 1, "chat-a", expectedProgress = ReadProgress(0, 0))
        assertEquals(ReadProgress(0, 1), repo.progress.value)

        // Explicit navigation is already committed when an old chapter's debounce finishes.
        repo.updateProgress(book.id, 1, 0, "chat-a")
        val afterNavigation = catalogFile(context).readBytes()
        repo.updateProgress(book.id, 0, 1, "chat-a", expectedProgress = ReadProgress(0, 1))
        assertEquals(ReadProgress(1, 0), repo.progress.value)
        assertEquals(ReadProgress(1, 0), repo.library.value.single().progress)
        assertArrayEquals(afterNavigation, catalogFile(context).readBytes())

        repo.updateProgress(book.id, 1, 1, "chat-a", expectedProgress = ReadProgress(1, 0))
        assertEquals(ReadProgress(1, 1), repo.progress.value)
        val afterParagraphNavigation = catalogFile(context).readBytes()
        repo.updateProgress(book.id, 1, 0, "chat-a", expectedProgress = ReadProgress(1, 0))
        assertEquals(ReadProgress(1, 1), repo.progress.value)
        assertArrayEquals(afterParagraphNavigation, catalogFile(context).readBytes())
        // A deliberate previous-chapter action can still move backwards.
        repo.updateProgress(book.id, 0, 0, "chat-a")
        assertEquals(ReadProgress(0, 0), repo.progress.value)
    }

    private fun fixture(block: suspend (ReadRepository, ReadingContext) -> Unit) = runBlocking<Unit> {
        val context = isolatedContext()
        val repo = ReadRepository(context)
        try { block(repo, context) } finally { repo.close() }
    }

    private fun isolatedContext() = ReadingContext(RuntimeEnvironment.getApplication(), temporary.newFolder(), UUID.randomUUID().toString())

    private fun bookFile(context: Context, id: String) = File(context.filesDir, "gongdu_read/$id.book.json")
    private fun notesFile(context: Context, id: String) = File(context.filesDir, "gongdu_read/$id.notes.json")
    private fun catalogFile(context: Context) = File(context.filesDir, "gongdu_read/catalog.json")

    private class ReadingContext(base: Context, private val root: File, private val prefix: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$prefix-$name", mode)
    }

    private fun assertFailure(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private suspend fun assertSuspendFailure(block: suspend () -> Any?) {
        assertTrue(runCatching { block() }.isFailure)
    }
}
