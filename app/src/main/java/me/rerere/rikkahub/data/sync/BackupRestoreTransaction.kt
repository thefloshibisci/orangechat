package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.SafeFileResolver
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Recoverable file replacement journal. Paths are relative to the private app data directory.
 * Preferences are changed through DataStore while running; raw snapshots are used only before
 * DataStore opens on process startup. Database files are installed only on that startup.
 */
internal class BackupRestoreTransaction(private val root: File) {
    private val directory = File(root, DIRECTORY)
    val staging = File(directory, "new")
    private val originals = File(directory, "old")
    val isPrepared: Boolean get() = File(directory, APPLYING).isFile

    fun relativePath(file: File): String = file.relativeTo(root).invariantSeparatorsPath.also { resolve(root, it) }

    @Serializable
    private data class Target(
        val path: String, val existed: Boolean, val preference: Boolean, val database: Boolean,
        val replacement: Boolean,
    )

    @Serializable
    private data class Journal(val targets: List<Target>)

    fun begin() {
        if (File(directory, COMMITTED).isFile) cleanup()
        check(!directory.exists()) { "A previous restore needs an app restart before another restore" }
        check(!File(root, "files/pending_database_restore/.ready").exists()) {
            "An earlier database restore needs an app restart"
        }
        check(staging.mkdirs()) { "Unable to create restore staging directory" }
    }

    fun stagePath(path: String): File = resolve(staging, path)

    fun prepare(files: List<String>, preferences: List<String>, databases: List<String>) {
        val paths = files + databases + preferences
        check(paths.distinct().size == paths.size) { "Duplicate restore targets" }
        val targets = paths.map { path ->
            val target = resolve(root, path)
            Target(path, target.exists(), path in preferences, path in databases, stagePath(path).exists())
        }
        targets.filter { it.preference && it.existed }.forEach { target ->
            val backup = resolve(originals, target.path)
            check(backup.parentFile!!.isDirectory || backup.parentFile!!.mkdirs())
            copyDurably(resolve(root, target.path), backup)
        }
        writeDurably(File(directory, JOURNAL), Json.encodeToString(Journal(targets)))
        // No live data may change until the complete rollback description is on disk.
        writeDurably(File(directory, APPLYING), "applying")
    }

    fun installFiles(move: (File, File) -> Boolean = File::renameTo) {
        install(readJournal().targets.filter { !it.preference && !it.database }, move)
    }

    /** Keep old files/preferences until the database has also been activated on restart. */
    fun finish(hasDatabase: Boolean) {
        if (hasDatabase) writeDurably(File(directory, READY), "ready") else commit()
    }

    fun rollbackFiles() {
        beginRollback()
        rollback(readJournal().targets.filterNot { it.preference })
    }

    fun beginRollback() = writeDurably(File(directory, ROLLING_BACK), "rolling back")

    fun discard() = cleanup()

    /** An extraction/validation failure never touched live data. */
    fun discardUnprepared() {
        check(!File(directory, APPLYING).exists())
        cleanup()
    }

    private fun install(targets: List<Target>, move: (File, File) -> Boolean) {
        targets.forEach { item ->
            val staged = resolve(staging, item.path)
            val target = resolve(root, item.path)
            val backup = resolve(originals, item.path)
            // A process can die after a move; retrying activation must not back up the new file.
            if (item.replacement && !staged.exists()) {
                check(target.exists()) { "Missing staged restore file: ${item.path}" }
                return@forEach
            }
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            if (item.existed && !backup.exists()) {
                check(backup.parentFile!!.isDirectory || backup.parentFile!!.mkdirs())
                check(move(target, backup)) { "Unable to preserve original file: ${item.path}" }
            }
            if (item.replacement) {
                check(move(staged, target)) { "Unable to install restore file: ${item.path}" }
            }
        }
    }

    private fun rollback(targets: List<Target>) {
        var failure: Throwable? = null
        targets.asReversed().forEach { item ->
            runCatching {
                val target = resolve(root, item.path)
                val backup = resolve(originals, item.path)
                if (item.preference && item.existed) {
                    // Copy rather than move: a second crash must still be recoverable.
                    check(backup.isFile) { "Missing preferences recovery snapshot: ${item.path}" }
                    val temporary = File(target.parentFile, ".${target.name}.restore-${UUID.randomUUID()}")
                    copyDurably(backup, temporary)
                    check(!target.exists() || target.delete()) { "Cannot remove partial preferences" }
                    check(temporary.renameTo(target)) { "Cannot recover preferences" }
                } else if (backup.exists()) {
                    check(!target.exists() || target.deleteRecursively()) { "Cannot remove partial restored file" }
                    check(backup.renameTo(target)) { "Cannot recover original file: ${item.path}" }
                } else if (!item.existed) {
                    check(!target.exists() || target.deleteRecursively()) { "Cannot remove new restore file: ${item.path}" }
                }
            }.onFailure { error ->
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun readJournal() = Json.decodeFromString<Journal>(File(directory, JOURNAL).readText())

    private fun commit() {
        writeDurably(File(directory, COMMITTED), "committed")
        // Cleanup failure must not report a successfully committed restore as failed.
        runCatching { cleanup() }
    }

    private fun cleanup() {
        if (!directory.exists()) return
        val garbage = File(directory.parentFile, ".backup_restore.finished-${UUID.randomUUID()}")
        // Rename first: interrupted recursive cleanup must never look like an unfinished journal.
        check(directory.renameTo(garbage)) { "Unable to retire restore journal" }
        garbage.deleteRecursively()
    }

    companion object {
        private const val DIRECTORY = "files/.backup_restore"
        private const val JOURNAL = "journal.json"
        private const val APPLYING = ".applying"
        private const val READY = ".ready"
        private const val COMMITTED = ".committed"
        private const val ROLLING_BACK = ".rolling_back"

        private fun resolve(root: File, path: String): File {
            require(!File(path).isAbsolute && path.split('/').none { it == ".." || it == "." }) {
                "Invalid restore path"
            }
            return requireNotNull(SafeFileResolver.resolveInside(root, path)) { "Restore path escapes app data" }
        }

        private fun writeDurably(file: File, value: String) {
            FileOutputStream(file).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        }

        private fun copyDurably(source: File, target: File) {
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            FileOutputStream(target).use { output ->
                source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
        }

        /** Call before Room, Koin and either DataStore can open files. Returns an activation error only
         * after rollback has succeeded; a recovery failure throws so startup cannot use mixed data. */
        fun recoverBeforeStartup(root: File): Throwable? {
            val transaction = BackupRestoreTransaction(root)
            val directory = transaction.directory
            if (!directory.exists()) return null
            if (File(directory, COMMITTED).isFile || !File(directory, APPLYING).isFile) {
                transaction.cleanup()
                return null
            }
            val targets = transaction.readJournal().targets
            if (File(directory, READY).isFile && !File(directory, ROLLING_BACK).isFile) {
                try {
                    transaction.install(targets.filter { it.database }, File::renameTo)
                    transaction.commit()
                    return null
                } catch (error: Throwable) {
                    transaction.beginRollback()
                    try { transaction.rollback(targets) } catch (recovery: Throwable) {
                        recovery.addSuppressed(error)
                        throw recovery
                    }
                    transaction.cleanup()
                    return error
                }
            }
            transaction.beginRollback()
            transaction.rollback(targets)
            transaction.cleanup()
            return null
        }
    }
}
