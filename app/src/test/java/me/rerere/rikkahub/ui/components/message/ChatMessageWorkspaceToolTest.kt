/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessageWorkspaceToolTest {

    @Test
    fun `languageOf resolves known extensions case-insensitively and falls back to plaintext`() {
        val cases = mapOf(
            "src/Main.kt" to "kotlin",
            "build.gradle.kts" to "kotlin",
            "App.JAVA" to "java",
            "index.mjs" to "javascript",
            "App.tsx" to "tsx",
            "component.ts" to "typescript",
            "worker.py" to "python",
            "script.sh" to "bash",
            "config.json" to "json",
            "docker-compose.yml" to "yaml",
            "README.markdown" to "markdown",
            "Makefile" to "plaintext",
            null to "plaintext",
        )

        for ((path, expected) in cases) {
            assertEquals("path=$path", expected, languageOf(path))
        }
    }

    @Test
    fun `workspaceEditDiffOf extracts diff metadata when present and ignores non-diff output`() {
        val expectedDiff = "--- a/Foo.kt\n+++ b/Foo.kt\n@@ -1 +1 @@\n-old\n+new"
        val withDiff = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "workspace_edit_file",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    text = """{"ok":true}""",
                    metadata = buildJsonObject { put("diff", expectedDiff) },
                ),
            ),
        )
        assertEquals(expectedDiff, workspaceEditDiffOf(withDiff))

        val withoutDiff = UIMessagePart.Tool(
            toolCallId = "call-2",
            toolName = "workspace_edit_file",
            input = "{}",
            output = listOf(UIMessagePart.Text(text = """{"diff":"body only"}""")),
        )
        assertNull(workspaceEditDiffOf(withoutDiff))

        val nonPrimitiveDiff = UIMessagePart.Tool(
            toolCallId = "call-3",
            toolName = "workspace_edit_file",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    text = """{"ok":true}""",
                    metadata = buildJsonObject { put("diff", buildJsonObject { put("line", 1) }) },
                ),
            ),
        )
        assertNull(workspaceEditDiffOf(nonPrimitiveDiff))

        val emptyOutput = UIMessagePart.Tool(
            toolCallId = "call-4",
            toolName = "workspace_edit_file",
            input = "{}",
        )
        assertNull(workspaceEditDiffOf(emptyOutput))
    }
}
