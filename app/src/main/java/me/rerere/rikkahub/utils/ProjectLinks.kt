package me.rerere.rikkahub.utils

import java.net.URI

/** This installation's project links; upstream attribution never supplies its update packages. */
internal object ProjectLinks {
    const val REPOSITORY = "thefloshibisci/orangechat"
    const val HOME = "https://github.com/$REPOSITORY"
    const val ISSUES = "$HOME/issues"
    const val RELEASES = "$HOME/releases"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val DOCUMENTATION = "file:///android_asset/help/orangechat-guide.html"
    const val LICENSE = "$HOME/blob/master/LICENSE"
    const val YASELLI_UPSTREAM = "https://github.com/yaselli/watermelon-chat"
    const val RIKKAHUB_UPSTREAM = "https://github.com/rikkahub/rikkahub"
}

internal fun aboutBuildLabel(
    version: String,
    code: String,
    commit: String,
    builtAt: String,
    applicationId: String,
    buildType: String,
): String = "$version / $code\n构建基线 $commit / $builtAt\n$applicationId / $buildType"

internal fun aboutUpdateStatus(state: UiState<UpdateInfo>, currentVersion: String): String = when (state) {
    UiState.Idle -> "仅检查本项目正式版"
    UiState.Loading -> "正在检查本项目正式版…"
    is UiState.Success -> if (Version(state.data.version) > Version(currentVersion)) {
        "发现新版本 ${state.data.version}，请在下方「版本发布」查看"
    } else {
        "当前已是最新版本（$currentVersion）"
    }
    is UiState.Error -> "检查失败：${state.error.message?.takeIf { it.isNotBlank() } ?: "请稍后重试"}"
}

/** A release body cannot redirect the updater to another fork, host or local path. */
internal fun isTrustedProjectDownload(url: String, name: String, tag: String? = null): Boolean = runCatching {
    if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk", RegexOption.IGNORE_CASE))) return false
    val parsed = URI(url)
    if (parsed.scheme != "https" || parsed.host != "github.com" || parsed.port != -1 ||
        parsed.rawUserInfo != null || parsed.rawQuery != null || parsed.rawFragment != null) return false
    val prefix = "/${ProjectLinks.REPOSITORY}/releases/download/"
    val path = parsed.rawPath ?: return false
    if (!path.startsWith(prefix)) return false
    val components = path.removePrefix(prefix).split('/')
    components.size == 2 && components[0].matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")) &&
        (tag == null || components[0] == tag) && components[1] == name
}.getOrDefault(false)

/** The personal liquid-glass build is a distinct package from either public release/debug channel. */
internal fun projectUpdateAssetMatchesChannel(name: String, applicationId: String): Boolean {
    val normalized = name.lowercase(java.util.Locale.ROOT)
    if (!normalized.endsWith(".apk")) return false
    val personal = "liquidglass" in normalized
    return when (applicationId) {
        "me.rerere.orangechat.liquidglass" -> personal
        "me.rerere.orangechat.debug" -> !personal && "debug" in normalized && "release" !in normalized
        "me.rerere.orangechat" -> !personal && "debug" !in normalized && "release" in normalized
        else -> false
    }
}

internal fun selectProjectUpdate(release: GithubRelease, applicationId: String): UpdateInfo {
    check(!release.draft && !release.prerelease &&
        release.tagName.matches(Regex("[vV]?\\d+(?:\\.\\d+){1,3}(?:\\+[A-Za-z0-9.-]+)?"))) {
        "更新源未返回可用的正式版本"
    }
    val downloads = release.assets.filter { asset ->
        asset.size > 0 && isTrustedProjectDownload(asset.browserDownloadUrl, asset.name, release.tagName) &&
            projectUpdateAssetMatchesChannel(asset.name, applicationId)
    }.map { asset ->
        val megabytes = asset.size.toDouble() / (1024 * 1024)
        val size = if (megabytes >= 1) String.format("%.1f MB", megabytes)
        else String.format("%.0f KB", asset.size.toDouble() / 1024)
        UpdateDownload(asset.name, asset.browserDownloadUrl, size)
    }
    check(downloads.isNotEmpty()) { "当前安装渠道暂无可直接覆盖的安装包，请在项目发布页核对" }
    return UpdateInfo(
        version = release.tagName.removePrefix("v").removePrefix("V"),
        publishedAt = release.publishedAt,
        changelog = release.body?.takeIf { it.isNotBlank() } ?: "暂无更新说明",
        downloads = downloads,
    )
}
