package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

private const val MAX_BACKUP_ZIP_ENTRIES = 25_000
private const val MAX_BACKUP_JSON_BYTES = 20 * 1024 * 1024
private const val MAX_BACKUP_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

/** Per-plugin logical snapshot embedded in a full local backup. */
internal data class StagedPluginBackup(
    val pluginId: String,
    val file: File,
)

internal data class StagedBackup(
    val settings: String?,
    val pluginSettings: String?,
    val files: List<String>,
    val databases: List<String>,
    val pluginBackups: List<StagedPluginBackup> = emptyList(),
)

/** Archive entry prefix produced by [PluginDataBackupCoordinator] and consumed by the stager. */
internal const val PLUGIN_BACKUP_ENTRY_PREFIX = "Orangechat/plugin_backups/"

/** Staging-only path for the per-plugin archive; it is never installed as a live file. */
private const val PLUGIN_BACKUP_STAGING = "files/.plugin_backup_restore"

/** Fully consume and validate the ZIP before any existing file or preference is changed. */
internal suspend fun stageBackupArchive(
    archive: File,
    transaction: BackupRestoreTransaction,
    restoreFiles: Boolean,
    restoreDatabase: Boolean,
    includePlugins: Boolean,
    databaseDirectory: String = "databases",
): StagedBackup {
    val seen = mutableSetOf<String>()
    val files = mutableListOf<String>()
    val pluginBackups = mutableListOf<StagedPluginBackup>()
    var settings: String? = null
    var pluginSettings: String? = null
    var entryCount = 0
    var totalBytes = 0L
    ZipInputStream(archive.inputStream().buffered()).use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = input.nextEntry ?: break
            entryCount++
            require(entryCount <= MAX_BACKUP_ZIP_ENTRIES) { "Backup archive has too many entries" }
            val name = entry.name.replace('\\', '/')
            require(!name.startsWith('/') && name.split('/').none { it == ".." || it == "." }) {
                "Invalid backup entry: ${entry.name}"
            }
            if (!entry.isDirectory) {
                require(seen.add(name)) { "Duplicate backup entry: $name" }
                val remaining = MAX_BACKUP_TOTAL_BYTES - totalBytes
                when (name) {
                    "settings.json" -> {
                        val content = readBoundedUtf8(input, minOf(MAX_BACKUP_JSON_BYTES.toLong(), remaining).toInt())
                        totalBytes += content.size
                        settings = content.toString(Charsets.UTF_8)
                    }
                    "plugin_settings.json" -> if (includePlugins) {
                        val content = readBoundedUtf8(input, minOf(MAX_BACKUP_JSON_BYTES.toLong(), remaining).toInt())
                        totalBytes += content.size
                        pluginSettings = content.toString(Charsets.UTF_8)
                    }
                    else -> {
                        if (includePlugins && restoreFiles && name.startsWith(PLUGIN_BACKUP_ENTRY_PREFIX)) {
                            val relative = name.removePrefix(PLUGIN_BACKUP_ENTRY_PREFIX)
                            require(relative.endsWith(".zip") && '/' !in relative) {
                                "Invalid plugin backup entry: $name"
                            }
                            val pluginId = relative.removeSuffix(".zip")
                            require(LocalDataValidation.PLUGIN_ID_PATTERN.matches(pluginId) &&
                                pluginId != "." && pluginId != ".."
                            ) { "Invalid plugin backup id: $pluginId" }
                            val destination = "$PLUGIN_BACKUP_STAGING/$pluginId.zip"
                            val target = transaction.stagePath(destination)
                            require(!target.exists()) { "Conflicting backup target: $name" }
                            totalBytes += copyEntry(input, target, remaining)
                            // These bytes are input for the logical restore and must not become
                            // live files, so they are not listed in `files`.
                            pluginBackups += StagedPluginBackup(pluginId, target)
                        } else {
                            val destination = when {
                                restoreDatabase && name in listOf("rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm") ->
                                    "$databaseDirectory/${if (name == "rikka_hub.db") "rikka_hub" else name}"
                                restoreFiles && (name.startsWith("upload/") || name.startsWith("skills/")) ->
                                    "files/$name"
                                restoreFiles && includePlugins && name.startsWith("plugins/") -> "files/$name"
                                restoreFiles && includePlugins && name.startsWith("Orangechat/plugins/") ->
                                    "files/${name.removePrefix("Orangechat/")}"
                                else -> null
                            }
                            if (destination != null) {
                                totalBytes += copyEntry(input, transaction.stagePath(destination), remaining)
                                if (!destination.startsWith("$databaseDirectory/")) files += destination
                            }
                        }
                    }
                }
            }
            // closeEntry also consumes/checks ignored entries, including their CRC.
            input.closeEntry()
        }
    }
    require(settings != null || files.isNotEmpty() || pluginBackups.isNotEmpty() ||
        transaction.stagePath("$databaseDirectory/rikka_hub").isFile
    ) {
        "Backup contains no restorable data"
    }
    val databases = if (restoreDatabase) {
        require(transaction.stagePath("$databaseDirectory/rikka_hub").isFile) { "Backup has no database" }
        // Missing sidecars mean remove old live sidecars, never replay them against the restored DB.
        listOf("rikka_hub", "rikka_hub-wal", "rikka_hub-shm").map { "$databaseDirectory/$it" }
    } else emptyList()
    return StagedBackup(settings, pluginSettings, files, databases, pluginBackups)
}

private suspend fun copyEntry(input: InputStream, target: File, remainingBytes: Long): Long {
    require(!target.exists()) { "Conflicting backup target: ${target.name}" }
    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
    var total = 0L
    try {
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= remainingBytes) { "Backup archive exceeds maximum unpacked size" }
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
        return total
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}

private suspend fun readBoundedUtf8(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "Backup archive exceeds maximum unpacked size" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Backup archive metadata exceeds maximum size" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/**
 * Live files inside every plugin directory the backup restores, but that the backup does not
 * contain. Listing them as restore targets without a staged replacement makes the journal move
 * them aside (and back on rollback), so each restored plugin directory ends up exactly as it was
 * backed up. Otherwise files added by a newer plugin version survive next to the older backup's
 * files and `.integrity`, and the plugin fails its checksum ("插件文件已被篡改或损坏").
 */
internal fun stalePluginFiles(root: File, stagedFiles: List<String>): List<String> {
    val prefix = "files/plugins/"
    val staged = stagedFiles.toHashSet()
    val pluginIds = stagedFiles.filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix).substringBefore('/') }
        .filter { it.isNotBlank() && !it.startsWith(".") }
        .toSet()
    return pluginIds.flatMap { id ->
        val directory = File(root, prefix + id)
        if (!directory.isDirectory) return@flatMap emptyList()
        directory.walkTopDown().filter { it.isFile }
            .map { prefix + id + "/" + it.relativeTo(directory).invariantSeparatorsPath }
            .filter { it !in staged }
            .toList()
    }.sorted()
}
