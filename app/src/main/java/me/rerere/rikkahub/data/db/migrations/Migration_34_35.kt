package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS serein_raw_revision (
                id TEXT NOT NULL PRIMARY KEY, assistantId TEXT NOT NULL, conversationId TEXT NOT NULL,
                messageId TEXT NOT NULL, revision INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL,
                createdAt TEXT NOT NULL, finishedAt TEXT, recordedAt INTEGER NOT NULL, origin TEXT NOT NULL, state TEXT NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES ConversationEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_serein_raw_revision_conversationId ON serein_raw_revision(conversationId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_serein_raw_revision_assistantId_conversationId_messageId_revision ON serein_raw_revision(assistantId,conversationId,messageId,revision)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS serein_raw_head (
                assistantId TEXT NOT NULL, conversationId TEXT NOT NULL, nodeId TEXT NOT NULL, messageId TEXT NOT NULL,
                revisionId TEXT NOT NULL, nodeIndex INTEGER NOT NULL, variantIndex INTEGER NOT NULL, selected INTEGER NOT NULL,
                PRIMARY KEY(assistantId,conversationId,nodeId,messageId),
                FOREIGN KEY(conversationId) REFERENCES ConversationEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(revisionId) REFERENCES serein_raw_revision(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(nodeId) REFERENCES message_node(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_serein_raw_head_conversationId ON serein_raw_head(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_serein_raw_head_revisionId ON serein_raw_head(revisionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_serein_raw_head_nodeId ON serein_raw_head(nodeId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_serein_raw_head_assistantId_conversationId_nodeIndex ON serein_raw_head(assistantId,conversationId,nodeIndex)")
    }
}
