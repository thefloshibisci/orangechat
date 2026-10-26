package me.rerere.rikkahub.data.db.dao

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class UsageRecordDAOTest {
    private val day = LocalDate.of(2026, 10, 23)
    private val zone = ZoneId.systemDefault()
    private val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val tomorrow = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `empty ledger has zero totals no buckets and no first timestamp`() = runBlocking {
        withDatabase { db ->
            val dao = db.usageRecordDao()
            assertEquals(0L, dao.observeLatestId().first())
            assertNull(dao.getFirstRecordTimestamp())
            assertEquals(UsageSummary(UsageTotals(), emptyList(), emptyList(), emptyList()),
                dao.getSummary(0, Long.MAX_VALUE, "%Y-%m-%d"))
            assertEquals(UsageTotals(), dao.getTotals())
        }
    }

    @Test
    fun `summary uses inclusive start exclusive end local dates and source breakdowns`() = runBlocking {
        withDatabase { db ->
            val dao = db.usageRecordDao()
            dao.insert(row(midnight - 1))
            dao.insert(row(midnight))
            dao.insert(row(tomorrow - 1).copy(source = "TRANSLATION", providerId = "second-provider"))
            dao.insert(row(tomorrow))
            val summary = dao.getSummary(midnight, tomorrow, "%Y-%m-%d")
            assertEquals(UsageTotals(2, 40, 10, 20, 60, 0, 0), summary.totals)
            assertEquals(listOf(UsageBucket(day.toString(), 2, 40, 10, 20, 60, 0)), summary.buckets)
            assertEquals(setOf("CHAT", "TRANSLATION"), summary.sources.map { it.source }.toSet())
            assertEquals(setOf("provider", "second-provider"), summary.models.map { it.providerId }.toSet())
            assertEquals(4L, dao.observeLatestId().first())
            assertEquals(midnight - 1, dao.getFirstRecordTimestamp())
            assertEquals(UsageTotals(), dao.getSummary(tomorrow, tomorrow, "%Y-%m-%d").totals)
            val month = dao.getTotalsByBucket("%Y-%m", midnight, tomorrow).single()
            assertEquals("2026-10", month.bucket)
            assertEquals(2L, month.requestCount)
        }
    }

    @Test
    fun `unreported usage is separate from reported zero and unsuccessful requests`() = runBlocking {
        withDatabase { db ->
            val dao = db.usageRecordDao()
            // Ignore stray token values on an unreported row defensively; they are never real usage.
            dao.insert(row(midnight).copy(usageReported = false, outcome = "ERROR"))
            dao.insert(row(midnight).copy(promptTokens = 0, completionTokens = 0, cachedTokens = 0,
                totalTokens = 0, usageReported = true))
            dao.insert(row(midnight).copy(outcome = "CANCELLED"))
            dao.insert(row(midnight).copy(outcome = "ERROR"))
            val summary = dao.getSummary(midnight, tomorrow, "%Y-%m-%d")
            assertEquals(UsageTotals(4, 40, 10, 20, 60, 1, 3), summary.totals)
            assertEquals(4L, summary.buckets.single().requestCount)
            assertEquals(1L, summary.buckets.single().unreportedCount)
            assertEquals(1L, summary.models.single().unreportedCount)
            assertEquals(1L, summary.sources.single().unreportedCount)
            assertEquals(60L, summary.models.single().totalTokens)
            assertEquals(60L, summary.sources.single().totalTokens)
        }
    }

    @Test
    fun `model identity includes provider configured id and historical API name including unknowns`() = runBlocking {
        withDatabase { db ->
            val dao = db.usageRecordDao()
            val first = row(midnight)
            dao.insert(first)
            dao.insert(first) // retries and tool rounds remain independent requests
            dao.insert(first.copy(providerId = "other-provider"))
            dao.insert(first.copy(modelId = "other-id"))
            dao.insert(first.copy(modelName = "renamed-api"))
            dao.insert(first.copy(modelId = null))
            dao.insert(first.copy(modelId = first.modelName, modelName = null))
            dao.insert(first.copy(providerId = null, modelId = null, modelName = null))
            val models = dao.getSummary(0, Long.MAX_VALUE, "%Y-%m-%d").models
            assertEquals(7, models.size)
            assertEquals(2L, models.single { it.providerId == first.providerId &&
                it.modelId == first.modelId && it.modelName == first.modelName }.requestCount)
            assertEquals(1L, models.single { it.providerId == null && it.modelId == null && it.modelName == null }.requestCount)
            assertEquals(8L, models.sumOf { it.requestCount })
        }
    }

    @Test
    fun `long token sums retain provider total and survive parent deletion`() = runBlocking {
        withDatabase { db ->
            val dao = db.usageRecordDao()
            db.openHelper.writableDatabase.execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('chat','keep','[]',1,2)")
            repeat(2) {
                dao.insert(row(midnight).copy(promptTokens = Int.MAX_VALUE, completionTokens = Int.MAX_VALUE,
                    cachedTokens = Int.MAX_VALUE, totalTokens = Int.MAX_VALUE))
            }
            db.conversationDao().deleteById("chat")
            val totals = dao.getTotals()
            assertEquals(2L * Int.MAX_VALUE, totals.promptTokens)
            assertEquals(2L * Int.MAX_VALUE, totals.completionTokens)
            assertEquals(2L * Int.MAX_VALUE, totals.cachedTokens)
            assertEquals(2L * Int.MAX_VALUE, totals.totalTokens)
            assertEquals(2L, totals.requestCount)
            assertEquals(0, db.openHelper.writableDatabase.query("PRAGMA foreign_key_list(usage_record)").use { it.count })
            assertEquals(0, db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { it.count })
        }
    }

    @Test
    fun `latest id flow invalidates after a new request`() = runBlocking {
        withDatabase { db ->
            val dao = db.usageRecordDao()
            val observedEmpty = CompletableDeferred<Unit>()
            val next = async {
                withTimeout(5_000) {
                    dao.observeLatestId().first { id ->
                        if (id == 0L) observedEmpty.complete(Unit)
                        id > 0
                    }
                }
            }
            withTimeout(5_000) { observedEmpty.await() }
            dao.insert(row(midnight))
            assertEquals(1L, next.await())
        }
    }

    private fun row(time: Long) = UsageRecordEntity(
        createdAt = time, source = "CHAT", providerId = "provider", modelId = "model",
        modelName = "api-name-snapshot", assistantId = "assistant", conversationId = "chat",
        promptTokens = 20, completionTokens = 5, cachedTokens = 10, totalTokens = 30, usageReported = true,
    )

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try { block(db) } finally { db.close() }
    }
}
