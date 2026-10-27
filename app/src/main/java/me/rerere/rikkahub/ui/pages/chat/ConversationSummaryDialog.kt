package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.CompressionUndoMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.summaryRounds
import me.rerere.rikkahub.data.model.withResolvedGroups
import me.rerere.rikkahub.data.model.withRoundText
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.writeBooleanPreference
import me.rerere.rikkahub.ui.theme.popupContainerColor
import kotlin.uuid.Uuid

private const val SPLIT_MODE_KEY = "compression_summary_split_mode"

@Composable
fun ConversationSummaryDialog(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onSave: suspend (List<ConversationSummary>, Boolean, CompressionUndoMode?) -> Unit,
    onMerge: suspend (List<ConversationSummary>, Boolean, String, Int) -> List<ConversationSummary>,
) {
    val context = LocalContext.current
    var summaries by remember(conversation.id) {
        mutableStateOf(conversation.compressionSummaries.withResolvedGroups(conversation.compressionHistory))
    }
    var enabled by remember(conversation.id) { mutableStateOf(conversation.sendCompressedSummary) }
    // Off = whole text per compression, on = one field per paragraph. Remembered across dialogs.
    var splitMode by remember { mutableStateOf(context.readBooleanPreference(SPLIT_MODE_KEY, false)) }
    // Whole-mode edits stay as raw text until committed, so typing blank lines doesn't reflow the field.
    var drafts by remember(conversation.id) { mutableStateOf(mapOf<Uuid, String>()) }
    var savingChanges by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var showMergeOptions by remember { mutableStateOf(false) }
    var showUndo by remember { mutableStateOf(false) }
    val saving = savingChanges || merging
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun committed(): List<ConversationSummary> =
        drafts.entries.fold(summaries) { acc, (group, text) -> acc.withRoundText(group, text) }

    fun commitDrafts() {
        if (drafts.isNotEmpty()) {
            summaries = committed()
            drafts = emptyMap()
        }
    }

    fun save(undo: CompressionUndoMode? = null) {
        val toSave = committed()
        savingChanges = true
        error = null
        scope.launch {
            try {
                onSave(toSave, enabled, undo)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                savingChanges = false
            }
        }
    }

    val preview = committed()
    val canSave = preview.isNotEmpty() && preview.all { it.text.isNotBlank() }
    val rounds = summaries.summaryRounds()

    BasicAlertDialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = AlertDialogDefaults.shape,
            color = popupContainerColor(AlertDialogDefaults.containerColor),
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                Text(
                    stringResource(R.string.compression_summary_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SwitchRow(stringResource(R.string.compression_send_summary), enabled, !saving) { enabled = it }
                    Text(
                        stringResource(R.string.compression_send_hint_short),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow(stringResource(R.string.compression_split_mode), splitMode, !saving) {
                        commitDrafts()
                        splitMode = it
                        context.writeBooleanPreference(SPLIT_MODE_KEY, it)
                    }
                    rounds.forEachIndexed { roundIndex, round ->
                        val group = round.first().group ?: return@forEachIndexed
                        SummaryRoundCard(
                            roundNumber = roundIndex + 1,
                            showNumber = rounds.size > 1,
                            round = round,
                            splitMode = splitMode,
                            wholeText = drafts[group] ?: round.joinToString("\n\n") { it.text },
                            enabled = !saving,
                            onRoundSelected = { selected ->
                                summaries = summaries.map { if (it.group == group) it.copy(sendToModel = selected) else it }
                            },
                            onWholeTextChange = { text -> drafts = drafts + (group to text) },
                            onPartSelected = { id, selected ->
                                summaries = summaries.map { if (it.id == id) it.copy(sendToModel = selected) else it }
                            },
                            onPartTextChange = { id, text ->
                                summaries = summaries.map { if (it.id == id) it.copy(text = text) else it }
                            },
                            onSplitPart = { id ->
                                summaries = summaries.flatMap { if (it.id == id) it.splitLines() else listOf(it) }
                            },
                        )
                    }
                    TextButton(
                        onClick = { commitDrafts(); showMergeOptions = true },
                        modifier = Modifier.align(Alignment.End),
                        enabled = !saving && enabled && canSave && preview.any { it.sendToModel },
                    ) {
                        Text(stringResource(R.string.compression_merge_existing))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.hasCompressionState) {
                        TextButton(onClick = { showUndo = true }, enabled = !saving) {
                            Text(stringResource(R.string.compression_undo), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { save() }, enabled = !saving && canSave) {
                        Text(stringResource(R.string.chat_page_save))
                    }
                }
            }
        }
    }

    if (showMergeOptions) {
        MergeSummariesDialog(
            onDismiss = { showMergeOptions = false },
            onConfirm = { prompt, tokens ->
                merging = true
                try {
                    summaries = onMerge(summaries, enabled, prompt, tokens)
                } finally {
                    merging = false
                }
            },
        )
    }
    if (showUndo) {
        UndoCompressionDialog(
            conversation = conversation,
            onDismiss = { showUndo = false },
            onConfirm = { mode -> showUndo = false; save(mode) },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun SummaryRoundCard(
    roundNumber: Int,
    showNumber: Boolean,
    round: List<ConversationSummary>,
    splitMode: Boolean,
    wholeText: String,
    enabled: Boolean,
    onRoundSelected: (Boolean) -> Unit,
    onWholeTextChange: (String) -> Unit,
    onPartSelected: (Uuid, Boolean) -> Unit,
    onPartTextChange: (Uuid, String) -> Unit,
    onSplitPart: (Uuid) -> Unit,
) {
    val state = when {
        round.all { it.sendToModel } -> ToggleableState.On
        round.none { it.sendToModel } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
    val sendLabel = stringResource(R.string.compression_round_send, roundNumber)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(
                state = state,
                onClick = { onRoundSelected(state != ToggleableState.On) },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = sendLabel },
            )
            Text(
                if (showNumber) stringResource(R.string.compression_round, roundNumber)
                else stringResource(R.string.compression_send),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (!splitMode) {
            OutlinedTextField(
                value = wholeText,
                onValueChange = onWholeTextChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                minLines = 4,
                maxLines = 14,
                shape = RoundedCornerShape(12.dp),
                isError = wholeText.isBlank(),
            )
        } else {
            round.forEachIndexed { index, part ->
                val partLabel = stringResource(R.string.compression_round_part, index + 1)
                Column(Modifier.padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            part.sendToModel, { onPartSelected(part.id, it) }, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = "$sendLabel · $partLabel" },
                        )
                        Text(partLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (part.text.lines().count { it.isNotBlank() } > 1) {
                            TextButton(onClick = { onSplitPart(part.id) }, enabled = enabled) {
                                Text(stringResource(R.string.compression_split_lines))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = part.text,
                        onValueChange = { onPartTextChange(part.id, it) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        minLines = 2,
                        maxLines = 8,
                        shape = RoundedCornerShape(12.dp),
                        isError = part.text.isBlank(),
                    )
                }
            }
        }
    }
}

@Composable
private fun UndoCompressionDialog(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onConfirm: (CompressionUndoMode) -> Unit,
) {
    val canUndoLatest = conversation.latestCompressionSnapshot != null
    // Default: undo only the latest round. Without a checkpoint, only "undo all" is possible.
    var undoAll by remember { mutableStateOf(!canUndoLatest) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = popupContainerColor(AlertDialogDefaults.containerColor),
        title = { Text(stringResource(if (undoAll) R.string.compression_undo_all else R.string.compression_undo_latest)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (undoAll) stringResource(R.string.compression_undo_all_hint, conversation.activeCompressedMessageNodeIds.size)
                    else stringResource(R.string.compression_undo_latest_hint, conversation.latestCompressionUndoRestoredCount),
                )
                if (!canUndoLatest) {
                    Text(
                        stringResource(R.string.compression_undo_no_history),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val label = stringResource(R.string.compression_undo_all_option)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        undoAll, { undoAll = it }, enabled = canUndoLatest,
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                    Text(label, color = if (undoAll) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (undoAll) CompressionUndoMode.ALL else CompressionUndoMode.LATEST) }) {
                Text(stringResource(R.string.compression_undo_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
