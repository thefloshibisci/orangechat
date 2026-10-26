package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One real provider request, including each retry and tool round. No message contents or credentials. */
@Entity(
    tableName = "usage_record",
    indices = [Index(value = ["created_at"]), Index(value = ["conversation_id", "source", "id"])],
)
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("provider_id")
    val providerId: String? = null,
    @ColumnInfo("model_id")
    val modelId: String? = null,
    // Keep the API name at request time, even after a model/provider is edited or deleted.
    @ColumnInfo("model_name")
    val modelName: String? = null,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("prompt_tokens")
    val promptTokens: Int = 0,
    @ColumnInfo("completion_tokens")
    val completionTokens: Int = 0,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Int = 0,
    @ColumnInfo("total_tokens")
    val totalTokens: Int = 0,
    @ColumnInfo("usage_reported")
    val usageReported: Boolean = false,
    @ColumnInfo("outcome")
    val outcome: String = "SUCCESS",
)
