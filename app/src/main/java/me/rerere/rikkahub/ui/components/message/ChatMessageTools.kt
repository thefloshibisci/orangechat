/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.FileDownload
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Zip02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatDraftContract
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.components.richtext.DiffView
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.parseDiffStats
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

internal object ToolNames {
    const val MEMORY = "memory_tool"
    const val SEARCH_WEB = "search_web"
    const val SCRAPE_WEB = "scrape_web"
    const val GET_TIME_INFO = "get_time_info"
    const val CLIPBOARD = "clipboard_tool"
    const val TTS = "text_to_speech"
    const val ASK_USER = "ask_user"
    const val USE_SKILL = "use_skill"
    const val WRITE_FILES = "write_files"
    const val ZIP_FILES = "zip_files"  // backward compat
    // 工作区 (rootfs) 工具: 走 WorkspaceRepository, 与上面的 WRITE_FILES/ZIP_FILES 完全独立
    const val WORKSPACE_READ_FILE = "workspace_read_file"
    const val WORKSPACE_WRITE_FILE = "workspace_write_file"
    const val WORKSPACE_EDIT_FILE = "workspace_edit_file"
    const val WORKSPACE_SHELL = "workspace_shell"
}

internal object MemoryActions {
    const val CREATE = "create"
    const val EDIT = "edit"
    const val DELETE = "delete"
}

private object ClipboardActions {
    const val READ = "read"
    const val WRITE = "write"
}

internal fun getToolIcon(toolName: String, action: String?) = when (toolName) {
    ToolNames.MEMORY -> when (action) {
        MemoryActions.CREATE, MemoryActions.EDIT -> HugeIcons.QuillWrite01
        MemoryActions.DELETE -> HugeIcons.Eraser
        else -> HugeIcons.QuillWrite01
    }

    ToolNames.SEARCH_WEB -> HugeIcons.Search01
    ToolNames.SCRAPE_WEB -> HugeIcons.GlobalSearch
    ToolNames.GET_TIME_INFO -> HugeIcons.Time02
    ToolNames.CLIPBOARD -> HugeIcons.Clipboard
    ToolNames.TTS -> HugeIcons.VolumeHigh
    ToolNames.ASK_USER -> HugeIcons.BubbleChatQuestion
    ToolNames.USE_SKILL -> HugeIcons.MagicWand01
    ToolNames.ZIP_FILES, ToolNames.WRITE_FILES -> HugeIcons.Zip02
    ToolNames.WORKSPACE_READ_FILE -> HugeIcons.FileView
    ToolNames.WORKSPACE_WRITE_FILE -> HugeIcons.FileAdd
    ToolNames.WORKSPACE_EDIT_FILE -> HugeIcons.FileEdit
    ToolNames.WORKSPACE_SHELL -> HugeIcons.ComputerTerminal01
    else -> HugeIcons.Tools
}

