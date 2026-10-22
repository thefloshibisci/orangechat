package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable

/** Restore whole conversations: skip existing IDs, or replace them completely. */
@Serializable
enum class ChatHistoryRestoreMode { INCREMENTAL, OVERWRITE_MATCHING }
