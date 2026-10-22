package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.appSQLiteOpenHelperFactory
import java.io.File

/** Upgrade and open only the staged copy, before any live files or preferences are replaced. */
internal fun preflightBackupDatabase(
    context: Context,
    file: File,
    factory: SupportSQLiteOpenHelper.Factory = appSQLiteOpenHelperFactory(context),
) {
    require(file.isFile && file.length() > 0) { "Backup database is missing or empty" }
    require(file.canonicalFile != context.getDatabasePath("rikka_hub").canonicalFile) {
        "Backup preflight must not operate on the live database"
    }
    val room = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .addMigrations(*APP_DATABASE_MIGRATIONS)
        .openHelperFactory(factory)
        .build()
    try {
        normalizeForeignBackup(context, file, factory)
        // Room actually runs its migration path, validates every entity, and checks its identity here.
        // TRUNCATE incorporates an incoming WAL and leaves a self-contained staged database on close.
        room.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                "Database integrity check failed"
            }
        }
    } catch (error: Exception) {
        throw IllegalStateException("备份数据库与橘瓣不兼容，未替换当前数据。\n${error.message}", error)
    } finally {
        room.close()
    }
}
