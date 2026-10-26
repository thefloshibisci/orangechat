/*
 * 橘瓣 OrangeChat，基于 RikkaHub；GNU AGPL v3，见 LICENSE。
 */
package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.theme.materialModeBorderStroke
import kotlin.uuid.Uuid

private class QueueEditDraft(val message: QueuedMessage) {
    val input = ChatInputState().apply {
        editingMessage = message.id
        setContents(message.parts)
    }
    var finished = false
}

@Composable
internal fun MessageQueuePanel(
    state: MessageQueueState,
    onRemove: (Uuid) -> Unit,
    onBeginEdit: (Uuid) -> QueuedMessage?,
    onFinishEdit: (Uuid, List<UIMessagePart>?) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    var editing by remember { mutableStateOf<QueueEditDraft?>(null) }
    LaunchedEffect(state.messages, editing?.message?.id) {
        editing?.let { draft ->
            if (state.messages.none { it.id == draft.message.id }) editing = null
        }
    }
    if (state.messages.isNotEmpty()) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = materialModeBorderStroke(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("chat_message_queue"),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.paused) "队列已暂停 · ${state.messages.size} 条" else "排队消息 · ${state.messages.size} 条",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = if (state.paused) onResume else onPause) {
                        Text(if (state.paused) "继续发送" else "暂停队列")
                    }
                }
                Text("待发消息暂存于本次运行，进程结束后不保留。", style = MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "${index + 1}. ${queuedMessagePreview(message.parts)}",
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                enabled = !message.isEditing,
                                onClick = { onBeginEdit(message.id)?.let { editing = QueueEditDraft(it) } },
                                modifier = Modifier.testTag("queue_edit_${message.id}"),
                            ) { Text(if (message.isEditing) "编辑中" else "编辑") }
                            TextButton(
                                enabled = !message.isEditing,
                                onClick = { onRemove(message.id) },
                                modifier = Modifier.testTag("queue_remove_${message.id}"),
                            ) { Text("撤回") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        val finish by rememberUpdatedState(onFinishEdit)
        DisposableEffect(draft) {
            onDispose {
                if (!draft.finished) finish(draft.message.id, null)
                draft.input.releaseDraft()
            }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑排队消息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.message.parts.count { it is UIMessagePart.Text } > 1) {
                        Text("正在编辑末段文字，前面的文字会保留。", style = MaterialTheme.typography.labelSmall)
                    }
                    // Removing an original attachment changes the queued payload; the service owns file cleanup.
                    draft.input.messageContent.forEachIndexed { index, part ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(queuedMessagePreview(listOf(part)), modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = {
                                draft.input.messageContent = draft.input.messageContent.filterIndexed { i, _ -> i != index }
                            }, modifier = Modifier.testTag("queue_attachment_remove_$index")) { Text("移除") }
                        }
                    }
                    TextField(
                        state = draft.input.textContent,
                        modifier = Modifier.fillMaxWidth().testTag("chat_queue_edit_text"),
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !draft.input.isEmpty(),
                    onClick = {
                        onFinishEdit(draft.message.id, draft.input.getContents())
                        draft.finished = true
                        editing = null
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}

internal fun queuedMessagePreview(parts: List<UIMessagePart>): String = parts.joinToString(" ") { part ->
    when (part) {
        is UIMessagePart.Text -> part.text
        is UIMessagePart.Image -> "[图片]"
        is UIMessagePart.Document -> "[文件] ${part.fileName}"
        is UIMessagePart.Audio, is UIMessagePart.VoiceMessage -> "[语音]"
        is UIMessagePart.Video -> "[视频]"
        else -> "[附件]"
    }
}
