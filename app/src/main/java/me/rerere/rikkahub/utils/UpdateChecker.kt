/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.AppScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

class UpdateChecker(
    private val client: OkHttpClient,
    private val context: Context,
    private val appScope: AppScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val checkMutex = Mutex()
    private var lastCheckAt = 0L
    private var lastResult: UpdateInfo? = null
    @Volatile private var dismissedVersion: String? = null
    private val _prompt = MutableStateFlow<UpdateInfo?>(null)
    val prompt = _prompt.asStateFlow()
    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()
    private val preferences = context.getSharedPreferences("app_update_download", Context.MODE_PRIVATE)

    init {
        val downloadId = preferences.getLong("id", -1L)
        val item = preferences.getString("item", null)?.let {
            runCatching { json.decodeFromString<UpdateDownload>(it) }.getOrNull()
        }
        if (downloadId >= 0 && item != null) observeDownload(downloadId, item)
    }

    fun checkUpdate(force: Boolean = false): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        val info = checkMutex.withLock {
            val cached = lastResult
            if (!force && cached != null && System.currentTimeMillis() - lastCheckAt < 6 * 60 * 60 * 1000L) {
                return@withLock cached
            }
            val response = client.newCall(
                Request.Builder()
                    .url(ProjectLinks.LATEST_RELEASE_API)
                    .get()
                    .addHeader("User-Agent", "OrangeChat ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}")
                    .build()
            ).await()
            response.use {
                if (!it.isSuccessful) error("检查更新失败（HTTP ${it.code}）")
                val release = json.decodeFromString<GithubRelease>(it.body.string())
                selectProjectUpdate(release, BuildConfig.APPLICATION_ID, Build.SUPPORTED_ABIS.toList())
                    .also { lastResult = it; lastCheckAt = System.currentTimeMillis() }
            }
        }
        if (Version(info.version) > Version(BuildConfig.VERSION_NAME) && (force || dismissedVersion != info.version)) {
            _prompt.value = info
        }
        emit(UiState.Success(info))
    }.catch {
        if (it is CancellationException) throw it
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    fun presentUpdate(info: UpdateInfo) { _prompt.value = info }

    fun dismissPrompt() {
        dismissedVersion = _prompt.value?.version
        _prompt.value = null
    }

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        if (_downloadState.value is UpdateDownloadState.Downloading) return
        if (!isTrustedProjectDownload(download.url, download.name) ||
            !projectUpdateAssetMatchesChannel(download.name, context.packageName) ||
            !projectUpdateAssetMatchesAbi(download.name, Build.SUPPORTED_ABIS.toList())) {
            _downloadState.value = UpdateDownloadState.Failed("此安装包不适用于当前安装渠道或设备")
            return
        }
        runCatching {
            val directory = File(checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
                "更新下载目录暂不可用，请稍后重试"
            }, "updates")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建更新下载目录" }
            val request = DownloadManager.Request(download.url.toUri()).apply {
                // 设置下载时通知栏的标题和描述
                setTitle(download.name)
                setDescription("正在下载更新包...")
                // 下载完成后通知栏可见
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 允许在移动网络和WiFi下下载
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                // 设置文件保存路径
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS,
                    "updates/${System.currentTimeMillis()}-${download.name}")
                // 允许下载的文件类型
                setMimeType("application/vnd.android.package-archive")
            }
            // 获取系统的DownloadManager
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            preferences.edit().putLong("id", id).putString("item", json.encodeToString(download)).apply()
            observeDownload(id, download)
        }.onFailure {
            _downloadState.value = UpdateDownloadState.Failed(it.message ?: "无法开始下载，请稍后重试")
        }
    }

    private fun observeDownload(id: Long, download: UpdateDownload) {
        _downloadState.value = UpdateDownloadState.Downloading(download, 0, download.sizeBytes)
        appScope.launch(Dispatchers.IO) {
            try {
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                while (true) {
                    val finished = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        check(cursor.moveToFirst()) { "下载记录已被移除，请重试" }
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val received = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0)
                        _downloadState.value = UpdateDownloadState.Downloading(download, received, total)
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).toUri()
                                check(local.scheme == "file") { "无法读取已下载的安装包" }
                                val file = File(checkNotNull(local.path))
                                try {
                                    verifyDownloadedUpdate(file, download)
                                    _downloadState.value = UpdateDownloadState.Ready(download, file.absolutePath)
                                } catch (error: Exception) {
                                    file.delete()
                                    throw error
                                }
                                true
                            }
                            DownloadManager.STATUS_FAILED -> error("下载失败（${cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}），请重试")
                            else -> false
                        }
                    }
                    if (finished) break
                    delay(700)
                }
                // Retain the system download ID so a process restart can restore the verified install action.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                preferences.edit().clear().apply()
                _downloadState.value = UpdateDownloadState.Failed(error.message ?: "下载失败，请稍后重试")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyDownloadedUpdate(file: File, download: UpdateDownload) {
        check(file.isFile && file.length() > 0) { "安装包为空或不存在" }
        if (download.sizeBytes > 0) check(file.length() == download.sizeBytes) { "安装包不完整，请重新下载" }
        download.digest?.takeIf { it.startsWith("sha256:") }?.let { digest ->
            val expected = digest.removePrefix("sha256:")
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) }
            }
            check(sha.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)) {
                "安装包校验失败，请重新下载"
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("下载内容不是有效的安装包")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidateCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        fun signatures(info: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                .orEmpty().map { signature ->
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
                }.toSet()
        val existingSigners = signatures(installed)
        verifyUpdateIdentity(context.packageName, candidate.packageName, installedCode, candidateCode,
            existingSigners, signatures(candidate))
    }

    fun installDownloadedUpdate(context: Context, ready: UpdateDownloadState.Ready) {
        appScope.launch(Dispatchers.IO) {
            try {
                val file = File(ready.path)
                verifyDownloadedUpdate(file, ready.download)
                withContext(Dispatchers.Main) {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        Toast.makeText(context, "允许安装后，请返回并再次点击「安装更新」", Toast.LENGTH_LONG).show()
                    } else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _downloadState.value = UpdateDownloadState.Failed(error.message ?: "无法打开安装程序，请重新下载")
            }
        }
    }
}

internal fun verifyUpdateIdentity(
    installedPackage: String,
    candidatePackage: String,
    installedVersion: Long,
    candidateVersion: Long,
    installedSigners: Set<String>,
    candidateSigners: Set<String>,
) {
    check(candidatePackage == installedPackage) { "安装包与当前应用不匹配" }
    check(candidateVersion > installedVersion) { "安装包版本未高于当前版本" }
    check(installedSigners.isNotEmpty() && candidateSigners == installedSigners) {
        "安装包签名与当前应用不一致，无法覆盖更新"
    }
}

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val download: UpdateDownload, val received: Long, val total: Long) : UpdateDownloadState
    data class Ready(val download: UpdateDownload, val path: String) : UpdateDownloadState
    data class Failed(val message: String) : UpdateDownloadState
}

/** An older finished download must never masquerade as the package offered by a newer release. */
internal fun updateDownloadForPrompt(info: UpdateInfo, state: UpdateDownloadState): UpdateDownloadState =
    if (state is UpdateDownloadState.Ready && info.downloads.none {
        it.name == state.download.name && it.url == state.download.url
    }) UpdateDownloadState.Idle else state

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String,
    val sizeBytes: Long = 0,
    val digest: String? = null,
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

/** GitHub Release API 返回结构（仅取需要的字段） */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("published_at") val publishedAt: String,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

@Serializable
data class GithubAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val digest: String? = null,
)

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
