package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.base64Encode

/** Reuse the normal user-message controls, including its regenerate confirmation. */
@Composable
internal fun InteractionMessageActions(message: UIMessage, node: MessageNode, readOnly: Boolean,
    model: Model?, onUpdate: (MessageNode) -> Unit, onRegenerate: () -> Unit, onEdit: () -> Unit,
    onDelete: () -> Unit, onShare: () -> Unit, onFork: () -> Unit, isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?) {
    var actionsOpen by remember(message.id) { mutableStateOf(false) }
    var copyOpen by remember(message.id) { mutableStateOf(false) }
    var previewOpen by remember(message.id) { mutableStateOf(false) }
    Column {
        ChatMessageActionButtons(readOnly, message, node, onUpdate, onRegenerate, { actionsOpen = true })
    }
    if (actionsOpen) ChatMessageActionsSheet(readOnly = readOnly, message = message, model = model,
        onDelete = onDelete, onEdit = onEdit, onShare = onShare, onFork = onFork,
        onSelectAndCopy = { copyOpen = true }, isFavorite = isFavorite, onToggleFavorite = onToggleFavorite,
        onWebViewPreview = { previewOpen = true }, onDismissRequest = { actionsOpen = false })
    if (copyOpen) ChatMessageCopySheet(message) { copyOpen = false }
    if (previewOpen) {
        val context = LocalContext.current
        val nav = LocalNavController.current
        val colors = MaterialTheme.colorScheme
        LaunchedEffect(message.id) {
            nav.navigate(Screen.WebView(content = buildMarkdownPreviewHtml(context, message.toText(), colors).base64Encode()))
            previewOpen = false
        }
    }
}
