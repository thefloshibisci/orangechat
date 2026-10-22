package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add message reactions while accepting the equivalent table already present in fork v38 backups. */
object Migration_33_34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) = ensureMessageReactionSchema(db)
}

internal fun ensureMessageReactionSchema(db: SupportSQLiteDatabase) {
    val columns = tableColumns(db, "message_reaction")
    val foreignKey = db.query("PRAGMA foreign_key_list(`message_reaction`)").use { cursor ->
        if (!cursor.moveToFirst()) null else ForeignKeyShape(
            table = cursor.getString(cursor.getColumnIndexOrThrow("table")),
            from = cursor.getString(cursor.getColumnIndexOrThrow("from")),
            to = cursor.getString(cursor.getColumnIndexOrThrow("to")),
            onDelete = cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
        )
    }
    val expected = setOf("id", "conversationId", "messageId", "actor", "emoji", "quote", "createdAt")
    val validExisting = columns == expected &&
        foreignKey == ForeignKeyShape("ConversationEntity", "conversationId", "id", "CASCADE")

    if (columns.isEmpty()) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_reaction` (
                `id` TEXT NOT NULL,
                `conversationId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `actor` TEXT NOT NULL,
                `emoji` TEXT NOT NULL,
                `quote` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversationId`) REFERENCES `ConversationEntity`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
    } else if (!validExisting) {
        val temporary = "message_reaction_v34"
        db.execSQL("DROP TABLE IF EXISTS `$temporary`")
        db.execSQL(
            """
            CREATE TABLE `$temporary` (
                `id` TEXT NOT NULL,
                `conversationId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `actor` TEXT NOT NULL,
                `emoji` TEXT NOT NULL,
                `quote` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversationId`) REFERENCES `ConversationEntity`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        val common = expected.intersect(columns).joinToString(",") { "`$it`" }
        if (common.isNotEmpty()) {
            db.execSQL("INSERT OR IGNORE INTO `$temporary` ($common) SELECT $common FROM `message_reaction`")
        }
        db.execSQL("DROP TABLE `message_reaction`")
        db.execSQL("ALTER TABLE `$temporary` RENAME TO `message_reaction`")
    }
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_message_reaction_conversationId` " +
            "ON `message_reaction` (`conversationId`)"
    )
}

private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> =
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        buildSet {
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(name))
        }
    }

private data class ForeignKeyShape(
    val table: String,
    val from: String,
    val to: String,
    val onDelete: String,
)
