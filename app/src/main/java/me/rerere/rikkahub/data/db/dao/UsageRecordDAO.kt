package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity

private const val USAGE_AGGREGATES =
    "COUNT(*) AS requestCount, " +
        "COALESCE(SUM(CASE WHEN usage_reported THEN prompt_tokens ELSE 0 END), 0) AS promptTokens, " +
        "COALESCE(SUM(CASE WHEN usage_reported THEN completion_tokens ELSE 0 END), 0) AS completionTokens, " +
        "COALESCE(SUM(CASE WHEN usage_reported THEN cached_tokens ELSE 0 END), 0) AS cachedTokens, " +
        "COALESCE(SUM(CASE WHEN usage_reported THEN total_tokens ELSE 0 END), 0) AS totalTokens, " +
        "COALESCE(SUM(CASE WHEN usage_reported = 0 THEN 1 ELSE 0 END), 0) AS unreportedCount "

@Dao
interface UsageRecordDAO {
    @Insert
    suspend fun insert(record: UsageRecordEntity)

    @Query("SELECT COALESCE(MAX(id), 0) FROM usage_record")
    fun observeLatestId(): Flow<Long>

    @Query("SELECT MIN(created_at) FROM usage_record")
    suspend fun getFirstRecordTimestamp(): Long?

    /** All totals and breakdowns share a single Room read transaction. The upper bound is exclusive. */
    @Transaction
    suspend fun getSummary(since: Long, until: Long, format: String): UsageSummary = UsageSummary(
        totals = getTotals(since, until),
        buckets = getTotalsByBucket(format, since, until),
        models = getTotalsByModel(since, until),
        sources = getTotalsBySource(since, until),
    )

    @Query("SELECT " + USAGE_AGGREGATES +
        ", COALESCE(SUM(CASE WHEN outcome IN ('ERROR', 'CANCELLED') THEN 1 ELSE 0 END), 0) AS failedCount " +
        "FROM usage_record WHERE created_at >= :since AND created_at < :until")
    suspend fun getTotals(since: Long = 0, until: Long = Long.MAX_VALUE): UsageTotals

    /** SQLite uses the device's local timezone; callers choose day/month/hour strftime formats. */
    @Query("SELECT strftime(:format, created_at / 1000.0, 'unixepoch', 'localtime') AS bucket, " +
        USAGE_AGGREGATES + "FROM usage_record WHERE created_at >= :since AND created_at < :until " +
        "GROUP BY bucket ORDER BY bucket")
    suspend fun getTotalsByBucket(format: String, since: Long, until: Long): List<UsageBucket>

    // Include the API name snapshot in the identity: renaming a model must not rewrite prior usage.
    @Query("SELECT provider_id AS providerId, model_id AS modelId, model_name AS modelName, " +
        USAGE_AGGREGATES + "FROM usage_record WHERE created_at >= :since AND created_at < :until " +
        "GROUP BY provider_id, model_id, model_name " +
        "ORDER BY totalTokens DESC, requestCount DESC, provider_id, model_id, model_name")
    suspend fun getTotalsByModel(since: Long, until: Long): List<ModelUsage>

    @Query("SELECT source, " + USAGE_AGGREGATES +
        "FROM usage_record WHERE created_at >= :since AND created_at < :until " +
        "GROUP BY source ORDER BY totalTokens DESC, requestCount DESC, source")
    suspend fun getTotalsBySource(since: Long, until: Long): List<SourceUsage>
}

data class UsageTotals(
    val requestCount: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val totalTokens: Long = 0,
    val unreportedCount: Long = 0,
    val failedCount: Long = 0,
)

data class UsageBucket(
    val bucket: String,
    val requestCount: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val totalTokens: Long,
    val unreportedCount: Long,
)

data class ModelUsage(
    val providerId: String?,
    val modelId: String?,
    val modelName: String?,
    val requestCount: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val totalTokens: Long,
    val unreportedCount: Long,
)

data class SourceUsage(
    val source: String,
    val requestCount: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val totalTokens: Long,
    val unreportedCount: Long,
)

data class UsageSummary(
    val totals: UsageTotals,
    val buckets: List<UsageBucket>,
    val models: List<ModelUsage>,
    val sources: List<SourceUsage>,
)
