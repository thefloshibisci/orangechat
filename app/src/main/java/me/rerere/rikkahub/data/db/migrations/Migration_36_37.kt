package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS serein_hook_snapshot (
            conversationId TEXT NOT NULL, userMessageId TEXT NOT NULL, fingerprint TEXT NOT NULL,
            context TEXT NOT NULL, memoryIds TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL,
            PRIMARY KEY(conversationId,userMessageId),
            FOREIGN KEY(conversationId) REFERENCES ConversationEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS serein_hook_delivery (
            conversationId TEXT NOT NULL, receiptId TEXT NOT NULL, configFingerprint TEXT NOT NULL,
            memoryIds TEXT NOT NULL, deliveredAt INTEGER NOT NULL, PRIMARY KEY(conversationId,receiptId),
            FOREIGN KEY(conversationId) REFERENCES ConversationEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    }
}
