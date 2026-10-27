package me.rerere.rikkahub.data.db.dao

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import me.rerere.rikkahub.data.repository.TranslationHistoryRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TranslationHistoryDAOTest {
    @Test
    fun `history sorts by timestamp and id and preserves full unicode text and language tag`() = runBlocking {
        withDatabase { db ->
            val dao = db.translationHistoryDao()
            val special = row("b", 2).copy(sourceText = " 原文\nemoji 👩🏽‍💻 <xml> ", translatedText = " 译文\n\"quoted\" ",
                targetLanguage = "zh-Hant-TW", modelId = "missing-model")
            dao.insert(row("z", 1)); dao.insert(row("a", 2)); dao.insert(special)
            assertEquals(listOf(special, row("a", 2), row("z", 1)), entries(dao))
        }
    }

    @Test
    fun `history read is bounded to latest hundred even before legacy rows are trimmed`() = runBlocking {
        withDatabase { db ->
            val dao = db.translationHistoryDao()
            repeat(125) { dao.insert(row(it.toString().padStart(3, '0'), 10)) }
            assertEquals((124 downTo 25).map { row(it.toString().padStart(3, '0'), 10) }, entries(dao))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM translation_history").use {
                assertTrue(it.moveToFirst())
                assertEquals(125, it.getInt(0))
            }
        }
    }

    @Test
    fun `save and trim retains deterministic latest hundred including tied timestamps`() = runBlocking {
        withDatabase { db ->
            val dao = db.translationHistoryDao()
            repeat(125) { n -> dao.saveAndTrim(row(n.toString().padStart(3, '0'), 10), 100) }
            assertEquals((124 downTo 25).map { row(it.toString().padStart(3, '0'), 10) }, entries(dao))
            assertEquals(100, rowCount(db))
        }
    }

    @Test
    fun `concurrent repository saves still retain only the latest hundred`() = runBlocking {
        withDatabase { db ->
            val dao = db.translationHistoryDao()
            val repository = TranslationHistoryRepository(dao)
            (0 until 120).map { n -> async { repository.add("source-$n", "result-$n", "en", Uuid.random()) } }.awaitAll()
            val records = entries(dao)
            assertEquals(100, records.size)
            assertEquals(100, rowCount(db))
            assertEquals(100, records.map { it.id }.toSet().size)
            assertTrue(records.all { it.sourceText.removePrefix("source-") == it.translatedText.removePrefix("result-") })
        }
    }

    @Test
    fun `failed trim rolls back the accompanying insert atomically`() = runBlocking {
        withDatabase { db ->
            val dao = db.translationHistoryDao()
            repeat(3) { dao.insert(row(it.toString(), it.toLong())) }
            val before = entries(dao)
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER block_history_trim BEFORE DELETE ON translation_history
                BEGIN SELECT RAISE(ABORT, 'test trim failure'); END""".trimIndent())
            val error = runCatching { dao.saveAndTrim(row("new", 9), 3) }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(before, entries(dao))
        }
    }

    @Test
    fun `repository rejects empty source or result without trimming retained rows`() = runBlocking {
        withDatabase { db ->
            val dao = db.translationHistoryDao()
            val repository = TranslationHistoryRepository(dao)
            val model = Uuid.random()
            repository.add("", "result", "en", model)
            repository.add(" \n\t", "result", "en", model)
            repository.add("source", " \n", "en", model)
            assertTrue(entries(dao).isEmpty())
            repository.add(" source \n", " result \n", "zh-Hant", model)
            val stored = entries(dao).single()
            assertEquals(" source \n", stored.sourceText)
            assertEquals(" result \n", stored.translatedText)
            assertEquals("zh-Hant", stored.targetLanguage)
            assertEquals(model.toString(), stored.modelId)
            assertNotNull(Uuid.parse(stored.id))
            assertTrue(stored.createAt > 0)
        }
    }

    @Test
    fun `delete one and clear all are idempotent and leave chat and usage untouched`() = runBlocking {
        withDatabase { db ->
            val dao = db.translationHistoryDao()
            val repository = TranslationHistoryRepository(dao)
            dao.insert(row("a", 1)); dao.insert(row("b", 2))
            db.usageRecordDao().insert(UsageRecordEntity(createdAt = 3, source = "TRANSLATION"))
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('chat','keep','[]',1,2)",
            )
            repository.deleteById("a"); repository.deleteById("a")
            assertEquals(listOf(row("b", 2)), entries(dao))
            repository.deleteAll(); repository.deleteAll()
            assertTrue(entries(dao).isEmpty())
            assertEquals(1L, db.usageRecordDao().getTotals().requestCount)
            db.openHelper.writableDatabase.query("SELECT title FROM ConversationEntity WHERE id='chat'").use {
                assertTrue(it.moveToFirst()); assertEquals("keep", it.getString(0))
            }
        }
    }

    private suspend fun entries(dao: TranslationHistoryDAO): List<TranslationHistoryEntity> {
        return dao.observeRecent().first()
    }

    private fun row(id: String, time: Long) = TranslationHistoryEntity(id, "source-$id", "result-$id", "en", "model", time)

    private fun rowCount(db: AppDatabase) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM translation_history").use {
        assertTrue(it.moveToFirst())
        it.getInt(0)
    }

    private suspend fun withDatabase(action: suspend (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try { action(db) } finally { db.close() }
    }
}
