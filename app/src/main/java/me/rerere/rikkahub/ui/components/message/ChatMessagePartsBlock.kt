/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import me.rerere.rikkahub.data.interaction.MessageReaction
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.charts.ChatChartCard
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
@Composable
internal fun MessagePartsBlock(
    listenConversationId: String? = null,
    listenReadOnly: Boolean = false,
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    conversationId: String? = null,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    reactions: List<MessageReaction> = emptyList(),
    reactionPresets: List<String> = emptyList(),
    onReact: ((String, String) -> Unit)? = null,
    bubbleAvatar: @Composable () -> Unit = {},
    onBubbleLongClick: (() -> Unit)? = null,
    onBubbleDoubleClick: (() -> Unit)? = null,
    onBubbleQuote: ((String) -> Unit)? = null,
    timestamp: String? = null,
    afterTools: @Composable (List<UIMessagePart.Tool>) -> Unit = {},
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val displaySettings = LocalDisplaySettings.current
    val everyday = displaySettings.chatDisplayMode == ChatDisplayMode.EVERYDAY
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }
                            .getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(displaySettings) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && displaySettings.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                val visibleSteps = block.steps.forChatDisplay(displaySettings.chatDisplayMode)
                if (visibleSteps.isNotEmpty()) {
                    val isReasoningOnlyBlock = visibleSteps.fastAll { it is ThinkingStep.ReasoningStep }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = visibleSteps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        allParts = parts,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                        conversationId = conversationId,
                                    )
                                }
                            }
                        }
                    }
                }
                afterTools(block.steps.filterIsInstance<ThinkingStep.ToolStep>().map { it.tool })
            }

            is MessagePartBlock.ChartBlock -> key("chart", block.index) {
                ChatChartCard(block.spec)
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                ChatBubbleRow(
                    enabled = everyday && block.part !is UIMessagePart.Text,
                    isUser = role == MessageRole.USER,
                    avatar = bubbleAvatar,
                    onLongClick = onBubbleLongClick,
                    onDoubleClick = onBubbleDoubleClick,
                    timestamp = timestamp,
                ) {
                    when (val part = block.part) {
                        is UIMessagePart.Text -> {
                            GongduShareMessageContent(part.text, listenConversationId, listenReadOnly) { plainText ->
                                // Filter internal archive markers only from ordinary text. A card's
                                // excerpt or note may legitimately contain the same literal text.
                                val displayText = remember(plainText) {
                                    plainText.replace(Regex("\\[zip:[^\\]]+\\]", RegexOption.IGNORE_CASE), "")
                                }
                                ChatMessageTextBubbles(
                                    text = displayText,
                                    role = role,
                                    assistant = assistant,
                                    reactions = reactions,
                                    reactionPresets = reactionPresets,
                                    onReact = onReact,
                                    onUserMessageClick = onUserMessageClick,
                                    onClickCitation = handleClickCitation,
                                    avatar = bubbleAvatar,
                                    onLongClick = onBubbleLongClick,
                                    onDoubleClick = onBubbleDoubleClick,
                                    onQuote = onBubbleQuote,
                                    timestamp = timestamp,
                                )
                            }
                        }

                        is UIMessagePart.Video -> {
                            Surface(
                                tonalElevation = 2.dp,
                                modifier = Modifier.combinedClickable(
                                    onLongClick = if (everyday) onBubbleLongClick else null,
                                    onDoubleClick = if (everyday) onBubbleDoubleClick else null,
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW)
                                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        intent.data = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            part.url.toUri().toFile(),
                                        )
                                        val chooserIndent = Intent.createChooser(intent, null)
                                        context.startActivity(chooserIndent)
                                    },
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                    Icon(HugeIcons.Video01, null)
                                }
                            }
                        }

                        is UIMessagePart.Audio -> {
                            AudioPlayerBubble(
                                url = part.url,
                                onLongClick = if (everyday) onBubbleLongClick else null,
                                onDoubleClick = if (everyday) onBubbleDoubleClick else null,
                            )
                        }

                        is UIMessagePart.VoiceMessage -> {
                            VoiceMessageBubble(
                                voiceMessage = part,
                                isUser = role == MessageRole.USER,
                                onLongClick = if (everyday) onBubbleLongClick else null,
                                onDoubleClick = if (everyday) onBubbleDoubleClick else null,
                            )
                        }

                        is UIMessagePart.Image -> {
                            val isImageLoading =
                                part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                            if (isImageLoading) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .shimmer(isLoading = true),
                                )
                            } else {
                                ZoomableAsyncImage(
                                    model = part.url,
                                    contentDescription = null,
                                    onLongClick = if (everyday) onBubbleLongClick else null,
                                    onDoubleClick = if (everyday) onBubbleDoubleClick else null,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.medium)
                                        .height(72.dp),
                                )
                            }
                        }

                        is UIMessagePart.Document -> {
                            Surface(
                                tonalElevation = 2.dp,
                                modifier = Modifier.combinedClickable(
                                    onLongClick = if (everyday) onBubbleLongClick else null,
                                    onDoubleClick = if (everyday) onBubbleDoubleClick else null,
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW)
                                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        intent.data = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            part.url.toUri().toFile(),
                                        )
                                        val chooserIndent = Intent.createChooser(intent, null)
                                        context.startActivity(chooserIndent)
                                    },
                                ),
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        when (part.mime) {
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                                Icon(
                                                    painter = painterResource(R.drawable.docx),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }

                                            "application/pdf" -> {
                                                Icon(
                                                    painter = painterResource(R.drawable.pdf),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }

                                            else -> {
                                                Icon(
                                                    imageVector = HugeIcons.File02,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }

                                        Text(
                                            text = part.fileName,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 200.dp),
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                        }
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    val citations = annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>()
    if (citations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        citations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                },
            ) {
                Text(stringResource(R.string.citations_count, citations.size))
            }
        }
    }

    // 工作区文件 chip: assistant 消息下方展示被 workspace_write_file/
    // workspace_edit_file 写入/编辑的文件, 点击可导出/分享。
    // 仅在归属工作区的 assistant 消息中渲染, 不影响用户消息和其它布局。
    if (role == MessageRole.ASSISTANT) {
        EditedFilesList(parts = parts, assistant = assistant)
    }
}
