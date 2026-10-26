package me.rerere.rikkahub.utils

import org.junit.Assert.*
import org.junit.Test

class ProjectLinksTest {
    @Test fun manualAboutCheckReportsProgressNewVersionAndMeaningfulChannelFailure() {
        assertEquals("仅检查本项目正式版", aboutUpdateStatus(UiState.Idle, "2.2.4"))
        assertTrue(aboutUpdateStatus(UiState.Loading, "2.2.4").contains("正在检查"))
        val newer = UpdateInfo("2.2.5", "2026-10-26T00:00:00Z", "notes", emptyList())
        assertTrue(aboutUpdateStatus(UiState.Success(newer), "2.2.4").contains("发现新版本 2.2.5"))
        assertTrue(aboutUpdateStatus(UiState.Success(newer.copy(version = "2.2.4")), "2.2.4").contains("已是最新"))
        assertTrue(aboutUpdateStatus(UiState.Success(newer.copy(version = "2.2.3")), "2.2.4").contains("已是最新"))
        assertTrue(aboutUpdateStatus(UiState.Error(IllegalStateException("当前安装渠道暂无可直接覆盖的安装包")), "2.2.4")
            .contains("当前安装渠道暂无可直接覆盖的安装包"))
        assertTrue(aboutUpdateStatus(UiState.Error(Exception()), "2.2.4").contains("请稍后重试"))
    }

    private val personal = "OrangeChat-Liquidglass-2.2.5-arm64.apk"
    private fun url(name: String = personal, tag: String = "v2.2.5") =
        "${ProjectLinks.RELEASES}/download/$tag/$name"

    @Test fun localProjectLinksAndUpstreamAttributionStaySeparate() {
        assertEquals("thefloshibisci/orangechat", ProjectLinks.REPOSITORY)
        for (link in listOf(ProjectLinks.HOME, ProjectLinks.ISSUES, ProjectLinks.RELEASES,
            ProjectLinks.LICENSE, ProjectLinks.LATEST_RELEASE_API)) {
            assertTrue(link, link.contains("thefloshibisci/orangechat"))
            assertFalse(link, link.contains("sue1231513") || link.contains("yaselli"))
        }
        assertEquals("https://github.com/yaselli/watermelon-chat", ProjectLinks.YASELLI_UPSTREAM)
        assertEquals("https://github.com/rikkahub/rikkahub", ProjectLinks.RIKKAHUB_UPSTREAM)
        assertEquals("file:///android_asset/help/orangechat-guide.html", ProjectLinks.DOCUMENTATION)
    }

    @Test fun bundledGuideBelongsToThisProjectAndHasNoLegacyDocumentationRedirect() {
        val guide = checkNotNull(listOf(java.io.File("src/main/assets/help/orangechat-guide.html"),
            java.io.File("app/src/main/assets/help/orangechat-guide.html")).firstOrNull { it.isFile }).readText()
        assertTrue(guide.contains("西瓜瓣 · 使用说明"))
        assertTrue(guide.contains("https://github.com/thefloshibisci/orangechat"))
        assertFalse(guide.contains("sue1231513") || guide.contains("yaselli"))
        assertFalse(guide.contains("<script", ignoreCase = true))
    }

    @Test fun aboutMetadataUsesOnlyTheCurrentBuildInputs() {
        val label = aboutBuildLabel("2.2.4", "160", "local123", "2026-10-25 22:00", "me.rerere.orangechat.liquidglass", "debug")
        assertTrue(label.contains("2.2.4 / 160"))
        assertTrue(label.contains("local123"))
        assertTrue(label.contains("me.rerere.orangechat.liquidglass / debug"))
        assertFalse(label.contains("2.3.3") || label.contains("138faf9") || label.contains("168"))
    }

    @Test fun trustsOnlyExactOwnRepositoryReleaseAssetAndMatchingTag() {
        assertTrue(isTrustedProjectDownload(url(), personal, "v2.2.5"))
        assertFalse(isTrustedProjectDownload(url(), personal, "v2.2.6"))
        assertFalse(isTrustedProjectDownload(url(), "other.apk"))
        for (candidate in listOf(
            url().replace("thefloshibisci/orangechat", "yaselli/watermelon-chat"),
            url().replace("github.com", "github.com.evil.test"),
            url().replace("https://", "http://"),
            url().replace("github.com/", "github.com@evil.test/"),
            url() + "?redirect=https://evil.test", url() + "#other",
            url().replace("/download/", "/download/%2e%2e/"),
            url().replace("v2.2.5/", "v2.2.5%2Fother/"),
        )) assertFalse(candidate, isTrustedProjectDownload(candidate, personal))
    }

    @Test fun personalReleaseAndDebugPackagesNeverShareAnUpdateAsset() {
        val release = "app-arm64-v8a-release.apk"
        val debug = "OrangeChat-2.2.5-debug-arm64.apk"
        assertTrue(projectUpdateAssetMatchesChannel(personal, "me.rerere.orangechat.liquidglass"))
        assertFalse(projectUpdateAssetMatchesChannel(release, "me.rerere.orangechat.liquidglass"))
        assertFalse(projectUpdateAssetMatchesChannel(debug, "me.rerere.orangechat.liquidglass"))
        assertTrue(projectUpdateAssetMatchesChannel(release, "me.rerere.orangechat"))
        assertFalse(projectUpdateAssetMatchesChannel(personal, "me.rerere.orangechat"))
        assertTrue(projectUpdateAssetMatchesChannel(debug, "me.rerere.orangechat.debug"))
        assertFalse(projectUpdateAssetMatchesChannel(release, "me.rerere.orangechat.debug"))
        assertFalse(projectUpdateAssetMatchesChannel(personal, "other.fork"))
    }

    @Test fun releaseSelectionFiltersUntrustedWrongChannelAndNonApkAssets() {
        val release = release(listOf(
            GithubAsset(personal, 1024, url()),
            GithubAsset("app-arm64-v8a-release.apk", 1024, url("app-arm64-v8a-release.apk")),
            GithubAsset("release-metadata.json", 1024, url("release-metadata.json")),
            GithubAsset(personal, 1024, url().replace("thefloshibisci/orangechat", "yaselli/watermelon-chat")),
            GithubAsset(personal, 1024, url(tag = "v2.0.0")),
            GithubAsset(personal, -1, url()),
        ))
        val update = selectProjectUpdate(release, "me.rerere.orangechat.liquidglass")
        assertEquals("2.2.5", update.version)
        assertEquals(listOf(personal), update.downloads.map { it.name })
        assertEquals("notes", update.changelog)
    }

    @Test fun incompatibleStableReleaseReportsMissingChannelInsteadOfOfferingAnotherPackage() {
        val release = release(listOf(GithubAsset("app-arm64-v8a-release.apk", 10, url("app-arm64-v8a-release.apk"))))
        val error = runCatching { selectProjectUpdate(release, "me.rerere.orangechat.liquidglass") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("当前安装渠道"))
    }

    @Test fun draftPrereleaseAndPrereleaseTagCannotEnterStableUpdateChannel() {
        val release = release(listOf(GithubAsset(personal, 1024, url())))
        for (candidate in listOf(release.copy(draft = true), release.copy(prerelease = true),
            release.copy(tagName = "v2.2.5-beta.1"))) {
            assertNotNull(runCatching { selectProjectUpdate(candidate, "me.rerere.orangechat.liquidglass") }.exceptionOrNull())
        }
    }

    private fun release(assets: List<GithubAsset>) = GithubRelease(
        tagName = "v2.2.5", publishedAt = "2026-10-25T14:00:00Z", body = "notes", assets = assets,
    )
}
