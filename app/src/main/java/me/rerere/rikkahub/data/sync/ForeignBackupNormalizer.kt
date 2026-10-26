package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import me.rerere.rikkahub.data.db.AppDatabase
import java.io.File

/** Only the inspected sibling-fork v38 format is supported. Operates exclusively on staging. */
internal fun normalizeForeignBackup(
    context: Context,
    file: File,
    factory: SupportSQLiteOpenHelper.Factory,
) {
    require(file.canonicalFile != context.getDatabasePath("rikka_hub").canonicalFile)
    // Read the SQLite header; older/current OrangeChat files retain their ordinary Room path.
    val version = java.io.RandomAccessFile(file, "r").use { it.seek(60); it.readInt() }
    // A WAL may contain a newer header. The helper checks the effective version and identity again.
    if (version != 38) return
    // Historical native OrangeChat databases also used v38. A version alone cannot identify a fork.
    // Probe only this staged file and leave current/unknown schemas to Room validation.
    val probe = factory.create(SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(file.absolutePath).callback(object : SupportSQLiteOpenHelper.Callback(38) {
            override fun onCreate(db: SupportSQLiteDatabase) = error("Missing backup database")
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                error("Unexpected backup version: $oldVersion")
        }).build())
    val foreign = try {
        probe.writableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
            it.moveToFirst() && it.getString(0) == "11d8129bf72f997e35de727852c7d17d"
        }
    } finally { probe.close() }
    if (!foreign) return
    val templateFile = File.createTempFile("backup-schema-", ".db", context.cacheDir)
    val template = Room.databaseBuilder(context, AppDatabase::class.java, templateFile.absolutePath)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).openHelperFactory(factory).build()
    try {
        val target = template.openHelper.writableDatabase
        val definitions = listOf("ConversationEntity", "MemoryEntity").associateWith { name ->
            target.query("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use {
                check(it.moveToFirst()); it.getString(0)
            }
        }
        val helper = factory.create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(32) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    // Must happen BEFORE the helper's upgrade transaction; otherwise DROP cascades.
                    db.setForeignKeyConstraintsEnabled(false)
                }
                override fun onCreate(db: SupportSQLiteDatabase) = error("Missing backup database")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("Unexpected backup version: $oldVersion")
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    check(oldVersion == 38 && newVersion == 32) { "Unsupported foreign backup version" }
                    val hash = db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                        check(it.moveToFirst()); it.getString(0)
                    }
                    check(hash == "11d8129bf72f997e35de727852c7d17d") { "Unknown foreign backup schema" }
                    db.query("PRAGMA foreign_keys").use { check(it.moveToFirst() && it.getInt(0) == 0) }
                    definitions.forEach { (name, ddl) ->
                        val sequence = db.query("SELECT seq FROM sqlite_sequence WHERE name=?", arrayOf(name)).use {
                            if (it.moveToFirst()) it.getLong(0) else null
                        }
                        val sourceColumns = columns(db, name)
                        val targetColumns = columns(target, name)
                        val extra = sourceColumns - targetColumns
                        val allowed = if (name == "ConversationEntity") setOf("context_compaction") else setOf(
                            "source_kind", "source_conversation_id", "source_message_id", "revision", "created_at", "updated_at",
                        )
                        check(extra == allowed) { "Unknown columns in foreign $name" }
                        // Preserve ALL original values, including unsupported fork-specific metadata.
                        // These archive tables remain in full exports, but are not active OrangeChat state.
                        db.execSQL("CREATE TABLE `orangechat_import_v38_$name` AS SELECT * FROM `$name`")
                        val temporary = "orangechat_import_new_$name"
                        db.execSQL(ddl.replace("`$name`", "`$temporary`"))
                        val common = targetColumns.intersect(sourceColumns).joinToString(",") { "`$it`" }
                        db.execSQL("INSERT INTO `$temporary` ($common) SELECT $common FROM `$name`")
                        db.execSQL("DROP TABLE `$name`")
                        db.execSQL("ALTER TABLE `$temporary` RENAME TO `$name`")
                        if (sequence != null) db.execSQL(
                            "UPDATE sqlite_sequence SET seq=MAX(seq, ?) WHERE name=?", arrayOf<Any>(sequence, name),
                        )
                        target.query("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL", arrayOf(name)).use {
                            while (it.moveToNext()) db.execSQL(it.getString(0))
                        }
                    }
                    db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "Foreign backup has broken references" } }
                    // Keep the old identity. The ordinary 32 -> 33 migration MUST pass full Room
                    // validation before Room writes the new identity; never bypass validation.
                }
            }).build())
        try { helper.writableDatabase } finally { helper.close() }
    } finally {
        template.close()
        context.deleteDatabase(templateFile.absolutePath)
    }
}

private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        buildSet {
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(name))
        }
    }
