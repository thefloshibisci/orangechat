package me.rerere.rikkahub.data.serein

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.MessageNode

@Entity(tableName = "serein_upload", foreignKeys = [ForeignKey(entity = ConversationEntity::class,
    parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId"), Index(value = ["destination", "status"])])
data class SereinUpload(
    @PrimaryKey val id: String,
    val assistantId: String,
    val conversationId: String,
    val nodeId: String,
    val messageId: String,
    val destination: String,
    val sourceHash: String,
    val payload: String,
    val status: String = "pending",
    val error: String = "",
    val queuedAt: Long = System.currentTimeMillis(),
)

data class SereinUploadCounts(val pending: Int = 0, val sent: Int = 0, val blocked: Int = 0, val retrying: Int = 0)

@Dao
interface SereinUploadDAO {
    @Query("""SELECT error FROM serein_upload WHERE destination=:destination
        AND status IN ('pending','blocked') AND error!='' ORDER BY queuedAt DESC,rowid DESC LIMIT 1""")
    fun latestError(destination: String): Flow<String?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(rows: List<SereinUpload>)
    @Query("SELECT * FROM serein_upload WHERE destination=:destination AND status='pending' ORDER BY queuedAt,rowid LIMIT 1")
    suspend fun next(destination: String): SereinUpload?
    @Query("SELECT * FROM message_node WHERE id=:id") suspend fun node(id: String): MessageNodeEntity?
    @Query("SELECT assistant_id FROM ConversationEntity WHERE id=:id") suspend fun owner(id: String): String?
    @Query("SELECT * FROM serein_upload WHERE id=:id") suspend fun read(id: String): SereinUpload?
    @Query("UPDATE serein_upload SET status=:status,error=:error,payload=CASE WHEN :clearBody THEN '' ELSE payload END WHERE id=:id AND status='pending'")
    suspend fun finish(id: String, status: String, error: String = "", clearBody: Boolean = false)
    @Query("""SELECT COALESCE(SUM(status='pending'),0) AS pending, COALESCE(SUM(status='sent'),0) AS sent,
        COALESCE(SUM(status='blocked'),0) AS blocked,
        COALESCE(SUM(status='pending' AND error!=''),0) AS retrying FROM serein_upload WHERE destination=:destination""")
    fun counts(destination: String): Flow<SereinUploadCounts>
    @Query("UPDATE serein_upload SET error='' WHERE destination=:destination AND status='pending'")
    suspend fun retry(destination: String)
}

/** Called only inside the changed-chat transaction; never reads history or invokes networking. */
internal suspend fun SereinUploadDAO.capture(config: SereinHookConfig, assistant: String, conversation: String,
    nodes: List<Pair<Int, MessageNode>>, origin: String) {
    if (origin != "live" || !config.uploadsFor(assistant)) return
    val start = runCatching { LocalDateTime.parse(config.uploadStartedAt) }.getOrNull() ?: return
    val destination = config.uploadDestination()
    val rows = nodes.mapNotNull { (_, node) ->
        val message = node.messages.getOrNull(node.selectIndex) ?: return@mapNotNull null
        if (message.createdAt < start) return@mapNotNull null
        val text = message.uploadText() ?: return@mapNotNull null
        val sourceId = "orangechat:$assistant:$conversation:${message.id}"
        val payload = buildJsonObject {
            put("source", "orangechat"); put("client", "orangechat")
            put("conversation_id", conversation); put("session_id", "orangechat:$assistant:$conversation")
            put("events", buildJsonArray { add(buildJsonObject {
                put("source_event_id", sourceId); put("role", message.role.name.lowercase())
                put("text", text); put("created_at", message.createdAt.toString())
                put("metadata", buildJsonObject { put("assistant_id", assistant); put("message_id", message.id.toString()) })
            }) })
        }.toString()
        val oversized = payload.toByteArray(Charsets.UTF_8).size > 262144
        SereinUpload(hookHash("$destination\u0000$sourceId"), assistant, conversation, node.id.toString(),
            message.id.toString(), destination, hookHash(text), if (oversized) "" else payload,
            status = if (oversized) "blocked" else "pending", error = if (oversized) "message_too_large" else "")
    }
    if (rows.isNotEmpty()) enqueue(rows)
}

internal fun me.rerere.ai.ui.UIMessage.uploadText(): String? {
    if (role == MessageRole.ASSISTANT && (finishedAt == null || getTools().isNotEmpty())) return null
    return rawArchiveText()
}
