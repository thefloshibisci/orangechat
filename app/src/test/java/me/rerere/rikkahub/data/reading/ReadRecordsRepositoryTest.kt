package me.rerere.rikkahub.data.reading

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.AtomicFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ReadRecordsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val book = ReadBook("e".repeat(64), "记录测试", format = "txt", importedAt = 123,
        chapters = listOf(ReadChapter("当前", listOf("她说：“你好，月光！”", "第二段真实原文", "未来段落")),
            ReadChapter("未来章", listOf("尚未读到"))))

    @Test fun activeReadingCountsOnlyRealElapsedForegroundIntervalsAndStopsAfterIdle() = runBlocking<Unit> {
        val context = isolatedContext()
        var elapsed = 1000L
        val repo = ReadRepository(context, { AtomicFile(it) }, { elapsed }, { 1_790_000_000_000L + elapsed })
        try {
            repo.importParsed(book)
            repo.openBook(book.id, "chat")
            repo.setActive("chat", true)
            elapsed += 30_000
            assertEquals(30, repo.recordReading(book.id, "chat", "ai", 30, true, elapsed)!!.seconds)
            assertNull(repo.recordReading(book.id, "chat", "ai", 30, true, elapsed))
            elapsed += 30_000
            assertNull(repo.recordReading(book.id, "chat", "ai", 30, false, elapsed))
            elapsed += 90_000
            assertNull(repo.recordReading(book.id, "chat", "ai", 30, true, elapsed - 90_000))
            assertEquals(30, repo.records.value.single().seconds)
            repo.setActive("chat", false)
            elapsed += 30_000
            assertNull(repo.recordReading(book.id, "chat", "ai", 30, true, elapsed))
            repo.setActive("chat", true)
            elapsed += 10_000
            assertEquals(40, repo.recordReading(book.id, "chat", "ai", 30, true, elapsed)!!.seconds)
            assertNull(repo.recordReading(book.id, "other-chat", "ai", 30, true, elapsed))
        } finally { repo.close() }
        val restored = ReadRepository(context)
        try {
            restored.openBook(book.id, "chat")
            assertEquals(40, restored.records.value.single().seconds)
            assertFalse(restored.session.value.authorized)
        } finally { restored.close() }
    }

    @Test fun assistantAccessRecordsExactFragmentsWithoutMovingTheHumanBookmarkOrInventingTime() = runBlocking<Unit> {
        val repo = ReadRepository(isolatedContext())
        try {
            repo.importParsed(book)
            repo.openBook(book.id, "chat")
            repo.updateProgress(book.id, 0, 1, "chat", offset = 3)
            repo.authorize("chat", "ai", true)
            val snapshot = repo.authorizedSnapshot("chat", "ai")!!
            repo.recordAssistantReading("chat", "ai", snapshot, 0, listOf(ReadFragment(0, 0, 2), ReadFragment(1, 1, 4)))
            assertEquals(ReadProgress(0, 1, 3), repo.progress.value)
            assertEquals(listOf(2, 3), repo.records.value.map { it.characters })
            assertTrue(repo.records.value.all { it.seconds == 0 && it.author == "assistant" })
            repo.recordAssistantReading("chat", "ai", snapshot, 0, listOf(ReadFragment(1, 1, 4)))
            assertEquals(2, repo.records.value.size)
            assertTrue(runCatching {
                repo.recordAssistantReading("other-chat", "ai", snapshot, 0, listOf(ReadFragment(0, 0, 2)))
            }.isFailure)
            assertTrue(runCatching {
                repo.recordAssistantReading("chat", "ai", snapshot, 1, listOf(ReadFragment(0, 0, 2)))
            }.isFailure)
            repo.authorize("chat", "ai", false)
            assertTrue(runCatching { repo.addCompanionJournal("chat", "ai", "没有授权") }.isFailure)
        } finally { repo.close() }
    }

    @Test fun companionAnnotationsCorrectOnlyNearbyVisibleQuotesAndKeepCanonicalCoordinates() = runBlocking<Unit> {
        val repo = ReadRepository(isolatedContext())
        try {
            repo.importParsed(book)
            repo.openBook(book.id, "chat")
            repo.updateProgress(book.id, 0, 1)
            repo.authorize("chat", "ai", true)
            val note = repo.addCompanionAnnotation("chat", "ai", 0, 1, "\"你好,月光!\"", "我也看见了")
            assertEquals(0, note.paragraph)
            assertEquals("“你好，月光！”", note.quote)
            assertEquals(book.chapters[0].paragraphs[0].substring(note.start, note.end), note.quote)
            assertEquals("assistant", note.author)
            repo.reply(note.id, "第二层回应", "chat", "ai")
            assertEquals(1, repo.annotations.value.single().replies.size)
            assertTrue(runCatching { repo.addCompanionAnnotation("chat", "ai", 0, 1, "未来段落", "不能预读") }.isFailure)
            assertTrue(runCatching { repo.addCompanionAnnotation("other", "ai", 0, 0, "你好", "不能串聊天") }.isFailure)
            assertEquals(ReadProgress(0, 1), repo.progress.value)
        } finally { repo.close() }
    }

    @Test fun backupRestoresNewBooksNotesAndIsolatedRecordsButPreservesExistingBooksSettingsAndNoConsent() = runBlocking<Unit> {
        val source = ReadRepository(isolatedContext())
        val destination = ReadRepository(isolatedContext())
        try {
            source.importParsed(book)
            source.openBook(book.id, "chat")
            source.updateProgress(book.id, 0, 1, offset = 2)
            source.addAnnotation(book.id, "chat", "ai", 0, 0, 0, 2, "人类批注")
            source.addJournal(book.id, "chat", "ai", "只属于这里的札记")
            source.authorize("chat", "ai", true)
            source.addCompanionJournal("chat", "ai", "助手札记")
            source.updateSettings(ReadSettings(theme = "bluecard-night", mode = "spread", paragraphSpacing = 24, letterSpacing = 1f))
            val backup = source.exportSnapshot()
            destination.updateSettings(ReadSettings(theme = "white", fontSize = 22))
            val restored = destination.restoreSnapshot(backup)
            assertEquals(ReadRestoreResult(1, 0), restored)
            assertEquals("white", destination.settings.value.theme)
            destination.openBook(book.id, "chat")
            assertEquals(ReadProgress(0, 1, 2), destination.progress.value)
            assertEquals("人类批注", destination.annotations.value.single().note)
            assertEquals(2, destination.records.value.size)
            assertFalse(destination.session.value.authorized)
            destination.authorize("chat", "ai", true)
            assertEquals(2, destination.authorizedSnapshot("chat", "ai")!!.records.size)
            assertNull(destination.authorizedSnapshot("chat", "other"))
            val changedBook = book.copy(title = "不允许覆盖已有书")
            assertEquals(ReadRestoreResult(0, 1), destination.restoreSnapshot(backup.copy(books = listOf(changedBook),
                catalog = listOf(changedBook.entry(backup.catalog.single().progress)))))
            destination.openBook(book.id, "chat")
            assertEquals(book.title, destination.currentBook.value!!.title)
            assertFalse(destination.session.value.authorized)
        } finally { source.close(); destination.close() }
    }

    @Test fun restoreRejectsInvalidWholePayloadBeforeAnyWritesAndCleansNewFilesOnInterruptedCommit() = runBlocking<Unit> {
        val context = isolatedContext()
        var failRecords = false
        val repo = ReadRepository(context, { file -> object : AtomicFile(file) {
            override fun startWrite(): FileOutputStream {
                if (failRecords && file.name.endsWith(".records.json")) throw IOException("synthetic restore interruption")
                return super.startWrite()
            }
        } })
        val backup = ReadBackup(books = listOf(book), catalog = listOf(book.entry()), annotations = emptyList(),
            records = emptyList(), settings = ReadSettings())
        try {
            assertTrue(runCatching { repo.restoreSnapshot(backup.copy(catalog = listOf(book.entry(ReadProgress(90))))) }.isFailure)
            assertTrue(repo.library.value.isEmpty())
            assertFalse(File(context.filesDir, "gongdu_read/${book.id}.book.json").exists())
            failRecords = true
            assertTrue(runCatching { repo.restoreSnapshot(backup) }.isFailure)
            assertTrue(repo.library.value.isEmpty())
            assertFalse(File(context.filesDir, "gongdu_read/${book.id}.book.json").exists())
            assertFalse(File(context.filesDir, "gongdu_read/restore-journal.json").exists())
            failRecords = false
            assertEquals(ReadRestoreResult(1, 0), repo.restoreSnapshot(backup))
        } finally { repo.close() }
    }

    @Test fun measuredViewportEnforcesCharacterBoundaryAndClearsWithoutBroadeningAccess() = runBlocking<Unit> {
        val repo = ReadRepository(isolatedContext())
        try {
            repo.importParsed(book)
            repo.openBook(book.id, "chat")
            repo.authorize("chat", "ai", true)
            repo.setActive("chat", true)
            repo.updateSettings(ReadSettings(mode = "page"))
            val futureNote = repo.addAnnotation(book.id, "chat", "ai", 0, 1, 0, 3, "下一段不可先见")
            val first = ReadProgress(0, 0, 0)
            val viewport = ReadViewport(0, 0, 3, listOf(ReadVisibleFragment(0, 0, 5)))
            repo.updateViewport(book.id, "chat", first, viewport)
            val snapshot = repo.authorizedSnapshot("chat", "ai")!!
            assertEquals(book.chapters[0].paragraphs[0].take(5), snapshot.visibleParagraphs(0).single())
            assertEquals(viewport, snapshot.viewport)
            assertEquals(0, snapshot.annotations.size)
            assertTrue(runCatching { repo.reply(futureNote.id, "超出页面", "chat", "ai") }.isFailure)
            assertTrue(runCatching { repo.addCompanionAnnotation("chat", "ai", 0, 0, "月光", "页末之后") }.isFailure)
            val page = readVisiblePage(snapshot).toString()
            assertFalse(page.contains("月光"))
            repo.clearViewport(book.id, "chat")
            assertEquals(listOf(""), repo.authorizedSnapshot("chat", "ai")!!.visibleParagraphs(0))
            assertTrue(runCatching { repo.recordAssistantReading("chat", "ai", snapshot, 0, listOf(ReadFragment(0, 0, 5))) }.isFailure)

            repo.updateProgress(book.id, 0, 1, "chat", offset = 2)
            repo.updateViewport(book.id, "chat", first, viewport) // stale CAS cannot republish an old page
            assertNull(repo.authorizedSnapshot("chat", "ai")!!.viewport)
            assertEquals("第二", repo.authorizedSnapshot("chat", "ai")!!.visibleParagraphs(0).last())
            repo.setActive("chat", false)
            assertEquals("第二", repo.authorizedSnapshot("chat", "ai")!!.visibleParagraphs(0).last())
        } finally { repo.close() }
    }

    @Test fun recordsAreBoundToExactChatAssistantAndCurrentReadableText() = runBlocking<Unit> {
        val repo = ReadRepository(isolatedContext())
        try {
            repo.importParsed(book)
            repo.openBook(book.id, "chat")
            repo.updateProgress(book.id, 1, 0)
            repo.addJournal(book.id, "chat", "ai", "未来章札记")
            repo.addJournal(book.id, "chat", "other-ai", "另一助手札记")
            repo.updateProgress(book.id, 0, 0)
            repo.addJournal(book.id, "chat", "ai", "当前札记")
            repo.authorize("chat", "ai", true)
            val snapshot = repo.authorizedSnapshot("chat", "ai")!!
            assertEquals(listOf("当前札记"), snapshot.records.map { it.text })
            assertFalse(readRecordsPage(snapshot, "chat", "ai", 0, 20).toString().contains("未来章札记"))
            repo.openBook(book.id, "other-chat")
            repo.authorize("other-chat", "ai", true)
            assertTrue(repo.authorizedSnapshot("other-chat", "ai")!!.records.isEmpty())
        } finally { repo.close() }
    }

    @Test fun exportingOneBookNeverOpensUnselectedBooksOrTheirRecords() = runBlocking<Unit> {
        val context = isolatedContext()
        val other = book.copy(id = "f".repeat(64), title = "无关书籍")
        var rejectOtherReads = false
        val repo = ReadRepository(context) { file -> object : AtomicFile(file) {
            override fun openRead(): FileInputStream {
                check(!rejectOtherReads || !file.name.startsWith(other.id)) { "unselected book must not be loaded" }
                return super.openRead()
            }
        } }
        try {
            repo.importParsed(book)
            repo.importParsed(other)
            repo.openBook(book.id, "chat")
            repo.addJournal(book.id, "chat", "ai", "本书札记")
            rejectOtherReads = true
            val snapshot = repo.exportSnapshot(book.id)
            assertEquals(listOf(book.id), snapshot.books.map { it.id })
            assertEquals(listOf("本书札记"), snapshot.records.map { it.text })
            assertTrue(runCatching { repo.exportSnapshot() }.isFailure)
        } finally { repo.close() }
    }

    @Test fun committedRestoreReturnsWarningWhenOnlyItsCleanupMarkerCannotBeRemoved() = runBlocking<Unit> {
        val context = isolatedContext()
        val repo = ReadRepository(context) { file -> object : AtomicFile(file) {
            override fun delete() { if (file.name != "restore-journal.json") super.delete() }
        } }
        try {
            val backup = ReadBackup(books = listOf(book), catalog = listOf(book.entry()), annotations = emptyList(),
                records = emptyList(), settings = ReadSettings())
            val result = repo.restoreSnapshot(backup)
            assertEquals(1, result.imported)
            assertNotNull(result.warning)
            assertEquals(listOf(book.id), repo.library.value.map { it.id })
            repo.openBook(book.id, "chat")
            assertEquals(book, repo.currentBook.value)
        } finally { repo.close() }
        val restarted = ReadRepository(context)
        try {
            restarted.openBook(book.id, "chat")
            assertEquals(book, restarted.currentBook.value)
            assertFalse(File(context.filesDir, "gongdu_read/restore-journal.json").exists())
        } finally { restarted.close() }
    }

    @Test fun lateViewportMeasuredWithOldSettingsCannotReviveAfterReflow() = runBlocking<Unit> {
        val repo = ReadRepository(isolatedContext())
        try {
            repo.importParsed(book)
            repo.openBook(book.id, "chat")
            repo.authorize("chat", "ai", true)
            repo.setActive("chat", true)
            val oldSettings = ReadSettings(mode = "page", fontSize = 16)
            repo.updateSettings(oldSettings)
            val anchor = repo.progress.value
            val oldPage = ReadViewport(0, 0, 2, listOf(ReadVisibleFragment(0, 0, 8)))
            repo.updateViewport(book.id, "chat", anchor, oldPage, expectedSettings = oldSettings)
            assertEquals(oldPage, repo.authorizedSnapshot("chat", "ai")!!.viewport)

            val largeFont = oldSettings.copy(fontSize = 30)
            repo.updateSettings(largeFont)
            repo.updateViewport(book.id, "chat", anchor, oldPage, expectedSettings = oldSettings)
            val waiting = repo.authorizedSnapshot("chat", "ai")!!
            assertNull(waiting.viewport)
            assertEquals(listOf(""), waiting.visibleParagraphs(0))

            val newPage = oldPage.copy(pageCount = 4, fragments = listOf(ReadVisibleFragment(0, 0, 4)))
            repo.updateViewport(book.id, "chat", anchor, newPage, expectedSettings = largeFont)
            repo.updateViewport(book.id, "chat", anchor, oldPage, expectedSettings = oldSettings)
            val current = repo.authorizedSnapshot("chat", "ai")!!
            assertEquals(newPage, current.viewport)
            assertEquals(book.chapters[0].paragraphs[0].take(4), current.visibleParagraphs(0).single())
        } finally { repo.close() }
    }

    private fun isolatedContext(): Context {
        val root = temporary.newFolder()
        val prefix = UUID.randomUUID().toString()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$prefix-$name", mode)
        }
    }
}
