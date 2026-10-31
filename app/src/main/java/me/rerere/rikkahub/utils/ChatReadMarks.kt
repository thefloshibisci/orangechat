package me.rerere.rikkahub.utils

import android.content.Context
import kotlin.uuid.Uuid

/**
 * Local "seen up to" marks per conversation: an assistant message counts as read once it has been
 * on screen while the chat was in the foreground. Stores the newest seen createdAt (epoch ms).
 */
internal object ChatReadMarks {
    private const val PREFS = "chat_read_marks"

    fun get(context: Context, conversationId: Uuid): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(conversationId.toString(), 0L)

    fun save(context: Context, conversationId: Uuid, seenAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(conversationId.toString(), seenAt).apply()
    }

    fun delete(context: Context, conversationId: Uuid) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(conversationId.toString()).apply()
    }
}
