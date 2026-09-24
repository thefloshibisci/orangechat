/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.AppDatabase
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "DatabaseBackupCoordinator"

/**
 * Keeps database backup and restore outside the live Room file lifecycle.
 * Restore is staged and applied before Room is created on the next process start.
 */
class DatabaseBackupCoordinator(
    private val context: Context,
    private val database: AppDatabase,
) {
    private val pendingDir: File
        get() = File(context.filesDir, PENDING_DIR)

    fun createConsistentSnapshot(): File {
        val snapshot = File(
            context.cacheDir,
            "db_snapshot_${LocalDateTime.now().format(FILE_TIME)}.db"
        )
        snapshot.delete()
        snapshot.parentFile?.mkdirs()

        val escapedPath = snapshot.absolutePath.replace("'", "''")
        database.openHelper.writableDatabase.execSQL("VACUUM INTO '$escapedPath'")
        check(snapshot.isFile && snapshot.length() > 0) {
            "Database snapshot was not created"
        }
        return snapshot
    }

    fun beginRestore() {
        pendingDir.deleteRecursively()
        check(pendingDir.mkdirs()) { "Unable to create database restore staging directory" }
    }

    fun stageRestorePart(entryName: String, input: InputStream) {
        val targetName = when (entryName) {
            "rikka_hub.db" -> DB_FILE
            "rikka_hub-wal" -> WAL_FILE
            "rikka_hub-shm" -> SHM_FILE
            else -> throw IllegalArgumentException("Unsupported database entry: $entryName")
        }
        if (!pendingDir.isDirectory) beginRestore()
        File(pendingDir, targetName).outputStream().buffered().use { output ->
            input.copyTo(output)
            output.flush()
        }
    }

    fun commitStagedRestore() {
        val stagedDb = File(pendingDir, DB_FILE)
        if (!stagedDb.isFile || stagedDb.length() == 0L) return
        validateDatabase(stagedDb)
        File(pendingDir, READY_MARKER).writeText("ready")
    }

    private fun validateDatabase(file: File) {
        // Backups contain FTS5 tables using the same native tokenizer as the live Room database.
        val configuration = SQLiteDatabaseConfiguration(file.path, SQLiteDatabase.OPEN_READONLY).apply {
            customExtensions.add(SQLiteCustomExtension(context.applicationInfo.nativeLibraryDir + "/libsimple", null))
        }
        val db = SQLiteDatabase.openDatabase(configuration, null, null)
        try {
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "Database integrity check failed"
                }
            }
        } finally {
            db.close()
        }
    }

    companion object {
        private const val PENDING_DIR = "pending_database_restore"
        private const val READY_MARKER = ".ready"
        private const val DB_FILE = "rikka_hub"
        private const val WAL_FILE = "rikka_hub-wal"
        private const val SHM_FILE = "rikka_hub-shm"
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

        /** Apply a validated restore before Koin/Room opens the database. */
        fun applyPendingRestore(context: Context) {
            val pendingDir = File(context.filesDir, PENDING_DIR)
            val marker = File(pendingDir, READY_MARKER)
            val stagedDb = File(pendingDir, DB_FILE)
            if (!marker.isFile || !stagedDb.isFile) return

            val databaseDir = context.getDatabasePath(DB_FILE).parentFile ?: return
            databaseDir.mkdirs()
            val backupDir = File(databaseDir, ".rikka_hub.restore-backup")

            val targets = listOf(DB_FILE, WAL_FILE, SHM_FILE)
            try {
                DatabaseRestoreFiles.replace(pendingDir, databaseDir, backupDir, targets)
                marker.delete()
                pendingDir.deleteRecursively()
                backupDir.deleteRecursively()
                Log.i(TAG, "Applied staged database restore")
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to apply staged database restore; recovery errors are suppressed", error)
                throw error
            }
        }
    }
}
