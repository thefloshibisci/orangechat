package me.rerere.rikkahub.data.serein

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

/** A pending stream is mutable; complete/interrupted revisions freeze their original text. */
@Entity(
    tableName = "serein_raw_revision",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId"), Index(value = ["assistantId", "conversationId", "messageId", "revision"], unique = true)],
)
data class RawArchiveRevision(
    @PrimaryKey val id: String,
    val assistantId: String,
    val conversationId: String,
    val messageId: String,
    val revision: Int,
    val role: String,
    val text: String,
    // Preserve the source's wall-clock value, without inventing a historical timezone.
    val createdAt: String,
    val finishedAt: String?,
    val recordedAt: Long,
    val origin: String,
    val state: String,
)

/** Branch visibility is separate from original-text history. Removed nodes lose heads, not history. */
@Entity(
    tableName = "serein_raw_head",
    primaryKeys = ["assistantId", "conversationId", "nodeId", "messageId"],
    foreignKeys = [
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RawArchiveRevision::class, parentColumns = ["id"], childColumns = ["revisionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageNodeEntity::class, parentColumns = ["id"], childColumns = ["nodeId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("conversationId"), Index("revisionId"), Index("nodeId"), Index(value = ["assistantId", "conversationId", "nodeIndex"])],
)
data class RawArchiveHead(
    val assistantId: String,
    val conversationId: String,
    val nodeId: String,
    val messageId: String,
    val revisionId: String,
    val nodeIndex: Int,
    val variantIndex: Int,
    val selected: Boolean,
)
