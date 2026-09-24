/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.scanner

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.SafeFileResolver
import me.rerere.rikkahub.data.security.SecurityAuditRepository
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 插件扫描器
 * 负责扫描、导入和管理插件目录
 */
class PluginScanner(
    private val context: Context,
    private val auditRepo: SecurityAuditRepository? = null,
) {
    companion object {
        const val PLUGINS_DIR = "plugins"
        const val LEGACY_PLUGINS_DIR = "Orangechat/plugins"
        const val MANIFEST_FILE = "manifest.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 获取插件根目录。插件是应用私有数据，使用无需存储权限且受分区存储支持的目录。
     */
    val pluginsDir: File
        get() = File(context.filesDir, PLUGINS_DIR).apply { mkdirs() }

    /**
     * 确保插件目录存在
     */
    fun ensurePluginsDir(): File = pluginsDir

    /**
     * 扫描所有插件
     */
    fun scanPlugins(): List<PluginInfo> {
        val dir = ensurePluginsDir()
        migrateLegacyPlugins(dir)
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }

        return dir.listFiles { file -> file.isDirectory }
            ?.mapNotNull { pluginDir -> loadPluginInfo(pluginDir) }
            ?: emptyList()
    }

    /**
     * 将旧版公共存储目录中的插件复制到应用私有目录。
     *
     * 迁移只补齐私有目录中不存在的插件，不覆盖用户已经导入或升级的新版本；
     * 旧目录也会保留，避免迁移失败或回退旧版应用时造成数据丢失。
     */
    private fun migrateLegacyPlugins(destinationRoot: File) {
        runCatching {
            val legacyRoot = File(Environment.getExternalStorageDirectory(), LEGACY_PLUGINS_DIR)
            if (!legacyRoot.isDirectory || legacyRoot.canonicalPath == destinationRoot.canonicalPath) {
                return@runCatching
            }

            legacyRoot.listFiles { file -> file.isDirectory }
                ?.filter { legacyPluginDir -> File(legacyPluginDir, MANIFEST_FILE).isFile }
                ?.forEach { legacyPluginDir ->
                    val destination = File(destinationRoot, legacyPluginDir.name)
                    if (!destination.exists()) {
                        installPluginDirectory(legacyPluginDir, destination)
                    }
                }
        }
    }

    /**
     * 加载单个插件信息
     */
    fun loadPluginInfo(pluginDir: File): PluginInfo? {
        val manifestFile = File(pluginDir, MANIFEST_FILE)
        if (!manifestFile.exists()) {
            return null
        }

        return try {
            val content = manifestFile.readText()
            val manifest = json.decodeFromString(PluginManifest.serializer(), content)
            validateManifest(manifest, pluginDir)
            require(manifest.id == pluginDir.name) { "插件目录与清单 ID 不一致" }

            // 完整性校验：若存在 .integrity 文件则验证，失败则禁用并标记错误
            val integrityFile = File(pluginDir, ".integrity")
            if (integrityFile.exists()) {
                val stored = integrityFile.readText().trim()
                val current = computePluginChecksum(pluginDir)
                if (stored != current) {
                    return PluginInfo(
                        manifest = manifest,
                        directory = pluginDir,
                        isEnabled = false,
                        loadError = "完整性校验失败：插件文件已被篡改或损坏"
                    )
                }
            }

            PluginInfo(
                manifest = manifest,
                directory = pluginDir,
                isEnabled = true // 默认启用
            )
        } catch (e: Exception) {
            // 解析失败，返回错误状态的插件
            PluginInfo(
                manifest = PluginManifest(
                    id = pluginDir.name,
                    name = pluginDir.name,
                    description = "加载失败: ${e.message}",
                    version = "error",
                    author = "unknown",
                    icon = "⚠️",
                    entry = "",
                    tools = emptyList(),
                    config = emptyList()
                ),
                directory = pluginDir,
                isEnabled = false,
                loadError = e.message
            )
        }
    }

    /**
     * 从ZIP文件预览插件（不解压到插件目录，仅解析 manifest）
     * 返回 manifest 和临时目录，供调用方展示权限确认对话框。
     * 调用方确认后应调用 [completeImport] 完成导入；取消时应清理临时目录。
     */
    suspend fun previewFromZip(uri: Uri): Result<Pair<PluginManifest, File>> {
        val tempFile = File(context.cacheDir, "plugin_preview_${System.currentTimeMillis()}.zip")
        var tempDir: File? = null
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(IllegalStateException("无法读取文件"))

            val extractedDir = File(context.cacheDir, "plugin_preview_${System.currentTimeMillis()}")
            tempDir = extractedDir
            unzip(tempFile, extractedDir)

            val manifestFile = findManifest(extractedDir)
                ?: run {
                    extractedDir.deleteRecursively()
                    return Result.failure(IllegalArgumentException("找不到 manifest.json"))
                }

            val manifest = json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())
            validateManifest(manifest, manifestFile.parentFile)

            // 预览阶段只保留解压目录，供后续 completeImport 使用
            Result.success(manifest to extractedDir)
        } catch (e: Exception) {
            tempDir?.deleteRecursively()
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 完成插件导入（在 previewFromZip 后调用）
     * @param manifest 预览阶段解析的 manifest
     * @param tempDir 预览阶段解压的临时目录
     */
    suspend fun completeImport(manifest: PluginManifest, tempDir: File): Result<PluginInfo> {
        return try {
            validatePluginId(manifest.id)
            val manifestFile = findManifest(tempDir)
                ?: run {
                    tempDir.deleteRecursively()
                    return Result.failure(IllegalArgumentException("找不到 manifest.json"))
                }

            val actualManifest = json.decodeFromString<PluginManifest>(manifestFile.readText())
            require(actualManifest == manifest) {
                "插件预览内容已变化，请重新选择文件"
            }
            val manifestRoot = manifestFile.parentFile ?: throw IllegalArgumentException("插件目录无效")
            validateManifest(actualManifest, manifestRoot)
            val entryFile = SafeFileResolver.resolveInside(manifestRoot, manifest.entry)
            if (entryFile == null || !entryFile.isFile) {
                tempDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("找不到入口文件: ${manifest.entry}"))
            }

            val pluginDir = File(pluginsDir, manifest.id)
            installPluginDirectory(manifestRoot, pluginDir)
            tempDir.deleteRecursively()

            // 写入完整性校验和
            runCatching {
                val checksum = computePluginChecksum(pluginDir)
                File(pluginDir, ".integrity").writeText(checksum)
            }

            loadPluginInfo(pluginDir)?.let {
                auditRepo?.log(
                    category = "plugin",
                    action = "installed",
                    target = manifest.id,
                    detail = "插件 ${manifest.name} (${manifest.id}) v${manifest.version} 已安装，作者: ${manifest.author}",
                    status = "success",
                )
                Result.success(it)
            } ?: Result.failure(IllegalStateException("无法加载插件信息"))
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * 从ZIP文件导入插件（旧版一次性导入，保留用于兼容）
     */
    suspend fun importFromZip(uri: Uri): Result<PluginInfo> {
        val tempFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.zip")
        var tempDir: File? = null
        return try {
            // 1. 复制到临时文件
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(IllegalStateException("无法读取文件"))

            // 2. 解压到临时目录
            val extractedDir = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}")
            tempDir = extractedDir
            unzip(tempFile, extractedDir)

            // 3. 查找manifest.json
            val manifestFile = findManifest(extractedDir)
                ?: return Result.failure(IllegalArgumentException("找不到 manifest.json"))

            // 4. 解析manifest
            val content = manifestFile.readText()
            val manifest = json.decodeFromString(PluginManifest.serializer(), content)
            val manifestRoot = manifestFile.parentFile ?: throw IllegalArgumentException("插件目录无效")
            validateManifest(manifest, manifestRoot)

            // 5. 验证入口文件
            val entryFile = SafeFileResolver.resolveInside(manifestRoot, manifest.entry)
            if (entryFile == null || !entryFile.isFile) {
                tempFile.delete()
                extractedDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("找不到入口文件: ${manifest.entry}"))
            }

            // 6. 安全安装；同 ID 插件视为升级，失败时恢复旧版
            val pluginDir = File(pluginsDir, manifest.id)
            installPluginDirectory(manifestRoot, pluginDir)
            runCatching {
                File(pluginDir, ".integrity").writeText(computePluginChecksum(pluginDir))
            }

            // 7. 清理临时文件
            tempFile.delete()
            extractedDir.deleteRecursively()

            // 9. 返回插件信息
            loadPluginInfo(pluginDir)?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("无法加载插件信息"))

        } catch (e: Exception) {
            tempDir?.deleteRecursively()
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 删除插件
     */
    fun deletePlugin(pluginId: String): Result<Unit> {
        return runCatching {
            require(pluginId.isNotBlank() && pluginId != "." && pluginId != "..") { "插件 ID 无效" }
            val root = pluginsDir.canonicalFile
            val pluginDir = File(root, pluginId).canonicalFile
            require(pluginDir.path.startsWith(root.path + File.separator)) { "插件路径无效" }
            if (!pluginDir.exists()) throw java.io.IOException("插件目录不存在: ${pluginDir.name}")
            if (!pluginDir.deleteRecursively() || pluginDir.exists()) {
                throw java.io.IOException("插件目录删除失败，请检查存储权限或文件占用: ${pluginDir.path}")
            }
        }
    }

    /**
     * 获取插件目录
     */
    fun getPluginDir(pluginId: String): File {
        return File(pluginsDir, pluginId)
    }

    /**
     * 解压ZIP文件
     */
    private fun unzip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: java.util.zip.ZipEntry? = zis.nextEntry
            while (entry != null) {
                val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                require(normalizedName.isNotBlank()) { "ZIP 包含空文件名" }
                val file = File(canonicalDest, normalizedName).canonicalFile
                require(file.toPath().startsWith(canonicalDest.toPath())) {
                    "ZIP 包含非法路径: ${entry.name}"
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { output ->
                        zis.copyTo(output)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    /**
     * 原子式安装或升级插件。只替换目标插件目录，不影响其他插件；复制失败时恢复旧版。
     */
    private fun installPluginDirectory(sourceDir: File?, pluginDir: File) {
        require(sourceDir != null && sourceDir.isDirectory) { "插件源目录无效" }
        val stagingDir = File(pluginsDir, ".${pluginDir.name}.installing")
        val backupDir = File(pluginsDir, ".${pluginDir.name}.backup")
        stagingDir.deleteRecursively()
        backupDir.deleteRecursively()
        try {
            sourceDir.copyRecursively(stagingDir, overwrite = true)
            if (pluginDir.exists()) {
                check(pluginDir.renameTo(backupDir)) { "无法备份旧版插件" }
            }
            check(stagingDir.renameTo(pluginDir)) { "无法启用新版插件" }
            backupDir.deleteRecursively()
        } catch (error: Throwable) {
            stagingDir.deleteRecursively()
            if (!pluginDir.exists() && backupDir.exists()) {
                backupDir.renameTo(pluginDir)
            }
            throw error
        }
    }

    /**
     * 查找manifest.json文件
     * 优先在根目录查找，然后在子目录中查找
     */
    private fun findManifest(dir: File): File? {
        // 优先在根目录找
        val rootManifest = File(dir, MANIFEST_FILE)
        if (rootManifest.exists()) {
            return rootManifest
        }

        // 在子目录中查找
        return dir.listFiles { file -> file.isDirectory }
            ?.asSequence()
            ?.map { subdir -> File(subdir, MANIFEST_FILE) }
            ?.firstOrNull { it.exists() }
    }

    /**
     * 计算插件目录的 SHA-256 校验和（排除 .integrity 文件本身，稳定排序）
     */
    private fun computePluginChecksum(dir: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        dir.walkTopDown()
            .filter { it.isFile && it.name != ".integrity" }
            .sortedBy { it.relativeTo(dir).path.replace('\\', '/') }
            .forEach { file ->
                digest.update(file.relativeTo(dir).path.replace('\\', '/').toByteArray(Charsets.UTF_8))
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateManifest(manifest: PluginManifest, pluginRoot: File?) {
        require(pluginRoot != null && pluginRoot.isDirectory) { "插件目录无效" }
        validatePluginId(manifest.id)
        require(SafeFileResolver.resolveInside(pluginRoot, manifest.entry)?.isFile == true) {
            "入口文件路径无效: ${manifest.entry}"
        }
        manifest.customPageWebView?.let { page ->
            require(SafeFileResolver.resolveInside(pluginRoot, page.entry)?.isFile == true) {
                "管理页面路径无效: ${page.entry}"
            }
        }
    }

    private fun validatePluginId(pluginId: String) {
        require(
            pluginId.isNotBlank() &&
                pluginId != "." &&
                pluginId != ".." &&
                '/' !in pluginId &&
                '\\' !in pluginId
        ) { "插件 ID 无效" }
    }
}
