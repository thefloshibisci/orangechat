package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Both previously distributed compression builds used version31 with different schemas. */
object Migration_31_32 : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) = migrateCompressionSchema(db)
}

/** Go directly from the uncompressed schema, without depending on which version31 was installed. */
object Migration_30_32 : Migration(30, 32) {
    override fun migrate(db: SupportSQLiteDatabase) = migrateCompressionSchema(db)
}

/** A sibling fork can use version 32 without OrangeChat's compression columns. */
object Migration_32_33 : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        migrateCompressionSchema(db)
        val hasHistory = db.query("PRAGMA table_info(`ConversationEntity`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(name) == "compression_history") found = true
            found
        }
        if (!hasHistory) db.execSQL(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `compression_history` TEXT NOT NULL DEFAULT '[]'"
        )
        val workflowColumns = db.query("PRAGMA table_info(`workflow_runs`)").use { cursor ->
            buildSet {
                val name = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
        }
        for (column in listOf("outputSummary", "stepsJson")) {
            if (column !in workflowColumns) db.execSQL("ALTER TABLE `workflow_runs` ADD COLUMN `$column` TEXT")
        }
        // Keep strict Room schema validation. Unknown fork differences are not silently reset.
    }
}

private fun migrateCompressionSchema(db: SupportSQLiteDatabase) {
    val columns = db.query("PRAGMA table_info(`ConversationEntity`)").use { cursor ->
        buildSet {
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(name))
        }
    }
    val definitions = linkedMapOf(
        "compression_summaries" to "TEXT NOT NULL DEFAULT '[]'",
        "compressed_node_ids" to "TEXT NOT NULL DEFAULT '[]'",
        "compression_revision" to "INTEGER NOT NULL DEFAULT 0",
        "send_compressed_summary" to "INTEGER NOT NULL DEFAULT 1",
        "compressed_summary" to "TEXT NOT NULL DEFAULT ''",
    )
    definitions.forEach { (column, definition) ->
        if (column !in columns) {
            db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `$column` $definition")
        }
    }

    // Only the legacy variant needs conversion. Do not overwrite v2 edits, exclusions or the master switch.
    if ("compressed_summary" in columns && "compression_summaries" !in columns) {
        val summaries = db.query("SELECT `id`, `compressed_summary` FROM `ConversationEntity`").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val text = cursor.getString(1)
                    if (text.isNotEmpty()) add(id to text)
                }
            }
        }
        summaries.forEach { (conversationId, text) ->
            val summaryId = UUID.nameUUIDFromBytes("legacy-compression:$conversationId".toByteArray(Charsets.UTF_8))
            val json = JsonArray(listOf(buildJsonObject {
                put("id", summaryId.toString())
                put("text", text)
                put("sendToModel", true)
            })).toString()
            db.execSQL(
                "UPDATE `ConversationEntity` SET `compression_summaries` = ? WHERE `id` = ?",
                arrayOf(json, conversationId),
            )
        }
    }
    // Room validates the full schema and updates its identity in the upgrade transaction.
    // Never delete/recreate ConversationEntity: message_node references it with ON DELETE CASCADE.
}
