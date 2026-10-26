package me.rerere.rikkahub.data.serein

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface RawArchiveDAO {
    @Insert
    suspend fun insertRevision(revision: RawArchiveRevision)

    @Insert
    suspend fun insertRevisions(revisions: List<RawArchiveRevision>)

    @Query("SELECT * FROM serein_raw_revision WHERE assistantId = :assistantId AND conversationId = :conversationId")
    suspend fun allRevisions(assistantId: String, conversationId: String): List<RawArchiveRevision>

    @Query("SELECT * FROM serein_raw_head WHERE assistantId = :assistantId AND conversationId = :conversationId")
    suspend fun allHeads(assistantId: String, conversationId: String): List<RawArchiveHead>

    @Update
    suspend fun updatePendingRevision(revision: RawArchiveRevision)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHeads(heads: List<RawArchiveHead>)

    @Query("SELECT * FROM serein_raw_head WHERE assistantId = :assistantId AND conversationId = :conversationId AND nodeId = :nodeId")
    suspend fun heads(assistantId: String, conversationId: String, nodeId: String): List<RawArchiveHead>

    @Query("SELECT * FROM serein_raw_revision WHERE assistantId = :assistantId AND conversationId = :conversationId AND messageId = :messageId ORDER BY revision DESC LIMIT 1")
    suspend fun latest(assistantId: String, conversationId: String, messageId: String): RawArchiveRevision?

    @Query("SELECT r.* FROM serein_raw_revision r JOIN ConversationEntity c ON c.id = r.conversationId AND c.assistant_id = r.assistantId WHERE r.assistantId = :assistantId AND r.conversationId = :conversationId AND r.id = :id")
    suspend fun revision(assistantId: String, conversationId: String, id: String): RawArchiveRevision?

    @Query("DELETE FROM serein_raw_head WHERE assistantId = :assistantId AND conversationId = :conversationId AND nodeId IN (:nodeIds)")
    suspend fun removeHeads(assistantId: String, conversationId: String, nodeIds: List<String>)

    @Query("SELECT DISTINCT nodeId FROM serein_raw_head WHERE assistantId = :assistantId AND conversationId = :conversationId")
    suspend fun nodeIds(assistantId: String, conversationId: String): List<String>

    @Query("DELETE FROM serein_raw_head WHERE conversationId = :conversationId AND assistantId != :assistantId")
    suspend fun removeOtherHeads(assistantId: String, conversationId: String)

    @Query("DELETE FROM serein_raw_revision WHERE conversationId = :conversationId AND assistantId != :assistantId")
    suspend fun removeOtherRevisions(assistantId: String, conversationId: String)

    @Query("UPDATE serein_raw_revision SET state = 'interrupted' WHERE assistantId = :assistantId AND conversationId = :conversationId AND messageId IN (:messageIds) AND state = 'pending'")
    suspend fun interruptPending(assistantId: String, conversationId: String, messageIds: List<String>)

    @Query("""
        SELECT r.* FROM serein_raw_revision r
        JOIN serein_raw_head h ON h.revisionId = r.id AND h.assistantId = r.assistantId AND h.conversationId = r.conversationId
        JOIN ConversationEntity c ON c.id = r.conversationId AND c.assistant_id = r.assistantId
        WHERE r.assistantId = :assistantId AND h.selected = 1 AND r.text LIKE :pattern ESCAPE '\'
        ORDER BY r.createdAt DESC, h.nodeIndex DESC, r.recordedAt DESC LIMIT :limit
    """)
    suspend fun search(assistantId: String, pattern: String, limit: Int): List<RawArchiveRevision>

    @Query("""
        SELECT r.* FROM serein_raw_revision r
        JOIN ConversationEntity c ON c.id = r.conversationId AND c.assistant_id = r.assistantId
        WHERE r.assistantId = :assistantId AND r.conversationId = :conversationId AND r.messageId = :messageId
        AND EXISTS (SELECT 1 FROM serein_raw_head h WHERE h.assistantId = r.assistantId AND h.conversationId = r.conversationId AND h.messageId = r.messageId)
        ORDER BY r.revision DESC LIMIT :limit
    """)
    suspend fun history(assistantId: String, conversationId: String, messageId: String, limit: Int): List<RawArchiveRevision>
}
