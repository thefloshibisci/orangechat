package me.rerere.rikkahub.utils

import android.content.Context
import kotlin.uuid.Uuid

/**
 * Foreground visibility receipts for individual message IDs. A timestamp watermark would mark
 * skipped history and unseen regenerated branches as read merely because they are older.
 */
internal object ChatReadMarks {
    private const val PREFS = "chat_read_marks_v2"

    fun get(context: Context, conversationId: Uuid): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(conversationId.toString(), emptySet()).orEmpty().toSet()

    fun record(context: Context, conversationId: Uuid, messageIds: Set<String>): Set<String> {
        val existing = get(context, conversationId)
        val combined = existing + messageIds
        if (combined != existing) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(conversationId.toString(), combined).apply()
        }
        return combined
    }

    fun delete(context: Context, conversationId: Uuid) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(conversationId.toString()).apply()
        context.getSharedPreferences("chat_read_marks", Context.MODE_PRIVATE).edit()
            .remove(conversationId.toString()).apply()
    }
}
