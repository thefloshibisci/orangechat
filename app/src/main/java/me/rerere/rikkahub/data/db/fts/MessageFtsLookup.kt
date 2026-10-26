package me.rerere.rikkahub.data.db.fts

import androidx.sqlite.db.SupportSQLiteDatabase

/** Auxiliary index only: existing FTS rows, tokenization and chat history remain intact. */
internal fun ensureMessageFtsLookup(db: SupportSQLiteDatabase) {
    val exists = db.query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'message_fts_lookup'"
    ).use { it.moveToFirst() }
    if (exists) return
    db.beginTransaction()
    try {
        db.execSQL("CREATE TABLE message_fts_lookup(fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT NOT NULL, node_id TEXT NOT NULL)")
        db.execSQL("CREATE INDEX index_message_fts_lookup_conversation ON message_fts_lookup(conversation_id)")
        db.execSQL("CREATE INDEX index_message_fts_lookup_node ON message_fts_lookup(conversation_id, node_id)")
        db.execSQL("INSERT INTO message_fts_lookup SELECT rowid, conversation_id, node_id FROM message_fts")
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

internal fun deleteMessageFtsRows(db: SupportSQLiteDatabase, conversationId: String, nodeId: String? = null) {
    val predicate = if (nodeId == null) "conversation_id = ?" else "conversation_id = ? AND node_id = ?"
    val arguments = if (nodeId == null) arrayOf(conversationId) else arrayOf(conversationId, nodeId)
    db.execSQL(
        "DELETE FROM message_fts WHERE rowid IN (SELECT fts_rowid FROM message_fts_lookup WHERE $predicate)",
        arguments,
    )
    db.execSQL("DELETE FROM message_fts_lookup WHERE $predicate", arguments)
}
