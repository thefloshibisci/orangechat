package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.bubbleQuote
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import me.rerere.rikkahub.data.interaction.MessageReaction
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.utils.chatBubbleSegments

/** One row per visible bubble; never share an avatar between segments. */
@Composable
internal fun ChatBubbleRow(
    enabled: Boolean,
    isUser: Boolean,
    avatar: @Composable () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    timestamp: String? = null,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    if (onLongClick == null && onDoubleClick == null) Modifier
                    else Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick, onDoubleClick = onDoubleClick)
                ),
        horizontalArrangement =
            Arrangement.spacedBy(8.dp, if (isUser) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.Top,
    ) {
        if (isUser) Spacer(Modifier.width(32.dp)) else avatar()
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            content()
            if (timestamp != null) {
                Text(timestamp, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp))
            }
        }
        if (isUser) avatar() else Spacer(Modifier.width(32.dp))
    }
}

@Composable
internal fun ChatMessageTextBubbles(
    text: String,
    role: MessageRole,
    assistant: Assistant?,
    reactions: List<MessageReaction>,
    reactionPresets: List<String>,
    onReact: ((String, String) -> Unit)?,
    onUserMessageClick: (() -> Unit)?,
    onClickCitation: (String) -> Unit,
    avatar: @Composable () -> Unit,
    onLongClick: (() -> Unit)?,
    onDoubleClick: (() -> Unit)? = null,
    onQuote: ((String) -> Unit)? = null,
    timestamp: String? = null,
) {
    val display = LocalDisplaySettings.current
    val everyday = display.chatDisplayMode == ChatDisplayMode.EVERYDAY
    val isUser = role == MessageRole.USER
    val split =
        (if (isUser) assistant?.splitUserBubbleByLine else assistant?.splitBubbleByLine) == true
    val segments =
        remember(text, display.chatDisplayMode, split) {
            text.chatBubbleSegments(display.chatDisplayMode, split).let { segments ->
                if (everyday) segments.filter { it.isNotBlank() } else segments
            }
        }
    val showBubble = isUser || everyday || display.showAssistantBubble
    val inlineReactions = showBubble && !everyday && !split
    val scheme = MaterialTheme.colorScheme
    BubbleTextSelection(enabled = !everyday) {
        Column(
            verticalArrangement = Arrangement.spacedBy(if (everyday) 6.dp else 4.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            segments.forEachIndexed { index, segment ->
                key(index) {
                    ChatBubbleRow(everyday, isUser, avatar, timestamp = timestamp) {
                        if (showBubble) {
                            BubbleFrame(if (isUser) display.userBubbleFrame else display.assistantBubbleFrame, isUser = isUser) {
                                BubbleSurface(
                                    imagePath =
                                        if (isUser) display.userBubbleImagePath
                                        else display.assistantBubbleImagePath,
                                    cornerRadius = display.bubbleCornerRadius.dp,
                                    color =
                                        if (isUser)
                                            display.userBubbleColor?.toComposeColor()
                                                ?: scheme.secondaryContainer
                                        else
                                            display.assistantBubbleColor?.toComposeColor()
                                                ?: scheme.surfaceContainerHigh,
                                    overlayEnabled = display.bubbleImageOverlayEnabled,
                                    bubbleAlpha = 1f - display.chatBubbleTransparency / 100f,
                                    onClick = if (isUser && !everyday) onUserMessageClick else null,
                                    onLongClick = if (everyday) onQuote?.let { { it(segment) } } ?: onLongClick else null,
                                    onDoubleClick = if (everyday) onDoubleClick else null,
                                    enableLiveBubbleBlur = true,
                                ) {
                                    val rendered = segment.replaceRegexes(
                                        assistant = assistant,
                                        scope = if (isUser) AssistantAffectScope.USER else AssistantAffectScope.ASSISTANT,
                                        visual = true,
                                    )
                                    val quote = if (everyday) rendered.bubbleQuote() else null
                                    if (quote != null) {
                                        BubbleQuoteCard(quote.author ?: "引用", quote.text)
                                        if (quote.reply.isNotBlank()) {
                                            MarkdownBlock(content = quote.reply, onClickCitation = onClickCitation,
                                                modifier = Modifier.padding(top = 6.dp))
                                        }
                                    } else {
                                        MarkdownBlock(content = rendered, onClickCitation = onClickCitation)
                                    }
                                    if (inlineReactions) MessageReactionBar(segment, reactions, reactionPresets, onReact)
                                }
                            }
                        } else {
                            MarkdownBlock(
                                content =
                                    segment.replaceRegexes(
                                        assistant = assistant,
                                        scope = AssistantAffectScope.ASSISTANT,
                                        visual = true,
                                    ),
                                onClickCitation = onClickCitation,
                                modifier = Modifier.animateContentSize(),
                            )
                        }
                        if (!inlineReactions) MessageReactionBar(segment, reactions, reactionPresets, onReact)
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleTextSelection(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) SelectionContainer(content = content) else content()
}

@Composable
private fun BubbleQuoteCard(author: String, text: String) {
    Column(
        modifier = Modifier.background(
            if (LocalDarkMode.current) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            else Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp)
        ).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(author, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}
