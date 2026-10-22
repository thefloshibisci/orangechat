/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "S3Sync"

class S3Sync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val databaseBackupCoordinator: DatabaseBackupCoordinator,
) {
    private fun getS3Client(config: S3Config): S3Client {
        return S3Client(config, httpClient)
    }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        // Test by listing objects with max 1 result
        client.listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(config: S3Config) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        try {
            val client = getS3Client(config)
            val key = "rikkahub_backups/${file.name}"

            client.putObject(
                key = key,
                file = file,
                contentType = "application/zip"
            ).getOrThrow()

            Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val result = client.listObjects(
            prefix = "rikkahub_backups/",
            maxKeys = 1000
        ).getOrThrow()

        result.objects
            .filter { it.key.startsWith("rikkahub_backups/backup_") && it.key.endsWith(".zip") }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast("/"),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restoreFromS3: Downloading ${item.displayName}")
            client.downloadObjectToFile(item.key, backupFile).getOrThrow()

            Log.i(TAG, "restoreFromS3: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restoreFromS3: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        client.deleteObject(item.key).getOrThrow()
        Log.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
    }

    suspend fun prepareBackupFile(config: S3Config): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_${timestamp}_${java.util.UUID.randomUUID()}.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        try {
            // Create zip file and backup data
            ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
                addVirtualFileToZip(
                    zipOut = zipOut,
                    name = "settings.json",
                    content = json.encodeToString(settingsStore.settingsFlow.value)
                )

                // Backup database files
                if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                    val snapshot = databaseBackupCoordinator.createConsistentSnapshot()
                    try {
                        addFileToZip(zipOut, snapshot, "rikka_hub.db")
                    } finally {
                        snapshot.delete()
                    }
                }

                // Backup app files
                if (config.items.contains(S3Config.BackupItem.FILES)) {
                    val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                    if (uploadFolder.exists() && uploadFolder.isDirectory) {
                        Log.i(TAG, "prepareBackupFile: Backing up files from ${uploadFolder.absolutePath}")
                        uploadFolder.listFiles()?.forEach { file ->
                            if (file.isFile && !file.name.startsWith(".") && file.length() > 0) {
                                addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                            }
                        }
                    } else {
                        Log.w(TAG, "prepareBackupFile: Upload folder does not exist or is not a directory")
                    }

                    val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                    if (skillsFolder.exists() && skillsFolder.isDirectory) {
                        Log.i(TAG, "prepareBackupFile: Backing up skills from ${skillsFolder.absolutePath}")
                        addDirectoryToZip(
                            zipOut = zipOut,
                            rootDir = skillsFolder,
                            currentDir = skillsFolder,
                            entryPrefix = "${FileFolders.SKILLS}/"
                        )
                    } else {
                        Log.w(TAG, "prepareBackupFile: Skills folder does not exist or is not a directory")
                    }
                }
            }
        } catch (e: Exception) {
            backupFile.delete()
            throw e
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(
        backupFile: File, config: S3Config,
    ) {
        BackupArchiveRestorer(context, settingsStore, json, databaseBackupCoordinator).restore(
            archive = backupFile,
            restoreFiles = config.items.contains(S3Config.BackupItem.FILES),
            restoreDatabase = config.items.contains(S3Config.BackupItem.DATABASE),
        )
        Log.i(TAG, "restoreFromBackupFile: Restore committed; restart required for database activation")
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
