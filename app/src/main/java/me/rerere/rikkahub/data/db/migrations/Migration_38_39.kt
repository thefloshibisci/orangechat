package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Foreign-backup normalization may already use the current table template before
        // applying the migration chain. Keep its existing config and let Room validate it.
        val hasConfig = db.query("PRAGMA table_info(`ConversationEntity`)").use { columns ->
            val name = columns.getColumnIndexOrThrow("name")
            var found = false
            while (columns.moveToNext()) {
                if (columns.getString(name) == "conversation_config") found = true
            }
            found
        }
        if (!hasConfig) {
            db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `conversation_config` TEXT NOT NULL DEFAULT ''")
        }
    }
}
