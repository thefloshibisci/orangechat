/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.HighlightText
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.components.richtext.DiffView
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.parseDiffStats
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

// ==================== 工作区 (workspace_*) 工具 UI ====================
// 以下组件服务 workspace_read_file / workspace_write_file / workspace_edit_file /
// workspace_shell 四个走 WorkspaceRepository (rootfs) 的工具, 与上面的 write_files/
// zip_files (ZipFilesTool) 完全独立, 两套并存互不影响。

private const val WORKSPACE_FILE_SUMMARY_MAX_LINES = 10
private const val WORKSPACE_SHELL_SUMMARY_MAX_LINES = 8

/**
 * 从 workspace_edit_file 输出部件的 metadata 读取全文件 diff。
 * workspace_edit_file 执行后会把 unified diff 放在输出 Text part 的
 * metadata["diff"] 里 (见 WorkspaceTools.kt), 不会随工具结果发给 API。
 */
internal fun workspaceEditDiffOf(tool: UIMessagePart.Tool): String? =
    workspaceToolDiff(tool.output)

/** 由文件扩展名推断语法高亮语言 */
internal fun languageOf(path: String?): String = when (
    path?.substringAfterLast('.', "")?.lowercase().orEmpty()
) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs" -> "javascript"
    "ts" -> "typescript"
    "tsx" -> "tsx"
    "jsx" -> "jsx"
    "py" -> "python"
    "rb" -> "ruby"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "cc", "cxx", "hpp", "hxx" -> "cpp"
    "cs" -> "csharp"
    "swift" -> "swift"
    "php" -> "php"
    "sh", "bash", "zsh" -> "bash"
    "json" -> "json"
    "xml" -> "xml"
    "html", "htm" -> "html"
    "css" -> "css"
    "scss" -> "scss"
    "yaml", "yml" -> "yaml"
    "toml" -> "toml"
    "md", "markdown" -> "markdown"
    "sql" -> "sql"
    "gradle" -> "groovy"
    else -> "plaintext"
}

/** 内联摘要: 按扩展名语法高亮展示文件内容首部若干行 */
@Composable
internal fun WorkspaceFileContentSummary(text: String, path: String?, loading: Boolean) {
    val preview = remember(text) {
        text.lineSequence().take(WORKSPACE_FILE_SUMMARY_MAX_LINES).joinToString("\n")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .shimmer(isLoading = loading),
    ) {
        HighlightText(
            code = preview,
            language = languageOf(path),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = WORKSPACE_FILE_SUMMARY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** BottomSheet 详情: 文件路径 + 按扩展名语法高亮的完整内容 */
@Composable
internal fun WorkspaceFileContentPreview(path: String?, code: String?) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = path ?: stringResource(R.string.tool_ui_file),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (code != null) {
            HighlightCodeBlock(
                code = code,
                language = languageOf(path),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** workspace_edit_file 详情: 路径 + 增删统计 + 完整 diff view; 无 diff 时回退到通用预览 */
@Composable
internal fun WorkspaceEditFilePreview(
    arguments: JsonElement,
    toolName: String,
    output: List<UIMessagePart>,
    memoryRepo: MemoryRepository,
    scope: CoroutineScope,
    onDismissRequest: () -> Unit,
) {
    val diff = workspaceToolDiff(output)
    if (diff == null) {
        GenericToolPreview(
            toolName = toolName,
            arguments = arguments,
            output = output,
            isMemoryOperation = false,
            memoryId = null,
            memoryRepo = memoryRepo,
            scope = scope,
            onDismissRequest = onDismissRequest,
        )
        return
    }
    val stats = remember(diff) { parseDiffStats(diff) }
    val path = arguments.getStringContent("path")
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = path ?: toolName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "+${stats.additions}",
                style = MaterialTheme.typography.labelMedium,
                color = DiffAddedColor,
            )
            Text(
                text = "-${stats.deletions}",
                style = MaterialTheme.typography.labelMedium,
                color = DiffRemovedColor,
            )
        }
        DiffView(
            diff = diff,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** workspace_shell 内联摘要: 退出状态 + stdout/stderr 首部若干行 */
@Composable
internal fun WorkspaceShellSummary(
    arguments: JsonElement,
    content: JsonElement?,
    loading: Boolean,
) {
    if (content == null) return
    val combined = remember(content) {
        listOf(content.getStringContent("stdout"), content.getStringContent("stderr"))
            .filterNot { it.isNullOrBlank() }
            .joinToString("\n")
            .trim()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WorkspaceShellExitStatus(content, MaterialTheme.typography.labelSmall)
        if (combined.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .shimmer(isLoading = loading),
            ) {
                Text(
                    text = combined.lineSequence().take(WORKSPACE_SHELL_SUMMARY_MAX_LINES).joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = WORKSPACE_SHELL_SUMMARY_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** workspace_shell BottomSheet 详情: 命令 + cwd + stdout/stderr */
@Composable
internal fun WorkspaceShellPreview(
    arguments: JsonElement,
    content: JsonElement?,
) {
    if (content == null) return
    val command = arguments.getStringContent("command").orEmpty()
    val cwd = arguments.getStringContent("cwd")
    val stdout = content.getStringContent("stdout").orEmpty()
    val stderr = content.getStringContent("stderr").orEmpty()
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.tool_ui_shell_default),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            WorkspaceShellExitStatus(content, MaterialTheme.typography.labelMedium)
        }
        HighlightCodeBlock(
            code = if (cwd.isNullOrBlank()) command else "# cwd: $cwd\n$command",
            language = "bash",
            modifier = Modifier.fillMaxWidth(),
        )
        if (stdout.isNotEmpty()) {
            Text(text = "stdout", style = MaterialTheme.typography.labelMedium)
            HighlightCodeBlock(
                code = stdout,
                language = "plaintext",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (stderr.isNotEmpty()) {
            Text(
                text = "stderr",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            HighlightCodeBlock(
                code = stderr,
                language = "plaintext",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Shell 退出状态文本: exit code 为 0 显示绿色, 超时或非零显示错误色 */
@Composable
private fun WorkspaceShellExitStatus(content: JsonElement, style: TextStyle) {
    val exitCode = content.jsonObjectOrNull?.get("exitCode")?.jsonPrimitiveOrNull?.intOrNull
    val timedOut = content.jsonObjectOrNull?.get("timedOut")?.jsonPrimitiveOrNull?.booleanOrNull ?: false
    val ok = !timedOut && exitCode == 0
    Text(
        text = when {
            timedOut -> stringResource(R.string.tool_ui_shell_timeout)
            else -> stringResource(R.string.tool_ui_shell_exit, exitCode?.toString() ?: "?")
        },
        style = style,
        color = if (ok) DiffAddedColor else MaterialTheme.colorScheme.error,
    )
}
