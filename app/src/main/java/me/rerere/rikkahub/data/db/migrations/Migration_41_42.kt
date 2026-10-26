package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing workspaces keep their local files, status and approval policy. */
val Migration_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = db.query("PRAGMA table_info(workspaces)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        if ("type" !in columns) db.execSQL("ALTER TABLE workspaces ADD COLUMN type TEXT NOT NULL DEFAULT 'LOCAL'")
        if ("ssh_config" !in columns) db.execSQL("ALTER TABLE workspaces ADD COLUMN ssh_config TEXT")
        // Room validates pre-existing columns too; an incompatible foreign schema fails without data loss.
    }
}