internal fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    allParts: List<UIMessagePart> = emptyList(),
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    conversationId: String? = null,
) {
    val isAskUser = tool.toolName == ToolNames.ASK_USER

    if (isAskUser) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }
    if (tool.toolName == LocalMomentsChatDraftContract.TOOL_NAME) {
        MomentsChatDraftToolStep(
            tool = tool,
            conversationId = conversationId,
            onToolApproval = onToolApproval,
        )
        return
    }
    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val isPending = tool.isPending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val arguments = tool.inputAsJson()
    val memoryAction = arguments.getStringContent("action")
    val workspaceMachine = workspaceToolMachine(tool.output)
    val content = if (tool.isExecuted && tool.toolName.startsWith("workspace_")) {
        workspaceToolContent(tool.output) ?: JsonObject(emptyMap())
    } else if (tool.isExecuted) {
        runCatching {
            JsonInstant.parseToJsonElement(
                tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            )
        }.getOrElse { JsonObject(emptyMap()) }
    } else {
        null
    }
    val workspaceError = if (tool.toolName.startsWith("workspace_")) content.getStringContent("error") else null
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    // Video analysis attachments feed the model; they are not assistant voice messages.
    val audios = if (tool.toolName in setOf("gongdu_read_video", ToolNames.TTS)) emptyList()
        else tool.output.filterIsInstance<UIMessagePart.Audio>()

    val title = when (tool.toolName) {
        ToolNames.MEMORY -> when (memoryAction) {
            MemoryActions.CREATE -> stringResource(R.string.chat_message_tool_create_memory)
            MemoryActions.EDIT -> stringResource(R.string.chat_message_tool_edit_memory)
            MemoryActions.DELETE -> stringResource(R.string.chat_message_tool_delete_memory)
            else -> stringResource(R.string.chat_message_tool_call_generic, ToolNaming.toDisplayName(tool.toolName))
        }

        ToolNames.SEARCH_WEB -> stringResource(
            R.string.chat_message_tool_search_web,
            arguments.getStringContent("query") ?: ""
        )

        ToolNames.SCRAPE_WEB -> stringResource(R.string.chat_message_tool_scrape_web)
        ToolNames.GET_TIME_INFO -> stringResource(R.string.chat_message_tool_get_time)
        ToolNames.CLIPBOARD -> when (memoryAction) {
            ClipboardActions.READ -> stringResource(R.string.chat_message_tool_clipboard_read)
            ClipboardActions.WRITE -> stringResource(R.string.chat_message_tool_clipboard_write)
            else -> stringResource(R.string.chat_message_tool_call_generic, ToolNaming.toDisplayName(tool.toolName))
        }

        ToolNames.TTS -> {
            val preview = arguments.getStringContent("text")?.let { text ->
                if (text.length > 24) text.take(24) + "…" else text
            } ?: ""
            "语音：$preview"
        }

        ToolNames.USE_SKILL -> {
            val skillName = arguments.getStringContent("name") ?: ""
            val path = arguments.getStringContent("path")
            if (path != null) "Skill: $skillName / $path" else "Skill: $skillName"
        }

        ToolNames.ZIP_FILES, ToolNames.WRITE_FILES -> {
            val zipName = arguments.getStringContent("zip_name") ?: ""
            val fileCount = arguments.jsonObjectOrNull?.get("files")?.jsonArrayOrNull?.size ?: 0
            val editCount = arguments.jsonObjectOrNull?.get("edits")?.jsonArrayOrNull?.size ?: 0
            val isEdit = arguments.getStringContent("base_files") == "previous" && editCount > 0
            when {
                isEdit && zipName.isNotBlank() -> "✏️ $zipName ($editCount edits)"
                zipName.isNotBlank() -> "📦 $zipName"
                else -> "💾 ${fileCount} files"
            }
        }

        ToolNames.WORKSPACE_READ_FILE -> {
            val path = arguments.getStringContent("path")
            if (path != null) stringResource(R.string.tool_ui_read_file, path)
            else stringResource(R.string.tool_ui_read_file_default)
        }

        ToolNames.WORKSPACE_WRITE_FILE -> {
            val path = arguments.getStringContent("path")
            if (path != null) stringResource(R.string.tool_ui_write_file, path)
            else stringResource(R.string.tool_ui_write_file_default)
        }

        ToolNames.WORKSPACE_EDIT_FILE -> {
            val path = arguments.getStringContent("path")
            if (path != null) stringResource(R.string.tool_ui_edit_file, path)
            else stringResource(R.string.tool_ui_edit_file_default)
        }

        ToolNames.WORKSPACE_SHELL -> {
            val command = arguments.getStringContent("command")
            if (command.isNullOrBlank()) {
                stringResource(R.string.tool_ui_shell_default)
            } else {
                val preview = command.replace("\n", " ").trim()
                val truncated = if (preview.length > 40) preview.take(40) + "…" else preview
                stringResource(R.string.tool_ui_shell, truncated)
            }
        }

        else -> stringResource(R.string.chat_message_tool_call_generic, ToolNaming.toDisplayName(tool.toolName))
    }

    // 判断是否有额外内容需要显示
    val hasExtraContent = when (tool.toolName) {
        ToolNames.MEMORY -> memoryAction in listOf(MemoryActions.CREATE, MemoryActions.EDIT) &&
            content.getStringContent("content") != null

        ToolNames.SEARCH_WEB -> content.getStringContent("answer") != null ||
            (content?.jsonObjectOrNull?.get("items")?.jsonArrayOrNull?.isNotEmpty() == true)

        ToolNames.SCRAPE_WEB -> arguments.getStringContent("url") != null
        ToolNames.TTS -> arguments.getStringContent("text") != null
        ToolNames.ZIP_FILES, ToolNames.WRITE_FILES -> tool.isExecuted && content != null
        ToolNames.WORKSPACE_READ_FILE -> content.getStringContent("text") != null
        ToolNames.WORKSPACE_WRITE_FILE -> arguments.getStringContent("text") != null
        ToolNames.WORKSPACE_EDIT_FILE -> workspaceEditDiffOf(tool) != null
        ToolNames.WORKSPACE_SHELL -> content != null
        else -> false
    } || workspaceError != null || isDenied || images.isNotEmpty() || audios.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = getToolIcon(tool.toolName, memoryAction),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Column {
                workspaceMachine?.let { machine ->
                    Text(machine, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { showDenyDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onToolApproval(tool.toolCallId, true, "") },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else {
            null
        },
        onClick = if (content != null || isPending || images.isNotEmpty() || audios.isNotEmpty() ||
            tool.toolName == ToolNames.WORKSPACE_EDIT_FILE ||
            tool.toolName == ToolNames.WORKSPACE_WRITE_FILE ||
            tool.toolName == ToolNames.WORKSPACE_READ_FILE
        ) {
            { showResult = true }
        } else {
            null
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    workspaceError?.let { error ->
                        Text(error.substringBefore("\n\tat "), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    if (tool.toolName == ToolNames.MEMORY &&
                        memoryAction in listOf(MemoryActions.CREATE, MemoryActions.EDIT)
                    ) {
                        content.getStringContent("content")?.let { memoryContent ->
                            Text(
                                text = memoryContent,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.shimmer(isLoading = loading),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (tool.toolName == ToolNames.SEARCH_WEB) {
                        content.getStringContent("answer")?.let { answer ->
                            Text(
                                text = answer,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.shimmer(isLoading = loading),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val items = content?.jsonObjectOrNull?.get("items")?.jsonArrayOrNull ?: emptyList()
                        if (items.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FaviconRow(
                                    urls = items.mapNotNull { it.getStringContent("url") },
                                    size = 18.dp,
                                )
                                Text(
                                    text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                    if (tool.toolName == ToolNames.SCRAPE_WEB) {
                        val url = arguments.getStringContent("url") ?: ""
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                    if (tool.toolName == ToolNames.TTS) {
                        SavedSpeechVoiceBar(
                            clips = tool.output.filterIsInstance<UIMessagePart.Audio>(),
                            loading = loading || !tool.isExecuted,
                            generationError = content.getStringContent("error"),
                        )
                    }
                    if ((tool.toolName == ToolNames.ZIP_FILES || tool.toolName == ToolNames.WRITE_FILES) && tool.isExecuted && content != null) {
                        val context = LocalContext.current
                        val zipName = content.getStringContent("zip_name") ?: "files.zip"
                        val totalFiles = content.getStringContent("total_files")?.toIntOrNull()
                            ?: content.jsonObjectOrNull?.get("files")?.jsonArrayOrNull?.size ?: 0

                        // 从工具执行结果中读取确切文件内容（不再猜数据源）
                        // write_files 工具现在在输出 JSON 中包含 files_content 字段
                        val fileContents = remember(content) {
                            val filesContentObj = content.jsonObjectOrNull?.get("files_content")?.jsonObjectOrNull
                            if (filesContentObj != null) {
                                filesContentObj.entries.mapNotNull { (name, element) ->
                                    runCatching {
                                        name to (element as kotlinx.serialization.json.JsonPrimitive).content
                                    }.getOrNull()
                                }
                            } else {
                                // 向后兼容: 如果工具结果中没有 files_content, 尝试从 arguments 提取 (full write 模式)
                                arguments.jsonObjectOrNull?.get("files")?.jsonArrayOrNull?.mapNotNull { fileElement ->
                                    val name = fileElement.getStringContent("name") ?: return@mapNotNull null
                                    val fileContent = fileElement.getStringContent("content") ?: return@mapNotNull null
                                    name to fileContent
                                } ?: emptyList()
                            }
                        }

                        val scope = rememberCoroutineScope()
                        val createZipLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.CreateDocument("application/zip")
                        ) { uri ->
                            uri?.let { destination ->
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        context.contentResolver.openOutputStream(destination)?.use { stream ->
                                            val zipOut = java.util.zip.ZipOutputStream(stream)
                                            fileContents.forEach { (name, content) ->
                                                zipOut.putNextEntry(java.util.zip.ZipEntry(name))
                                                zipOut.write(content.toByteArray(Charsets.UTF_8))
                                                zipOut.closeEntry()
                                            }
                                            zipOut.finish()
                                        }
                                    }.onFailure {
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, "Export failed: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "$totalFiles files",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(
                                onClick = { createZipLauncher.launch(zipName) },
                                enabled = fileContents.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.FileDownload,
                                    contentDescription = "Download ZIP",
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Download",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    if (tool.toolName == ToolNames.WORKSPACE_READ_FILE) {
                        val text = content.getStringContent("text")
                        if (text != null) {
                            WorkspaceFileContentSummary(
                                text = text,
                                path = arguments.getStringContent("path"),
                                loading = loading,
                            )
                        }
                    }
                    if (tool.toolName == ToolNames.WORKSPACE_WRITE_FILE) {
                        val text = arguments.getStringContent("text")
                        if (text != null) {
                            WorkspaceFileContentSummary(
                                text = text,
                                path = arguments.getStringContent("path"),
                                loading = loading,
                            )
                        }
                    }
                    if (tool.toolName == ToolNames.WORKSPACE_EDIT_FILE) {
                        val diff = workspaceEditDiffOf(tool)
                        if (diff != null) {
                            val stats = remember(diff) { parseDiffStats(diff) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "+${stats.additions}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DiffAddedColor,
                                )
                                Text(
                                    text = "-${stats.deletions}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DiffRemovedColor,
                                )
                            }
                            DiffView(
                                diff = diff,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 10,
                                showFileHeader = false,
                            )
                        }
                    }
                    if (tool.toolName == ToolNames.WORKSPACE_SHELL) {
                        WorkspaceShellSummary(
                            arguments = arguments,
                            content = content,
                            loading = loading,
                        )
                    }
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (audios.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            audios.fastForEach { audio ->
                                AudioPlayerBubble(url = audio.url)
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            }
        )
    }

    if (showResult) {
        ToolCallPreviewSheet(
            toolName = ToolNaming.toDisplayName(tool.toolName),
            rawToolName = tool.toolName,
            conversationId = conversationId,
            arguments = arguments,
            content = content,
            output = if (tool.toolName == "gongdu_read_video")
                tool.output.filterNot { it is UIMessagePart.Audio } else tool.output,
            onDismissRequest = { showResult = false }
        )
    }
}
