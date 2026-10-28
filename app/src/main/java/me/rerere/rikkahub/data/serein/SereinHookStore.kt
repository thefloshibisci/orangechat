package me.rerere.rikkahub.data.serein

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ConversationEntity

@Entity(tableName = "serein_hook_snapshot", primaryKeys = ["conversationId", "userMessageId"],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class SereinHookSnapshot(
    val conversationId: String,
    val userMessageId: String,
    val fingerprint: String,
    val context: String,
    val memoryIds: String,
    val status: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'{}'") val diagnostics: String = "{}",
)

@Entity(tableName = "serein_hook_delivery", primaryKeys = ["conversationId", "receiptId"],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class SereinHookDelivery(
    val conversationId: String,
    val receiptId: String,
    val configFingerprint: String,
    val memoryIds: String,
    val deliveredAt: Long,
)

@Dao
interface SereinHookDAO {
    // Only status/IDs reach the settings UI; do not load recalled text or user queries.
    @Query("""SELECT s.status,s.memoryIds,s.createdAt FROM serein_hook_snapshot s
        JOIN ConversationEntity c ON c.id=s.conversationId
        WHERE c.assistant_id=:assistant AND s.fingerprint LIKE :fingerprint || ':%'
        ORDER BY s.createdAt DESC,s.rowid DESC LIMIT 1""")
    fun latestStatus(assistant: String, fingerprint: String): Flow<SereinRecallStatus?>
    @Query("SELECT assistant_id FROM ConversationEntity WHERE id=:conversation")
    suspend fun owner(conversation: String): String?
    @Query("SELECT * FROM serein_hook_snapshot WHERE conversationId=:conversation AND userMessageId=:user")
    suspend fun snapshot(conversation: String, user: String): SereinHookSnapshot?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(snapshot: SereinHookSnapshot)
    @Query("""DELETE FROM serein_hook_snapshot WHERE conversationId=:conversation AND userMessageId NOT IN
        (SELECT userMessageId FROM serein_hook_snapshot WHERE conversationId=:conversation ORDER BY createdAt DESC, userMessageId DESC LIMIT 64)""")
    suspend fun pruneSnapshots(conversation: String)
    @Query("""SELECT * FROM serein_hook_delivery WHERE conversationId=:conversation AND configFingerprint=:config
        ORDER BY deliveredAt DESC, rowid DESC LIMIT 5""")
    suspend fun recent(conversation: String, config: String): List<SereinHookDelivery>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun record(delivery: SereinHookDelivery)
    @Query("""DELETE FROM serein_hook_delivery WHERE conversationId=:conversation AND receiptId NOT IN
        (SELECT receiptId FROM serein_hook_delivery WHERE conversationId=:conversation ORDER BY deliveredAt DESC, rowid DESC LIMIT 50)""")
    suspend fun pruneDeliveries(conversation: String)
    @Query("DELETE FROM serein_hook_snapshot") suspend fun clearSnapshots()
    @Query("DELETE FROM serein_hook_delivery") suspend fun clearDeliveries()
}

data class SereinRecallStatus(val status: String, val memoryIds: String, val createdAt: Long)
