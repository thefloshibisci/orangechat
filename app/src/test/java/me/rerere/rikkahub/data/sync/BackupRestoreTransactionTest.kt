package me.rerere.rikkahub.data.sync

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRestoreTransactionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun installFailureCanRestoreReplacedAndNewFiles() {
        val root = temporary.newFolder("root")
        val original = File(root, "files/upload/old.txt").apply { parentFile!!.mkdirs(); writeText("old") }
        val added = File(root, "files/upload/new.txt")
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("files/upload/old.txt").apply { parentFile!!.mkdirs(); writeText("replacement") }
        transaction.stagePath("files/upload/new.txt").writeText("new")
        transaction.prepare(listOf("files/upload/old.txt", "files/upload/new.txt"), emptyList(), emptyList())

        var moves = 0
        assertTrue(runCatching {
            transaction.installFiles { from, to ->
                moves++
                if (moves == 3) false else from.renameTo(to)
            }
        }.isFailure)
        transaction.beginRollback()
        transaction.rollbackFiles()

        assertEquals("old", original.readText())
        assertFalse(added.exists())
    }

    @Test fun startupBeforeDatabaseReadyRollsBackFilesAndRawPreferences() {
        val root = temporary.newFolder("root")
        val file = File(root, "files/upload/item.txt").apply { parentFile!!.mkdirs(); writeText("old-file") }
        val preferences = File(root, "files/datastore/settings.preferences_pb")
            .apply { parentFile!!.mkdirs(); writeText("old-settings") }
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("files/upload/item.txt").apply { parentFile!!.mkdirs(); writeText("new-file") }
        transaction.stagePath("databases/rikka_hub").apply { parentFile!!.mkdirs(); writeText("new-db") }
        transaction.prepare(
            files = listOf("files/upload/item.txt"),
            preferences = listOf("files/datastore/settings.preferences_pb"),
            databases = listOf("databases/rikka_hub"),
        )
        transaction.installFiles()
        preferences.writeText("new-settings")

        assertEquals(null, BackupRestoreTransaction.recoverBeforeStartup(root))
        assertEquals("old-file", file.readText())
        assertEquals("old-settings", preferences.readText())
        assertFalse(File(root, "databases/rikka_hub").exists())
    }

    @Test fun startupActivatesReadyDatabaseAndDropsAbsentOldSidecars() {
        val root = temporary.newFolder("root")
        val database = File(root, "databases/rikka_hub").apply { parentFile!!.mkdirs(); writeText("old-db") }
        val wal = File(root, "databases/rikka_hub-wal").apply { writeText("stale-wal") }
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        transaction.stagePath("databases/rikka_hub").apply { parentFile!!.mkdirs(); writeText("new-db") }
        transaction.prepare(emptyList(), emptyList(), listOf("databases/rikka_hub", "databases/rikka_hub-wal"))
        transaction.finish(hasDatabase = true)

        assertEquals(null, BackupRestoreTransaction.recoverBeforeStartup(root))
        assertEquals("new-db", database.readText())
        assertFalse(wal.exists())
    }

    @Test fun stagePathRejectsTraversal() {
        val transaction = BackupRestoreTransaction(temporary.newFolder("root"))
        assertTrue(runCatching { transaction.stagePath("files/../../outside") }.isFailure)
    }

    @Test fun archiveTraversalIsRejectedBeforeExistingDataChanges() = runBlocking {
        val root = temporary.newFolder("root")
        val original = File(root, "files/upload/item.txt").apply { parentFile!!.mkdirs(); writeText("original") }
        val archive = File(temporary.root, "traversal.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("upload/../../outside"))
            zip.write("bad".toByteArray())
            zip.closeEntry()
        }
        val transaction = BackupRestoreTransaction(root).apply { begin() }

        assertTrue(runCatching {
            stageBackupArchive(archive, transaction, true, false, false)
        }.isFailure)
        transaction.discardUnprepared()
        assertEquals("original", original.readText())
        assertFalse(File(temporary.root, "outside").exists())
    }
}
