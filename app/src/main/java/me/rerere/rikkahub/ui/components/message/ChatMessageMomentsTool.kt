/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatDraftPreview
import me.rerere.rikkahub.data.ai.tools.LocalMomentsChatSelectionStore
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * `moments_chat_draft` is a native approval gate whose payload is a whole card, not an id list.
 * It renders the host-generated preview inline before the approve/deny buttons, so approving is
 * always a decision made against the complete content (50 messages / 32 KB budget included).
 */
@Composable
internal fun ChainOfThoughtScope.MomentsChatDraftToolStep(
    tool: UIMessagePart.Tool,
    conversationId: String?,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)?,
) {
    var expanded by remember(tool.toolCallId) { mutableStateOf(true) }
    var showPreviewSheet by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    val pending = tool.isPending
    val denied = tool.approvalState is ToolApprovalState.Denied
    val arguments = tool.inputAsJson()
    val preview = remember(conversationId, arguments, pending, tool.isExecuted) {
        LocalMomentsChatSelectionStore.preview(conversationId, arguments)
    }
    val receipt = remember(tool.output) { momentsDraftReceipt(tool.output) }
    val approveEnabled = preview.canWrite

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            Icon(
                imageVector = HugeIcons.MagicWand01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LocalContentColor.current.copy(alpha = 0.7f),
            )
        },
        label = {
            Column {
                Text(
                    text = if (pending) "待批准：朋友圈草稿" else "朋友圈草稿",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pending) {
                    Text(
                        "完整预览如下；批准只会保存草稿（作者是你），不会发布",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        extra = if (pending && onToolApproval != null) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(
                        onClick = { showDenyDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onToolApproval(tool.toolCallId, true, "") },
                        enabled = approveEnabled,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        } else {
            null
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MomentsChatDraftPreviewContent(preview = preview, receipt = receipt, denied = denied)
                if (!approveEnabled && pending) {
                    Text(
                        "完整预览不可用或超出上限，不能批准；请先拒绝，再重新选择消息发起。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { showPreviewSheet = true }) {
                    Text("在面板中查看完整预览")
                }
            }
        },
    )

    if (showPreviewSheet) {
        ToolCallPreviewSheet(
            toolName = ToolNaming.toDisplayName(tool.toolName),
            rawToolName = tool.toolName,
            conversationId = conversationId,
            arguments = arguments,
            content = null,
            output = tool.output,
            onDismissRequest = { showPreviewSheet = false },
        )
    }

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            },
        )
    }
}

@Composable
internal fun MomentsChatDraftPreviewContent(
    preview: LocalMomentsChatDraftPreview,
    receipt: MomentsDraftReceipt?,
    denied: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!preview.available) {
                Text(
                    preview.unavailableReason ?: "无法生成完整预览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (preview.requestedMessageIds.isNotEmpty()) {
                    Text(
                        "工具请求的消息 id：${preview.requestedMessageIds.joinToString()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("草稿预览", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${preview.selectedCount} 条 · 正文 ${preview.totalChars} 字 · " +
                            if (preview.includeImages) {
                                "图片 ${preview.imageCount} 张"
                            } else {
                                "未包含图片（${preview.omittedImages} 张已排除）"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                preview.previewMessages.forEach { message ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (message.role == MessageRole.USER) "我 · ${message.name}" else message.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (message.text.isNotBlank()) {
                            // Complete text on purpose: the approver must see exactly what is written.
                            Text(message.text, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (message.imageUrls.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(message.imageUrls) { imageUrl ->
                                    ZoomableAsyncImage(
                                        model = imageUrl,
                                        contentDescription = "聊天图片",
                                        modifier = Modifier
                                            .height(72.dp)
                                            .wrapContentWidth()
                                            .clip(RoundedCornerShape(8.dp)),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
                Text(
                    "上限：${LocalDataApi.MAX_CHAT_MESSAGES} 条 / ${LocalDataApi.MAX_CHAT_TOTAL_CHARS} 字 / " +
                        "${LocalDataApi.MAX_MEDIA_PER_POST} 图 / 快照 " +
                        "${LocalDataApi.MAX_CHAT_SNAPSHOT_BYTES / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preview.problems.isNotEmpty()) {
                    Text(
                        "未写入：超过本机朋友圈卡片上限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    preview.problems.forEach { problem ->
                        Text("• $problem", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (preview.excludedMessages > 0 || preview.excludedParts > 0) {
                    Text(
                        "已自动排除 ${preview.excludedMessages} 条系统/工具消息、" +
                            "${preview.excludedParts} 处非正文内容（思考/工具/未选图片等）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    LocalMomentsChatDraftPreview.AUTHOR_NOTE + "未发布。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (denied) {
                Text(
                    "已拒绝这次草稿写入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (receipt != null) {
                HorizontalDivider()
                if (receipt.ok) {
                    Text(
                        "已保存到本机朋友圈草稿：${receipt.messageCount} 条消息、${receipt.imageCount} 张图片；未发布。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "请到「本机朋友圈 → 草稿」预览、编辑并自行发布。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "写入失败：${receipt.errorMessage ?: receipt.errorCode ?: "未知错误"}；未发布。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

internal data class MomentsDraftReceipt(
    val ok: Boolean,
    val messageCount: Int,
    val imageCount: Int,
    val draftId: String?,
    val errorCode: String?,
    val errorMessage: String?,
)

internal fun momentsDraftReceipt(output: List<UIMessagePart>): MomentsDraftReceipt? {
    val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.trim()
    if (text.isBlank()) return null
    val root = runCatching { JsonInstant.parseToJsonElement(text).jsonObjectOrNull }.getOrNull() ?: return null
    val draft = root["draft"]?.jsonObjectOrNull
    val error = root["error"]?.jsonObjectOrNull
    return MomentsDraftReceipt(
        ok = root["ok"]?.jsonPrimitiveOrNull?.booleanOrNull == true,
        messageCount = draft?.get("messageCount")?.jsonPrimitiveOrNull?.intOrNull ?: 0,
        imageCount = draft?.get("imageCount")?.jsonPrimitiveOrNull?.intOrNull ?: 0,
        draftId = draft?.get("id")?.jsonPrimitiveOrNull?.contentOrNull,
        errorCode = error?.get("code")?.jsonPrimitiveOrNull?.contentOrNull,
        errorMessage = error?.get("message")?.jsonPrimitiveOrNull?.contentOrNull,
    )
}
