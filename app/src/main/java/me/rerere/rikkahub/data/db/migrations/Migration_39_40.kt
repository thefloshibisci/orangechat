package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Normalized backups can have current tables already. Preserve them, then let Room validate.
        db.execSQL("""CREATE TABLE IF NOT EXISTS `usage_record` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `created_at` INTEGER NOT NULL, `source` TEXT NOT NULL,
            `provider_id` TEXT, `model_id` TEXT, `model_name` TEXT,
            `assistant_id` TEXT, `conversation_id` TEXT,
            `prompt_tokens` INTEGER NOT NULL, `completion_tokens` INTEGER NOT NULL,
            `cached_tokens` INTEGER NOT NULL, `total_tokens` INTEGER NOT NULL,
            `usage_reported` INTEGER NOT NULL, `outcome` TEXT NOT NULL)""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` ON `usage_record` (`created_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_conversation_id_source_id` ON `usage_record` (`conversation_id`, `source`, `id`)")
    }
}
