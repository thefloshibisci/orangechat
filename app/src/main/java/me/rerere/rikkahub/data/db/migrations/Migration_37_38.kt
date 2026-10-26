package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS serein_upload (
            id TEXT NOT NULL PRIMARY KEY, assistantId TEXT NOT NULL, conversationId TEXT NOT NULL,
            nodeId TEXT NOT NULL, messageId TEXT NOT NULL, destination TEXT NOT NULL,
            sourceHash TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL, error TEXT NOT NULL,
            queuedAt INTEGER NOT NULL,
            FOREIGN KEY(conversationId) REFERENCES ConversationEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_serein_upload_conversationId ON serein_upload(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_serein_upload_destination_status ON serein_upload(destination,status)")
    }
}
