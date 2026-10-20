package me.rerere.rikkahub.ui.components.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceDownloadPathTest {
    @Test fun acceptsUnicodeSpacesAndLiteralPlus() {
        assertEquals("目录/a b+c.zip", WorkspaceDownloadPath.parse("sandbox:/workspace/%E7%9B%AE%E5%BD%95/a%20b+c.zip"))
        assertEquals("result.zip", WorkspaceDownloadPath.parse("/workspace/result.zip"))
    }
    @Test fun rejectsTraversalHostFilesAndMalformedLinks() {
        listOf("sandbox:/workspace/../private", "sandbox:/workspace/%2e%2e/private",
            "sandbox:/workspace/a%5c..%5csecret", "sandbox:/etc/passwd", "file:///etc/passwd",
            "sandbox:/workspace/", "sandbox:/workspace/%00", "sandbox://host/workspace/a",
            "sandbox:/workspace/%zz", "https://example.com/workspace/a").forEach {
            assertNull(it, WorkspaceDownloadPath.parse(it))
        }
    }
    @Test fun offersDownloadsForOldInlinePathsWithoutChangingMessageText() {
        assertEquals(listOf("/workspace/我的 文件.zip"), WorkspaceDownloadPath.inlinePaths(
            "保存于 `/workspace/我的 文件.zip`，再次引用 `/workspace/我的 文件.zip`"))
    }
}
