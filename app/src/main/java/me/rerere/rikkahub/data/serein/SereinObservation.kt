package me.rerere.rikkahub.data.serein

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import java.time.Instant

@Entity(tableName = "serein_observation_outbox", foreignKeys = [ForeignKey(
    entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
    onDelete = ForeignKey.CASCADE)], indices = [Index("conversationId"), Index("destination")])
data class SereinObservation(
    @PrimaryKey val id: String,
    val assistantId: String,
    val conversationId: String,
    val destination: String,
    val payload: String,
    val createdAt: Long,
    val error: String = "",
)

@Dao
interface SereinObservationDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(row: SereinObservation)
    @Query("SELECT * FROM serein_observation_outbox WHERE destination=:destination ORDER BY createdAt,id LIMIT 1")
    suspend fun next(destination: String): SereinObservation?
    @Query("DELETE FROM serein_observation_outbox WHERE id=:id") suspend fun remove(id: String)
    @Query("UPDATE serein_observation_outbox SET error=:error WHERE id=:id") suspend fun fail(id: String, error: String)
    @Query("SELECT COUNT(*) FROM serein_observation_outbox WHERE destination=:destination")
    fun pending(destination: String): Flow<Int>
    @Query("SELECT error FROM serein_observation_outbox WHERE destination=:destination AND error!='' ORDER BY createdAt DESC LIMIT 1")
    fun latestError(destination: String): Flow<String?>
}

/** No reply bodies, credentials, tool output or hidden reasoning in the observation. */
internal fun observationPayload(id: String, config: SereinHookConfig, conversation: String,
    query: String, result: HookRecall, delivered: List<String>, requestStatus: String, elapsedMs: Long,
    createdAt: Long): String = buildJsonObject {
    val ids = if (requestStatus == "completed" && result.status == "ready") delivered.filter { it in result.ids }.distinct() else emptyList()
    put("receipt_id", id); put("window_id", "orangechat:${config.assistantId}:$conversation")
    put("delivered_ids", JsonArray(ids.map(::JsonPrimitive)))
    put("observation", buildJsonObject {
        put("version", 1); put("client", "orangechat"); put("request_status", requestStatus)
        put("recall_status", result.status); put("created_at", Instant.ofEpochMilli(createdAt).toString())
        put("elapsed_ms", elapsedMs.coerceIn(0, 86_400_000)); put("query", query.take(2000))
        put("route", result.diagnostics["route"] ?: JsonPrimitive(""))
        put("reason", result.diagnostics["reason"] ?: JsonPrimitive(""))
        val details = (result.diagnostics["candidates"] as? JsonArray).orEmpty()
        put("candidates", JsonArray(result.ids.map { id ->
            details.filterIsInstance<JsonObject>().firstOrNull { it["id"]?.jsonPrimitive?.content == id }
                ?: buildJsonObject { put("id", id); put("title", id); put("source_kind", id.substringBefore(':')) }
        }))
    })
}.toString()
