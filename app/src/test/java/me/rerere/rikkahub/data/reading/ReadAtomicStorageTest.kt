package me.rerere.rikkahub.data.reading

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ReadAtomicStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val book = ReadBook("d".repeat(64), "保存测试", format = "txt",
        chapters = listOf(ReadChapter("第一章", listOf("月光落在窗台上。"))))

    @Test fun silentCommitFailureIsReportedAndNeverDeletesTheRecoveredOriginal() = runBlocking<Unit> {
        val context = isolatedContext()
        var failCommit = false
        val repo = ReadRepository(context) { file ->
            object : AtomicFile(file) {
                override fun finishWrite(output: FileOutputStream) {
                    if (failCommit && file.name.endsWith(".notes.json")) {
                        // A failed backup cleanup/rename may return normally. Leave its recovery
                        // marker intact; openRead must restore the previous committed version.
                        output.close()
                    } else super.finishWrite(output)
                }
            }
        }
        try {
            val note = prepare(repo)
            val original = notesFile(context).readBytes()
            failCommit = true
            assertTrue(runCatching { repo.reply(note.id, "不能误报成功", "chat", "assistant") }.isFailure)
            assertArrayEquals(original, notesFile(context).readBytes())
            assertTrue(repo.annotations.value.single().replies.isEmpty())
            failCommit = false
            repo.openBook(book.id, "chat")
            assertEquals(note, repo.annotations.value.single())
            assertEquals("重试成功", repo.reply(note.id, "重试成功", "chat", "assistant").replies.single().text)
        } finally { repo.close() }
    }

    @Test fun partialStagingWriteFailureRollsBackAndAllowsRetry() = runBlocking<Unit> {
        val context = isolatedContext()
        var failWrite = false
        val repo = ReadRepository(context) { file ->
            object : AtomicFile(file) {
                override fun startWrite(): FileOutputStream {
                    val output = super.startWrite()
                    if (!failWrite || !file.name.endsWith(".notes.json")) return output
                    failWrite = false
                    return object : FileOutputStream(output.fd) {
                        override fun write(bytes: ByteArray) {
                            super.write(bytes, 0, minOf(bytes.size, 8))
                            throw IOException("synthetic partial write failure")
                        }
                    }
                }
            }
        }
        try {
            val note = prepare(repo)
            val original = notesFile(context).readBytes()
            failWrite = true
            assertTrue(runCatching { repo.reply(note.id, "写入一半失败", "chat", "assistant") }.isFailure)
            assertArrayEquals(original, notesFile(context).readBytes())
            assertTrue(repo.annotations.value.single().replies.isEmpty())
            assertEquals("重试成功", repo.reply(note.id, "重试成功", "chat", "assistant").replies.single().text)
        } finally { repo.close() }
    }

    @Test fun revocationDuringStagingPreventsCommitAndPreservesPreviousNotes() = runBlocking<Unit> {
        val context = isolatedContext()
        val staged = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var pauseWrite = false
        val repo = ReadRepository(context) { file ->
            object : AtomicFile(file) {
                override fun startWrite(): FileOutputStream {
                    val output = super.startWrite()
                    if (pauseWrite && file.name.endsWith(".notes.json")) {
                        staged.countDown()
                        if (!resume.await(5, TimeUnit.SECONDS)) {
                            super.failWrite(output)
                            error("staging gate timed out")
                        }
                    }
                    return output
                }
            }
        }
        try {
            val note = prepare(repo)
            val original = notesFile(context).readBytes()
            pauseWrite = true
            val pending = async(Dispatchers.IO) {
                runCatching { repo.reply(note.id, "撤回授权后不可保存", "chat", "assistant") }
            }
            try {
                assertTrue("reply reached staging", staged.await(5, TimeUnit.SECONDS))
                repo.authorize("chat", "assistant", false)
                assertFalse(repo.isAuthorized("chat", "assistant"))
            } finally { resume.countDown() }
            assertTrue(pending.await().isFailure)
            assertArrayEquals(original, notesFile(context).readBytes())
            assertTrue(repo.annotations.value.single().replies.isEmpty())
            pauseWrite = false
            repo.openBook(book.id, "chat")
            assertEquals(note, repo.annotations.value.single())
        } finally {
            resume.countDown()
            repo.close()
        }
    }

    private suspend fun prepare(repo: ReadRepository): ReadAnnotation {
        repo.importParsed(book)
        repo.openBook(book.id, "chat")
        val note = repo.addAnnotation(book.id, "chat", "assistant", 0, 0, 0, 2, "批注")
        repo.authorize("chat", "assistant", true)
        return note
    }

    private fun notesFile(context: Context) = File(context.filesDir, "gongdu_read/${book.id}.notes.json")

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
