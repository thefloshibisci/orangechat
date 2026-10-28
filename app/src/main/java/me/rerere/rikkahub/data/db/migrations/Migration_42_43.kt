package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = db.query("PRAGMA table_info(serein_hook_snapshot)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        if ("diagnostics" !in columns) db.execSQL("ALTER TABLE serein_hook_snapshot ADD COLUMN diagnostics TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("""CREATE TABLE IF NOT EXISTS serein_observation_outbox (
            id TEXT NOT NULL PRIMARY KEY, assistantId TEXT NOT NULL, conversationId TEXT NOT NULL,
            destination TEXT NOT NULL, payload TEXT NOT NULL, createdAt INTEGER NOT NULL, error TEXT NOT NULL,
            FOREIGN KEY(conversationId) REFERENCES ConversationEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_serein_observation_outbox_conversationId ON serein_observation_outbox(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_serein_observation_outbox_destination ON serein_observation_outbox(destination)")
    }
}
