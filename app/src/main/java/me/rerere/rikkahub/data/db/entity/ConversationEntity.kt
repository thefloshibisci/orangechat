/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
    @ColumnInfo("folder_id", defaultValue = "")
    val folderId: String = "",
    @ColumnInfo("compression_summaries", defaultValue = "'[]'")
    val compressionSummaries: String = "[]",
    @ColumnInfo("compressed_node_ids", defaultValue = "'[]'")
    val compressedNodeIds: String = "[]",
    @ColumnInfo("compression_revision", defaultValue = "0")
    val compressionRevision: Long = 0,
    @ColumnInfo("send_compressed_summary", defaultValue = "1")
    val sendCompressedSummary: Boolean = true,
    // Keep the previous test build's column: rebuilding this parent table can cascade-delete message_node.
    // Schema32 migrates its contents into compression_summaries, which is the canonical representation.
    @ColumnInfo("compressed_summary", defaultValue = "''")
    val legacyCompressedSummary: String = "",
    @ColumnInfo("compression_history", defaultValue = "'[]'")
    val compressionHistory: String = "[]",
    @ColumnInfo("conversation_config", defaultValue = "''")
    val config: String = "",
)
