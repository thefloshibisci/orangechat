package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.interaction.CHAT_REACTION_KAOMOJI
import me.rerere.rikkahub.data.interaction.MessageReaction

@Composable
internal fun MessageReactionBar(
    quote: String,
    reactions: List<MessageReaction>,
    presets: List<String> = CHAT_REACTION_KAOMOJI,
    onToggle: ((String, String) -> Unit)?,
) {
    if (onToggle == null || reactions.isEmpty()) return
    var picking by remember(quote) { mutableStateOf(false) }
    val grouped = reactions.filter { it.quote == quote }.groupBy { it.emoji }
    if (grouped.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        grouped.forEach { (reaction, rows) ->
            val own = rows.any { it.actor == "user" }
            Surface(
                onClick = { onToggle(reaction, quote) },
                shape = RoundedCornerShape(8.dp),
                color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    "$reaction ${rows.map { it.actor }.distinct().size}",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Surface(
            onClick = { picking = true },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Text(
                "颜文字＋",
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    if (picking) {
        MessageReactionPicker(
            segments = listOf(quote),
            presets = presets,
            onDismiss = { picking = false },
        ) { reaction, selected ->
            onToggle(reaction, selected)
            picking = false
        }
    }
}

@Composable
internal fun MessageReactionPicker(
    segments: List<String>,
    presets: List<String> = CHAT_REACTION_KAOMOJI,
    onDismiss: () -> Unit,
    onSelect: (String, String) -> Unit,
) {
    val choices = segments.ifEmpty { listOf("") }
    val availablePresets = presets.ifEmpty { CHAT_REACTION_KAOMOJI }
    var selected by remember(choices) { mutableStateOf(choices.last()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("贴个颜文字") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (choices.size > 1) {
                    Text("先选要回应的那一句", style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        choices.forEach { sentence ->
                            Surface(
                                color = if (selected == sentence) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                modifier = Modifier.clickable { selected = sentence },
                            ) {
                                Text(sentence.take(160), Modifier.padding(8.dp))
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    availablePresets.forEach { reaction ->
                        Surface(
                            onClick = { onSelect(reaction, selected) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                reaction,
                                Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
