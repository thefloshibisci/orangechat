package me.rerere.rikkahub.data.sync

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseRestoreFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val names = listOf("rikka_hub", "rikka_hub-wal", "rikka_hub-shm")

    private fun originalFiles(directory: File) {
        names.forEach { File(directory, it).writeText("old:$it") }
    }

    private fun assertOriginals(directory: File) {
        names.forEach { assertEquals("old:$it", File(directory, it).readText()) }
    }

    @Test fun partialBackupFailureLeavesEveryOriginalIntact() {
        val database = temporary.newFolder("database")
        val pending = temporary.newFolder("pending")
        val backup = File(database, "backup")
        originalFiles(database)
        File(pending, names[0]).writeText("new")
        val result = runCatching {
            DatabaseRestoreFiles.replace(pending, database, backup, names) { from, to ->
                if (from == File(database, names[1])) false else from.renameTo(to)
            }
        }
        assertTrue(result.isFailure)
        assertOriginals(database)
        assertEquals("new", File(pending, names[0]).readText())
    }

    @Test fun installFailureRestoresOriginalsAndKeepsStagedFilesForRetry() {
        val database = temporary.newFolder("database")
        val pending = temporary.newFolder("pending")
        val backup = File(database, "backup")
        originalFiles(database)
        names.forEach { File(pending, it).writeText("new:$it") }
        val result = runCatching {
            DatabaseRestoreFiles.replace(pending, database, backup, names) { from, to ->
                if (from == File(pending, names[1])) false else from.renameTo(to)
            }
        }
        assertTrue(result.isFailure)
        assertOriginals(database)
        names.forEach { assertEquals("new:$it", File(pending, it).readText()) }
        DatabaseRestoreFiles.replace(pending, database, backup, names)
        names.forEach { assertEquals("new:$it", File(database, it).readText()) }
    }

    @Test fun consistentSnapshotReplacesDatabaseWithoutOldWalFiles() {
        val database = temporary.newFolder("database")
        val pending = temporary.newFolder("pending")
        val backup = File(database, "backup")
        originalFiles(database)
        File(pending, names[0]).writeText("snapshot")
        DatabaseRestoreFiles.replace(pending, database, backup, names)
        assertEquals("snapshot", File(database, names[0]).readText())
        assertFalse(File(database, names[1]).exists())
        assertFalse(File(database, names[2]).exists())
        assertOriginals(backup)
    }

    @Test fun earlierRecoveryBackupIsNeverDiscarded() {
        val database = temporary.newFolder("database")
        val pending = temporary.newFolder("pending")
        val backup = File(database, "backup").apply { mkdirs() }
        File(backup, names[0]).writeText("recovery")
        File(pending, names[0]).writeText("new")
        assertTrue(runCatching { DatabaseRestoreFiles.replace(pending, database, backup, names) }.isFailure)
        assertEquals("recovery", File(backup, names[0]).readText())
        assertEquals("new", File(pending, names[0]).readText())
    }
}
