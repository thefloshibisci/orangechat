/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.files

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.exportImageFile
import me.rerere.rikkahub.utils.getActivity
import java.util.zip.ZipInputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
) {
    companion object {
        private const val TAG = "FilesManager"
    }

    private val managedFileMutex = Mutex()
    private val removedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun withoutDeletedAttachments(messages: List<UIMessage>): List<UIMessage> =
        if (removedUrls.isEmpty()) messages else filterAttachments(messages) {
            it.attachmentUrl() !in removedUrls
        }

    suspend fun validAttachments(
        messages: List<UIMessage>,
        forSending: Boolean = false,
    ): List<UIMessage> = withContext(Dispatchers.IO) {
        val validator = AttachmentValidator(forSending)
        filterAttachments(messages) { part ->
            validator.isValid(part).also { valid ->
                // A format unsupported by a provider (for example SVG) is still valid stored content.
                if (!forSending) part.attachmentUrl()?.let { url ->
                    if (valid) removedUrls.remove(url) else removedUrls.add(url)
                }
            }
        }
    }

    suspend fun saveManagedFromUri(
        folder: String,
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val resolvedName = displayName ?: getFileNameFromUri(uri) ?: "file"
        val resolvedMime = mimeType ?: getFileMimeType(uri) ?: "application/octet-stream"
        val target = createTargetFile(folder, resolvedName, resolvedMime)
        copyAttachmentAtomically(target) { context.contentResolver.openInputStream(uri) }
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = resolvedName,
            mimeType = resolvedMime,
        )
    }

    suspend fun saveManagedFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        copyAttachmentAtomically(target) { bytes.inputStream() }
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    suspend fun saveManagedText(
        folder: String,
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeText(text)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder).first()

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File =
        File(context.filesDir, entity.relativePath)

    fun createChatFilesByContents(uris: List<Uri>, forceCopy: Boolean = false): List<Uri> {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD).apply { mkdirs() }
        return uris.mapNotNull { uri ->
            runCatching {
                val source = localAttachmentFile(uri.toString())
                // A managed attachment is already durable. Regeneration/re-sending must not copy it again.
                if (!forceCopy && source != null && source.canonicalFile.parentFile == dir.canonicalFile) {
                    require(source.isFile && source.length() > 0) { "附件不存在或为空" }
                    return@runCatching source.toUri()
                }
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                val file = dir.resolve(buildUuidFileName(sourceName, sourceMime))
                copyAttachmentAtomically(file) { context.contentResolver.openInputStream(uri) }
                trackManagedFile(FileFolders.UPLOAD, file, sourceName, sourceMime ?: guessMimeType(file, sourceName))
                file.toUri()
            }.onFailure {
                Log.e(TAG, "createChatFilesByContents: Failed to import attachment", it)
            }.getOrNull()
        }
    }

    fun createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            if (byteArray.isEmpty()) return@forEach
            val fileName = buildUuidFileName(displayName = "image.png", mimeType = "image/png")
            val file = dir.resolve(fileName)
            copyAttachmentAtomically(file) { byteArray.inputStream() }
            val newUri = file.toUri()
            trackManagedFile(
                folder = FileFolders.UPLOAD,
                file = file,
                displayName = "image.png",
                mimeType = "image/png"
            )
            newUris.add(newUri)
        }
        return newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> {
                            if (part.url.startsWith("data:image")) {
                                val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
                                val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                                val byteArray = FileUtils.compressBitmapToPng(bitmap)
                                val urls = createChatFilesByByteArrays(listOf(byteArray))
                                Log.i(
                                    TAG,
                                    "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                                )
                                part.copy(
                                    url = urls.first().toString(),
                                )
                            } else {
                                part
                            }
                        }

                        else -> part
                    }
                }
            )
        }

    internal fun isManagedChatFile(url: String): Boolean {
        val file = localAttachmentFile(url) ?: return false
        return runCatching {
            file.canonicalFile.parentFile == context.filesDir.resolve(FileFolders.UPLOAD).canonicalFile
        }.getOrDefault(false)
    }

    fun deleteChatFiles(uris: List<Uri>) {
        val relativePaths = mutableSetOf<String>()
        uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
            val file = uri.toFile()
            val relativePath = getRelativePathInFilesDir(file) ?: return@forEach
            if (!file.exists() || file.delete()) {
                relativePaths.add(relativePath)
                removedUrls.add(uri.toString())
                removedUrls.add(file.absolutePath)
            }
        }
        if (relativePaths.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                managedFileMutex.withLock {
                    relativePaths.forEach { path -> repository.deleteByPath(path) }
                }
            }
        }
    }

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            return@withContext Pair(0, 0)
        }
        val files = dir.listFiles() ?: return@withContext Pair(0, 0)
        val count = files.size
        val size = files.sumOf { it.length() }
        Pair(count, size)
    }

    fun createChatTextFile(text: String): UIMessagePart.Document {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        file.writeText(text)
        trackManagedFile(
            folder = FileFolders.UPLOAD,
            file = file,
            displayName = "pasted_text.txt",
            mimeType = "text/plain"
        )
        return UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = context.filesDir.resolve("images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String) = withContext(Dispatchers.IO) {
        val activity = requireNotNull(activityContext.getActivity()) { "Activity not found" }
        when {
            image.startsWith("data:image") -> {
                val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                activityContext.exportImage(activity, bitmap)
            }

            image.startsWith("file:") -> {
                val file = image.toUri().toFile()
                activityContext.exportImageFile(activity, file)
            }

            image.startsWith("/") -> {
                activityContext.exportImageFile(activity, File(image))
            }

            image.startsWith("http") -> {
                runCatching {
                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connect()

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                        activityContext.exportImage(activity, bitmap)
                    } else {
                        Log.e(
                            TAG,
                            "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                        )
                    }
                }.getOrNull()
            }

            else -> error("Invalid image format")
        }
    }

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): Int = withContext(Dispatchers.IO) {
        managedFileMutex.withLock {
            val dir = File(context.filesDir, folder)
            if (!dir.exists()) return@withLock 0
            val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 }
                ?: return@withLock 0
            var inserted = 0
            files.forEach { file ->
                val relativePath = "${folder}/${file.name}"
                val existing = repository.getByPath(relativePath)
                if (existing == null) {
                    val now = System.currentTimeMillis()
                    val displayName = file.name
                    val mimeType = guessMimeType(file, displayName)
                    repository.insert(
                        ManagedFileEntity(
                            folder = folder,
                            relativePath = relativePath,
                            displayName = displayName,
                            mimeType = mimeType,
                            sizeBytes = file.length(),
                            createdAt = file.lastModified().takeIf { it > 0 } ?: now,
                            updatedAt = now,
                        )
                    )
                    inserted += 1
                }
            }
            inserted
        }
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        managedFileMutex.withLock {
            val entity = repository.getById(id) ?: return@withLock false
            if (deleteFromDisk) {
                val file = getFile(entity)
                if (file.exists() && !file.delete()) return@withLock false
                removedUrls.add(file.toUri().toString())
                removedUrls.add(file.absolutePath)
            }
            repository.deleteById(id) > 0
        }
    }

    /** Remove missing/empty/corrupt managed media; never delete valid unreferenced user files. */
    suspend fun cleanInvalidFiles(): Int = withContext(Dispatchers.IO) {
        var count = 0
        list().forEach { entity ->
            val file = getFile(entity)
            val invalid = !file.isFile || file.length() == 0L ||
                (entity.mimeType.startsWith("image/") && entity.mimeType != "image/svg+xml" && !isDecodableImage(file))
            if (invalid && delete(entity.id)) count++
        }
        // Failed imports from older releases may never have reached the managed-file table.
        File(context.filesDir, FileFolders.UPLOAD).listFiles()?.forEach { file ->
            if (file.isFile && !file.name.startsWith(".") && file.length() == 0L && file.delete()) count++
        }
        count
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, FileUtils.buildUuidFileName(displayName = displayName, mimeType = mimeType))
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String =
        FileUtils.buildUuidFileName(displayName, mimeType)

    private suspend fun createManagedFileEntity(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
    ): ManagedFileEntity {
        val now = System.currentTimeMillis()
        return repository.insert(
            ManagedFileEntity(
                folder = folder,
                relativePath = buildRelativePath(folder, file),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = file.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun trackManagedFile(folder: String, file: File, displayName: String, mimeType: String) {
        val relativePath = buildRelativePath(folder, file)
        appScope.launch(Dispatchers.IO) {
            managedFileMutex.withLock {
                runCatching {
                    if (!file.isFile || file.length() == 0L || file.toUri().toString() in removedUrls) return@runCatching
                    val existing = repository.getByPath(relativePath)
                    if (existing != null) {
                        return@runCatching
                    }
                    val now = System.currentTimeMillis()
                    repository.insert(
                        ManagedFileEntity(
                            folder = folder,
                            relativePath = relativePath,
                            displayName = displayName,
                            mimeType = mimeType,
                            sizeBytes = file.length(),
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                }.onFailure {
                    Log.e(TAG, "trackManagedFile: Failed to track file ${file.absolutePath}", it)
                    Logging.log(
                        TAG,
                        "trackManagedFile: Failed to track file ${file.absolutePath} ${it.message} | ${it.stackTraceToString()}"
                    )
                }
            }
        }
    }

    private fun buildRelativePath(folder: String, file: File): String =
        FileUtils.buildRelativePath(folder, file)

    private fun getRelativePathInFilesDir(file: File): String? =
        FileUtils.getRelativePathInFilesDir(context.filesDir, file)

    fun getFileNameFromUri(uri: Uri): String? =
        FileUtils.getFileNameFromUri(context, uri)

    fun getFileMimeType(uri: Uri): String? =
        FileUtils.getFileMimeType(context, uri)

    private fun guessMimeType(file: File, fileName: String): String =
        FileUtils.guessMimeType(file, fileName)

    /**
     * 已知的文本文件扩展名（小写，不含点号）
     */
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "js", "jsx", "mjs", "cjs",
        "html", "css", "vue", "svelte", "xml",
        "py", "rb", "lua", "sql",
        "java", "kt", "ts", "tsx", "dart", "php", "swift", "go",
        "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh", "fish",
        "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx",
        "rs", "cs", "markdown", "mdx",
        "toml", "ini", "env", "gradle", "kts", "properties",
        "proto", "graphql", "gql",
        "yml", "yaml",
        "r", "scala", "clj", "ex", "exs", "erl", "hs",
        "ml", "fs", "cob", "cbl", "f90", "f",
        "asm", "s", "v", "sv", "vhd", "vhdl",
        "cmake", "make", "dockerfile",
        "gitignore", "editorconfig", "prettierrc", "eslintrc",
        "lock", "log", "conf", "cfg",
    )

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

    /**
     * 从 ZIP 文件中提取内容并转为聊天附件。
     * - 文本文件 → UIMessagePart.Document
     * - 图片文件 → Uri（用于 addImages）
     * - 其他文件 → 跳过并记录
     * - 自动跳过隐藏文件/目录（以 . 开头）和 __MACOSX 等
     */
    fun extractZipToChatFiles(
        zipUri: Uri,
        zipFileName: String,
    ): ZipExtractResult {
        val documents = mutableListOf<UIMessagePart.Document>()
        val images = mutableListOf<Uri>()
        val skippedFiles = mutableListOf<String>()

        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) dir.mkdirs()

        context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name

                    // Skip directories, hidden files, and macOS metadata
                    if (entry.isDirectory ||
                        entryName.startsWith("__MACOSX") ||
                        entryName.split("/").any { it.startsWith(".") }
                    ) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    // Get just the filename (without path)
                    val fileName = entryName.substringAfterLast("/")
                    if (fileName.isBlank()) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    val ext = fileName.substringAfterLast(".", "").lowercase()

                    when {
                        ext in IMAGE_EXTENSIONS -> {
                            val savedName = buildUuidFileName(displayName = fileName, mimeType = "image/$ext")
                            val file = dir.resolve(savedName)
                            file.outputStream().use { output -> zis.copyTo(output) }
                            trackManagedFile(
                                folder = FileFolders.UPLOAD,
                                file = file,
                                displayName = fileName,
                                mimeType = "image/$ext"
                            )
                            images.add(file.toUri())
                        }

                        ext in TEXT_EXTENSIONS -> {
                            val mime = FileUtils.guessMimeTypeFromExtension(ext)
                            val savedName = buildUuidFileName(displayName = fileName, mimeType = mime)
                            val file = dir.resolve(savedName)
                            file.outputStream().use { output -> zis.copyTo(output) }
                            trackManagedFile(
                                folder = FileFolders.UPLOAD,
                                file = file,
                                displayName = fileName,
                                mimeType = mime
                            )
                            documents.add(
                                UIMessagePart.Document(
                                    url = file.toUri().toString(),
                                    fileName = fileName,
                                    mime = mime
                                )
                            )
                        }

                        ext == "pdf" || ext in setOf(
                            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub"
                        ) -> {
                            val mime = FileUtils.guessMimeTypeFromExtension(ext)
                            val savedName = buildUuidFileName(displayName = fileName, mimeType = mime)
                            val file = dir.resolve(savedName)
                            file.outputStream().use { output -> zis.copyTo(output) }
                            trackManagedFile(
                                folder = FileFolders.UPLOAD,
                                file = file,
                                displayName = fileName,
                                mimeType = mime
                            )
                            documents.add(
                                UIMessagePart.Document(
                                    url = file.toUri().toString(),
                                    fileName = fileName,
                                    mime = mime
                                )
                            )
                        }

                        ext == "zip" -> {
                            skippedFiles.add(fileName)
                        }

                        else -> {
                            if (entry.size in 1..100_000) {
                                val bytes = zis.readBytes()
                                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
                                if (text != null && text.isValidUtf8Text()) {
                                    val mime = "text/plain"
                                    val savedName = buildUuidFileName(displayName = fileName, mimeType = mime)
                                    val file = dir.resolve(savedName)
                                    file.writeBytes(bytes)
                                    trackManagedFile(
                                        folder = FileFolders.UPLOAD,
                                        file = file,
                                        displayName = fileName,
                                        mimeType = mime
                                    )
                                    documents.add(
                                        UIMessagePart.Document(
                                            url = file.toUri().toString(),
                                            fileName = fileName,
                                            mime = mime
                                        )
                                    )
                                } else {
                                    skippedFiles.add(fileName)
                                }
                            } else {
                                skippedFiles.add(fileName)
                            }
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        if (skippedFiles.isNotEmpty()) {
            val summaryText = buildString {
                appendLine("Files in '$zipFileName' that could not be extracted as text:")
                skippedFiles.forEach { appendLine("- $it") }
            }
            val summaryDoc = createChatTextFile(summaryText)
            documents.add(
                UIMessagePart.Document(
                    url = summaryDoc.url,
                    fileName = "${zipFileName}_skipped_files.txt",
                    mime = "text/plain"
                )
            )
        }

        return ZipExtractResult(documents = documents, images = images)
    }

    private fun String.isValidUtf8Text(): Boolean {
        if (this.isEmpty()) return false
        val sampleSize = minOf(length, 500)
        val sample = this.take(sampleSize)
        val printable = sample.count { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }
        return printable.toFloat() / sampleSize > 0.9f
    }
}

/**
 * ZIP 解压结果：文档列表 + 图片 URI 列表
 */
data class ZipExtractResult(
    val documents: List<UIMessagePart.Document>,
    val images: List<Uri>,
)

object FileFolders {
    const val UPLOAD = "upload"
    const val SKILLS = "skills"
}

suspend fun FilesManager.saveUploadFromUri(
    uri: Uri,
    displayName: String? = null,
    mimeType: String? = null,
): ManagedFileEntity = saveManagedFromUri(
    folder = FileFolders.UPLOAD,
    uri = uri,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadFromBytes(
    bytes: ByteArray,
    displayName: String,
    mimeType: String = "application/octet-stream",
): ManagedFileEntity = saveManagedFromBytes(
    folder = FileFolders.UPLOAD,
    bytes = bytes,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadText(
    text: String,
    displayName: String = "pasted_text.txt",
    mimeType: String = "text/plain",
): ManagedFileEntity = saveManagedText(
    folder = FileFolders.UPLOAD,
    text = text,
    displayName = displayName,
    mimeType = mimeType,
)
