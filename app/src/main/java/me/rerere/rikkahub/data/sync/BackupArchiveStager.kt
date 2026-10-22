package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

internal data class StagedBackup(
    val settings: String?,
    val pluginSettings: String?,
    val files: List<String>,
    val databases: List<String>,
)

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
    var settings: String? = null
    var pluginSettings: String? = null
    ZipInputStream(archive.inputStream().buffered()).use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = input.nextEntry ?: break
            val name = entry.name.replace('\\', '/')
            require(!name.startsWith('/') && name.split('/').none { it == ".." || it == "." }) {
                "Invalid backup entry: ${entry.name}"
            }
            if (!entry.isDirectory) {
                require(seen.add(name)) { "Duplicate backup entry: $name" }
                when (name) {
                    "settings.json" -> settings = input.readBytes().toString(Charsets.UTF_8)
                    "plugin_settings.json" -> if (includePlugins) {
                        pluginSettings = input.readBytes().toString(Charsets.UTF_8)
                    }
                    else -> {
                        val destination = when {
                            restoreDatabase && name in listOf("rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm") ->
                                "$databaseDirectory/${if (name == "rikka_hub.db") "rikka_hub" else name}"
                            restoreFiles && (name.startsWith("upload/") || name.startsWith("skills/")) -> "files/$name"
                            restoreFiles && includePlugins && name.startsWith("plugins/") -> "files/$name"
                            restoreFiles && includePlugins && name.startsWith("Orangechat/plugins/") ->
                                "files/${name.removePrefix("Orangechat/")}"
                            else -> null
                        }
                        if (destination != null) {
                            val target = transaction.stagePath(destination)
                            require(!target.exists()) { "Conflicting backup target: $name" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                            if (!destination.startsWith("$databaseDirectory/")) files += destination
                        }
                    }
                }
            }
            // closeEntry also consumes/checks ignored entries, including their CRC.
            input.closeEntry()
        }
    }
    require(settings != null || files.isNotEmpty() || transaction.stagePath("$databaseDirectory/rikka_hub").isFile) {
        "Backup contains no restorable data"
    }
    val databases = if (restoreDatabase) {
        require(transaction.stagePath("$databaseDirectory/rikka_hub").isFile) { "Backup has no database" }
        // Missing sidecars mean remove old live sidecars, never replay them against the restored DB.
        listOf("rikka_hub", "rikka_hub-wal", "rikka_hub-shm").map { "$databaseDirectory/$it" }
    } else emptyList()
    return StagedBackup(settings, pluginSettings, files, databases)
}
