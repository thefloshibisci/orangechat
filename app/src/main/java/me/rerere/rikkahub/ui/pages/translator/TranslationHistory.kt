/*
 * 橘瓣 OrangeChat，基于 RikkaHub；GNU AGPL v3，见 LICENSE。
 */
package me.rerere.rikkahub.ui.pages.translator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.Flow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationHistorySheet(
    history: Flow<PagingData<TranslationHistoryEntity>>,
    onRestore: (TranslationHistoryEntity) -> Unit,
    onDelete: (TranslationHistoryEntity) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = history.collectAsLazyPagingItems()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        TranslationHistoryPagingContent(entries, onRestore, onDelete, onClear,
            modifier = Modifier.fillMaxHeight(0.85f))
    }
}

@Composable
internal fun TranslationHistoryPagingContent(
    entries: LazyPagingItems<TranslationHistoryEntity>,
    onRestore: (TranslationHistoryEntity) -> Unit,
    onDelete: (TranslationHistoryEntity) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Count, keys and displayed data must belong to one immutable paging presentation.
    // Lazy layout may still ask an old key factory after deletion publishes an empty one.
    val snapshot = entries.itemSnapshotList
    TranslationHistoryContent(
            itemCount = snapshot.size,
            itemAt = { index ->
                val item = snapshot.getOrNull(index)
                if (index >= 0 && entries.itemSnapshotList === snapshot && index < entries.itemCount) {
                    // Access only to issue a load hint; always display the captured presentation.
                    // The presenter can replace its generation before its Compose state is applied.
                    try { entries[index] } catch (_: IndexOutOfBoundsException) { /* stale load hint */ }
                }
                item
            },
            itemKey = { index -> snapshot.getOrNull(index)?.id ?: "loading-$index" },
            refreshState = entries.loadState.refresh,
            appendState = entries.loadState.append,
            onRetry = entries::retry,
            onRestore = onRestore,
            onDelete = onDelete,
            onClear = onClear,
            modifier = modifier,
        )
}

@Composable
internal fun TranslationHistoryContent(
    itemCount: Int,
    itemAt: (Int) -> TranslationHistoryEntity?,
    refreshState: LoadState,
    appendState: LoadState,
    onRetry: () -> Unit,
    onRestore: (TranslationHistoryEntity) -> Unit,
    onDelete: (TranslationHistoryEntity) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    itemKey: (Int) -> Any = { it },
) {
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("翻译历史", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { showClearDialog = true }, enabled = itemCount > 0) {
                Text("清空历史")
            }
        }
        Text(
            "保留最近 100 条成功翻译，点按记录恢复原文、译文和目标语言。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (refreshState) {
            is LoadState.Loading -> HistoryLoading()
            is LoadState.Error -> HistoryLoadError(onRetry)
            is LoadState.NotLoading -> if (itemCount == 0) {
                Text(
                    "还没有翻译历史。完成翻译后会自动保存。",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("translation_history_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(count = itemCount, key = itemKey) { index ->
                itemAt(index)?.let { entry ->
                    TranslationHistoryItem(entry, { onRestore(entry) }, { onDelete(entry) })
                }
            }
            if (itemCount > 0) {
                when (appendState) {
                    is LoadState.Loading -> item(key = "append-loading") { HistoryLoading() }
                    is LoadState.Error -> item(key = "append-error") { HistoryLoadError(onRetry) }
                    is LoadState.NotLoading -> Unit
                }
            }
        }
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空翻译历史？") },
            text = { Text("将删除全部翻译历史记录，当前输入和译文会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClear()
                }) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TranslationHistoryItem(
    entry: TranslationHistoryEntity,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val language = Locale.forLanguageTag(entry.targetLanguage).getDisplayName(locale)
        .ifBlank { "未知语言" }
    val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", locale)
        .format(Instant.ofEpochMilli(entry.createAt).atZone(ZoneId.systemDefault()))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onRestore,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(entry.sourceText, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(entry.translatedText, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("$language · $date", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, contentDescription = "删除此条翻译记录")
            }
        }
    }
}

@Composable
private fun HistoryLoading() {
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text("正在读取翻译历史…")
    }
}

@Composable
private fun HistoryLoadError(onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text("读取翻译历史失败，请重试。", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text("重试") }
    }
}
