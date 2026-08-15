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
        return try {
            val tempFile = File(context.cacheDir, "plugin_preview_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(IllegalStateException("无法读取文件"))

            val tempDir = File(context.cacheDir, "plugin_preview_${System.currentTimeMillis()}")
            unzip(tempFile, tempDir)

            val manifestFile = findManifest(tempDir)
                ?: run {
                    tempFile.delete()
                    tempDir.deleteRecursively()
                    return Result.failure(IllegalArgumentException("找不到 manifest.json"))
                }

            val manifest = json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())

            // 预览阶段保留 tempFile 和 tempDir，供后续 completeImport 使用
            Result.success(manifest to tempDir)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 完成插件导入（在 previewFromZip 后调用）
     * @param manifest 预览阶段解析的 manifest
     * @param tempDir 预览阶段解压的临时目录
     */
    suspend fun completeImport(manifest: PluginManifest, tempDir: File): Result<PluginInfo> {
        return try {
            val manifestFile = findManifest(tempDir)
                ?: run {
                    tempDir.deleteRecursively()
                    return Result.failure(IllegalArgumentException("找不到 manifest.json"))
                }

            val entryFile = File(manifestFile.parentFile, manifest.entry)
            if (!entryFile.exists()) {
                tempDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("找不到入口文件: ${manifest.entry}"))
            }

            val pluginDir = File(pluginsDir, manifest.id)
            installPluginDirectory(manifestFile.parentFile, pluginDir)
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
        return try {
            // 1. 复制到临时文件
            val tempFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(IllegalStateException("无法读取文件"))

            // 2. 解压到临时目录
            val tempDir = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}")
            unzip(tempFile, tempDir)

            // 3. 查找manifest.json
            val manifestFile = findManifest(tempDir)
                ?: return Result.failure(IllegalArgumentException("找不到 manifest.json"))

            // 4. 解析manifest
            val content = manifestFile.readText()
            val manifest = json.decodeFromString(PluginManifest.serializer(), content)

            // 5. 验证入口文件
            val entryFile = File(manifestFile.parentFile, manifest.entry)
            if (!entryFile.exists()) {
                tempFile.delete()
                tempDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("找不到入口文件: ${manifest.entry}"))
            }

            // 6. 安全安装；同 ID 插件视为升级，失败时恢复旧版
            val pluginDir = File(pluginsDir, manifest.id)
            installPluginDirectory(manifestFile.parentFile, pluginDir)

            // 7. 清理临时文件
            tempFile.delete()
            tempDir.deleteRecursively()

            // 9. 返回插件信息
            loadPluginInfo(pluginDir)?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("无法加载插件信息"))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 删除插件
     */
    fun deletePlugin(pluginId: String): Boolean {
        val pluginDir = File(pluginsDir, pluginId)
        return if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        } else {
            false
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
            check(sourceDir.copyRecursively(stagingDir, overwrite = true)) { "复制插件文件失败" }
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
                digest.update(file.readBytes())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
