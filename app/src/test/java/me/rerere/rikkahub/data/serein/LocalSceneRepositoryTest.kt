package me.rerere.rikkahub.data.serein

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalSceneRepositoryTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test fun `owners cannot read search edit delete or view each others history`() = runBlocking {
        val db = database()
        try {
            val repo = LocalSceneRepository(db)
            val a = repo.create("a", "same-operation", "Shared phrase", "Private A")
            val b = repo.create("b", "same-operation", "Shared phrase", "Private B")
            assertNotEquals(a.id, b.id)
            assertEquals(listOf(a), repo.search("a", "Shared"))
            assertNull(repo.read("b", a.id))
            assertTrue(repo.history("b", a.id).isEmpty())
            assertTrue(runCatching { repo.edit("b", a.id, 1, "changed", "changed") }.exceptionOrNull() is SceneConflict)
            assertTrue(runCatching { repo.delete("b", a.id, 1) }.exceptionOrNull() is SceneConflict)
            assertEquals(a, repo.read("a", a.id))
        } finally { db.close() }
    }

    @Test fun `save retries are idempotent and deleted text cannot be resurrected`() = runBlocking {
        val db = database()
        try {
            val repo = LocalSceneRepository(db)
            val a = repo.create("a", "stable", "Title", "Content", "one;one;two")
            assertEquals("one\ntwo", a.cues)
            assertEquals(a, repo.create("a", "stable", "Title", "Content", "one;two"))
            assertEquals(1, repo.history("a", a.id).size)
            assertTrue(runCatching { repo.create("a", "stable", "Title", "Different") }.exceptionOrNull() is SceneConflict)
            repo.delete("a", a.id, 1)
            repo.delete("a", a.id, 1)
            assertNull(repo.read("a", a.id))
            assertTrue(repo.history("a", a.id).isEmpty())
            assertTrue(repo.search("a").isEmpty())
            assertTrue(db.localSceneDao().history("a", a.id, 50).isEmpty())
            assertEquals("", db.localSceneDao().head("a", a.id)!!.body)
            assertTrue(runCatching { repo.create("a", "stable", "Title", "Content", "one;two") }.exceptionOrNull() is SceneConflict)
        } finally { db.close() }
    }

    @Test fun `concurrent edits allow one winner and retain original revision`() = runBlocking {
        val db = database()
        try {
            val repo = LocalSceneRepository(db)
            val a = repo.create("a", "first", "Title", "Original")
            val results = coroutineScope {
                listOf("First edit", "Second edit").map { body -> async {
                    runCatching { repo.edit("a", a.id, 1, a.title, body, author = "assistant") }
                } }.awaitAll()
            }
            assertEquals(1, results.count { it.isSuccess })
            assertTrue(results.single { it.isFailure }.exceptionOrNull() is SceneConflict)
            val current = repo.read("a", a.id)!!
            assertEquals(2, current.revision)
            assertEquals(listOf(current.body, "Original"), repo.history("a", a.id).map { it.body })
            assertEquals(listOf("assistant", "user"), repo.history("a", a.id).map { it.author })
            assertTrue(runCatching { repo.delete("a", a.id, 1) }.exceptionOrNull() is SceneConflict)
            assertEquals(current, repo.edit("a", a.id, 2, current.title, current.body))
            assertEquals(2, repo.history("a", a.id).size)
        } finally { db.close() }
    }

    @Test fun `literal wildcard escaping and result limits do not broaden search`() = runBlocking {
        val db = database()
        try {
            val repo = LocalSceneRepository(db)
            val special = repo.create("a", "special", "100%_path\\file", "Exact symbol memory", "中文线索")
            repeat(55) { repo.create("a", "other-$it", "Ordinary $it", "Common text") }
            listOf("%", "_", "\\", "中文线索", special.id).forEach {
                assertEquals(listOf(special.id), repo.search("a", it).map { row -> row.id })
            }
            assertEquals(50, repo.search("a").size)
            assertEquals(8, repo.search("a", "Common", 8).size)
            assertTrue(runCatching { repo.search("a", "x".repeat(201)) }.isFailure)
            assertTrue(runCatching { repo.search("a", limit = 51) }.isFailure)
        } finally { db.close() }
    }

    @Test fun `deleting an assistant erases its text and history only`() = runBlocking {
        val db = database()
        try {
            val repo = LocalSceneRepository(db)
            val a = repo.create("a", "op", "Title", "Private text")
            repo.edit("a", a.id, 1, "Title", "Revised text")
            val b = repo.create("b", "op", "Title", "Other text")
            repo.deleteAssistantMemories("a")
            repo.deleteAssistantMemories("a")
            assertTrue(repo.search("a").isEmpty())
            assertTrue(db.localSceneDao().history("a", a.id, 50).isEmpty())
            assertEquals("", db.localSceneDao().head("a", a.id)!!.body)
            assertEquals(b, repo.read("b", b.id))
            assertEquals(1, repo.history("b", b.id).size)
        } finally { db.close() }
    }

    @Test fun `invalid edits leave head and revision history unchanged`() = runBlocking {
        val db = database()
        try {
            val repo = LocalSceneRepository(db)
            val a = repo.create("a", "op", "Title", "Original")
            assertTrue(runCatching { repo.edit("a", a.id, 1, "Title", "x".repeat(1501)) }.isFailure)
            assertTrue(runCatching { repo.create("a", "op2", "Title", "Body", (1..9).joinToString(";")) }.isFailure)
            assertEquals(a, repo.read("a", a.id))
            assertEquals(1, repo.history("a", a.id).size)
            assertEquals(1, repo.search("a").size)
        } finally { db.close() }
    }
}
