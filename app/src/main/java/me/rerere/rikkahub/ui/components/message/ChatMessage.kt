/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.data.interaction.withoutVideoCallTranscriptText
import me.rerere.rikkahub.data.interaction.withoutJumpControl
import me.rerere.rikkahub.utils.quotedBubbleSelection
import kotlinx.datetime.toJavaLocalDateTime
import java.time.format.DateTimeFormatter
import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.interaction.MessageReaction
import me.rerere.rikkahub.data.interaction.avatarInteraction
import me.rerere.rikkahub.data.interaction.avatarInteractionText
import me.rerere.rikkahub.data.interaction.reactionSegments
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.base64Encode
import java.util.Locale

@Composable
fun ChatMessage(
    node: MessageNode,
    inlineCards: Map<String, me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction> = emptyMap(),
    conversationId: kotlin.uuid.Uuid? = null,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    reactions: List<MessageReaction> = emptyList(),
    reactionPresets: List<String> = emptyList(),
    onReact: ((String, String) -> Unit)? = null,
    onAvatarInteraction: ((UIMessage) -> Unit)? = null,
    avatarPulse: String? = null,
    onQuote: ((String) -> Unit)? = null,
    hasAssistantResponse: Boolean? = null,
    hasUserRead: Boolean? = null,
    hideVideoCallTranscriptText: Boolean = false,
) {
    val sourceMessage = node.messages[node.selectIndex]
    val renderedMessage = me.rerere.rikkahub.data.ai.transformers.interactionTranscript(sourceMessage, streaming = loading).withoutJumpControl().let { original ->
        if (original.role == MessageRole.USER && original.parts.any {
                it is UIMessagePart.Text && it.text.startsWith("\u2063[orange-watch-auto]")
            }
        ) {
            original.copy(
                parts = original.parts.filterNot {
                    it is UIMessagePart.Text && it.text.startsWith("\u2063[orange-watch-auto]")
                }
            )
        } else {
            original
        }
    }
    val message = if (hideVideoCallTranscriptText) {
        renderedMessage.withoutVideoCallTranscriptText()
    } else renderedMessage
    if (message.parts.isEmptyUIMessage() && !loading &&
        message.annotations.none { it is me.rerere.ai.ui.UIMessageAnnotation.VideoCallGift }
    ) return
    if (message.avatarInteraction() != null) {
        Text(
            text = message.avatarInteractionText(),
            modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val avatarGesture = avatarInteractionModifier(
        avatarPulse,
        onAvatarInteraction?.let { callback -> { callback(message) } },
    )
    val settings = LocalDisplaySettings.current
    val everyday = settings.chatDisplayMode == ChatDisplayMode.EVERYDAY
    val timestamp = remember(message.createdAt, message.role, everyday, hasAssistantResponse, hasUserRead) {
        val time = message.createdAt.toJavaLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        val read = when (message.role) {
            MessageRole.USER -> hasAssistantResponse
            MessageRole.ASSISTANT -> hasUserRead
            else -> null
        }
        if (everyday && read != null) "$time ${if (read) "已读" else "未读"}" else time
    }
    message.annotations.filterIsInstance<me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction>().firstOrNull()?.let { card ->
        Column(modifier.fillMaxWidth()) {
            if (!everyday) {
                if (message.role == MessageRole.ASSISTANT) {
                    ChatMessageAssistantAvatar(
                        message = message, model = model, assistant = assistant, loading = loading,
                        modifier = Modifier.fillMaxWidth(), interactionModifier = avatarGesture,
                    )
                } else if (message.role == MessageRole.USER) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ChatMessageUserAvatar(
                            message = message, avatar = settings.userAvatar, nickname = settings.userNickname,
                            interactionModifier = avatarGesture,
                        )
                    }
                }
            }
            if (everyday) ChatBubbleRow(
                enabled = true,
                isUser = message.role == MessageRole.USER,
                avatar = { ChatBubbleAvatar(message.role == MessageRole.USER, model, assistant, avatarGesture) },
                timestamp = timestamp,
            ) {
                RichChatCard(card, conversationId, readOnly,
                    senderName = if (card.actor == "user") settings.userNickname.ifBlank { "我" }
                        else assistant?.name?.takeIf { it.isNotBlank() } ?: model?.displayName ?: "AI")
            } else {
                RichChatCard(card, conversationId, readOnly,
                    senderName = if (card.actor == "user") settings.userNickname.ifBlank { "我" }
                        else assistant?.name?.takeIf { it.isNotBlank() } ?: model?.displayName ?: "AI")
                val headerShowsTime = settings.showDateBelowName && when (message.role) {
                    MessageRole.USER -> settings.showUserAvatar
                    MessageRole.ASSISTANT -> settings.showModelName && (model != null || assistant?.useAssistantAvatar == true)
                    else -> false
                }
                if (!headerShowsTime) {
                    Text(
                        text = timestamp,
                        modifier = Modifier.align(if (message.role == MessageRole.USER) Alignment.End else Alignment.Start)
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
            if (message.role == MessageRole.USER && !everyday) {
                Column(Modifier.align(Alignment.End).padding(top = 4.dp)) {
                    InteractionMessageActions(sourceMessage, node, readOnly, model, onUpdate, onRegenerate,
                        onEdit, onDelete, onShare, onFork, isFavorite, onToggleFavorite)
                }
            }
        }
        return
    }
    val quoteAuthor = if (message.role == MessageRole.USER) settings.userNickname.ifBlank { "我" }
        else assistant?.name?.takeIf { it.isNotBlank() } ?: model?.displayName ?: "AI"
    val quoteSelection = onQuote?.let { callback ->
        { text: String -> callback(quotedBubbleSelection(quoteAuthor, text)) }
    }
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        color = settings.chatTextColor?.let { it.toComposeColor() } ?: Color.Unspecified,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = when (settings.chatFontFamily) {
            ChatFontFamily.DEFAULT -> FontFamily.Default
            ChatFontFamily.SERIF -> FontFamily.Serif
            ChatFontFamily.MONOSPACE -> FontFamily.Monospace
            ChatFontFamily.CUSTOM -> {
                val fontPath = settings.customFontPath
                if (fontPath.isNotBlank() && java.io.File(fontPath).exists()) {
                    FontFamily(Font(java.io.File(fontPath)))
                } else {
                    FontFamily.Default
                }
            }
        }
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showProcessSheet by remember(message.id) { mutableStateOf(false) }
    var showReactionPicker by remember(message.id) { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!everyday && !message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f),
                    interactionModifier = avatarGesture,
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f),
                    interactionModifier = avatarGesture,
                )
            }
        }
        ProvideTextStyle(textStyle) {
            if (everyday && message.role == MessageRole.ASSISTANT &&
                // A gift-only record has no hidden process to open; keep loading/process entries intact.
                (loading || message.annotations.none { it is UIMessageAnnotation.VideoCallGift } ||
                    message.parts.any { it is UIMessagePart.Reasoning || it is UIMessagePart.Tool }) &&
                (!hideVideoCallTranscriptText || message.parts.any { it is UIMessagePart.Reasoning || it is UIMessagePart.Tool }) && message.parts.none {
                (it is UIMessagePart.Text && it.text.isNotBlank())
                    || it is UIMessagePart.Image || it is UIMessagePart.Audio
                    || it is UIMessagePart.VoiceMessage || it is UIMessagePart.Video || it is UIMessagePart.Document
            }) {
                ChatBubbleRow(
                    enabled = true,
                    isUser = false,
                    avatar = { ChatBubbleAvatar(false, model, assistant, avatarGesture) },
                    onDoubleClick = { showActionsSheet = true },
                    timestamp = timestamp,
                ) {
                    Text(if (loading) "正在回复…" else "查看思考和工具记录", Modifier.padding(8.dp), color = colorScheme.onSurfaceVariant)
                }
            }
            MessagePartsBlock(
                listenConversationId = conversationId?.toString(),
                listenReadOnly = readOnly,
                afterTools = { tools ->
                    tools.filter { it.toolName == "music_tool" && it.isExecuted }.forEach { tool ->
                        tool.output.filterIsInstance<UIMessagePart.Text>().forEach { part ->
                            me.rerere.rikkahub.data.listen.parseListenShare(part.text)?.let { share ->
                                ListenSongCard(share, conversationId?.toString(), readOnly)
                            }
                        }
                    }
                    tools.mapNotNull { me.rerere.rikkahub.data.interaction.interactionToolId(it) }.distinct().forEach { id ->
                        inlineCards[id]?.let { card ->
                            ChatBubbleRow(everyday, false, avatar = { ChatBubbleAvatar(false, model, assistant, avatarGesture) }, timestamp = timestamp) {
                                RichChatCard(card, conversationId, readOnly, senderName = assistant?.name?.takeIf { it.isNotBlank() } ?: "AI")
                            }
                        }
                    }
                },
                assistant = assistant,
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
                loading = loading,
                model = model,
                conversationId = conversationId?.toString(),
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onUserMessageClick = if (!readOnly && message.role == MessageRole.USER) onEdit else null,
                reactions = reactions,
                reactionPresets = reactionPresets,
                onReact = onReact,
                bubbleAvatar = { ChatBubbleAvatar(message.role == MessageRole.USER, model, assistant, avatarGesture) },
                onBubbleLongClick = quoteSelection?.let { quote -> { quote(message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.ifBlank { "[附件消息]" }) } },
                onBubbleDoubleClick = { showActionsSheet = true },
                onBubbleQuote = quoteSelection,
                timestamp = timestamp,
            )
 
            message.annotations.filterIsInstance<me.rerere.ai.ui.UIMessageAnnotation.VideoCallGift>()
                .lastOrNull()?.let { gift ->
                    // The gift belongs to this same reply; do not add a second sender row.
                    VideoCallGiftCard(gift)
                }
            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }
 
        if (message.parts.none { it is UIMessagePart.Text }) {
            MessageReactionBar("", reactions, reactionPresets, onReact)
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }
 
        AnimatedVisibility(
            visible = showActions && !everyday,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    readOnly = readOnly,
                    message = sourceMessage,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation
                )
            }
        }
 
        if (everyday && !readOnly && node.messages.size > 1) {
            ChatMessageBranchSelector(node = node, onUpdate = onUpdate)
        }
        if (!everyday) {
            ProvideTextStyle(textStyle) { ChatMessageNerdLine(message = message) }
        }
    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            readOnly = readOnly,
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onReact = onReact?.let { { showReactionPicker = true } },
            onMessageActions = {
                if (everyday) {
                    Column {
                        ChatMessageActionButtons(
                            readOnly = readOnly,
                            message = sourceMessage,
                            node = node,
                            onUpdate = onUpdate,
                            onRegenerate = { showActionsSheet = false; onRegenerate() },
                            onOpenActionSheet = { showActionsSheet = false },
                            onTranslate = onTranslate,
                            onClearTranslation = onClearTranslation,
                            showMoreButton = false,
                        )
                    }
                }
            },
            onShowProcess = if (everyday && message.parts.any {
                it is UIMessagePart.Reasoning || it is UIMessagePart.Tool
            }) ({ showProcessSheet = true }) else null,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    navController.navigate(Screen.WebView(content = htmlContent.base64Encode()))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showReactionPicker && onReact != null) {
        MessageReactionPicker(
            segments = reactionSegments(message, (assistant ?: Assistant()).let {
                if (everyday) it.copy(splitBubbleByLine = true, splitUserBubbleByLine = true) else it
            }),
            presets = reactionPresets,
            onDismiss = { showReactionPicker = false },
            onSelect = { emoji, quote ->
                onReact(emoji, quote)
                showReactionPicker = false
            },
        )
    }
 
    if (showProcessSheet) {
        ModalBottomSheet(onDismissRequest = { showProcessSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            CompositionLocalProvider(LocalDisplaySettings provides settings.copy(
                chatDisplayMode = ChatDisplayMode.STANDARD,
                showThinkingContent = true,
            )) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("思考和工具记录", style = MaterialTheme.typography.titleMedium)
                    MessagePartsBlock(
                        assistant = assistant,
                        role = message.role,
                        model = model,
                        parts = message.parts.filter { it is UIMessagePart.Reasoning || it is UIMessagePart.Tool },
                        annotations = emptyList(),
                        loading = loading,
                        conversationId = conversationId?.toString(),
                        onToolApproval = onToolApproval,
                        onToolAnswer = onToolAnswer,
                    )
                }
            }
        }
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}
