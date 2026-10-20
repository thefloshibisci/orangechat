/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.service.ChatService
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import me.rerere.rikkahub.ui.theme.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
    chatService: ChatService = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val folders = remember { listOf(FileFolders.UPLOAD) }

    // 预先获取字符串资源
    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)

    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    var pendingDelete by remember { mutableStateOf<Set<Long>?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var selecting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showCleanupSheet by remember { mutableStateOf(false) }
    var cleanupMode by remember { mutableStateOf(CleanupMode.INVALID_ATTACHMENTS) }
    val files by filesManager.observe(selectedFolder).collectAsState(initial = emptyList())

    LaunchedEffect(files) {
        selectedIds = selectedIds.intersect(files.map { it.id }.toSet())
    }

    if (showCleanupSheet) {
        ModalBottomSheet(onDismissRequest = { showCleanupSheet = false }) {
            CleanupSheetContent(
                selectedMode = cleanupMode,
                onModeSelected = { cleanupMode = it },
                onConfirm = {
                    val mode = cleanupMode
                    showCleanupSheet = false

                    if (mode == CleanupMode.INVALID_ATTACHMENTS) {
                        busy = true
                        scope.launch {
                            try {
                                val (removed, repaired) = chatService.cleanInvalidAttachments()
                                toaster.show("已清理 $removed 个文件，修复 $repaired 条消息节点")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                toaster.show("清理未完成，请重试")
                            } finally {
                                busy = false
                            }
                        }
                    } else {
                        // Use the recorded modification time for age-based cleanup.
                        // Actual deletion still goes through the same confirmation
                        // and chat-reference cleanup as manual multi-selection.
                        val now = System.currentTimeMillis()
                        val cutoff = when (mode) {
                            CleanupMode.OLDER_THAN_7_DAYS -> now - 7L * 24 * 60 * 60 * 1000
                            CleanupMode.OLDER_THAN_14_DAYS -> now - 14L * 24 * 60 * 60 * 1000
                            CleanupMode.OLDER_THAN_30_DAYS -> now - 30L * 24 * 60 * 60 * 1000
                            else -> null
                        }
                        val targets = if (mode == CleanupMode.ALL_FILES) {
                            files.mapTo(mutableSetOf()) { it.id }
                        } else {
                            files.filter { cutoff != null && it.updatedAt <= cutoff }
                                .mapTo(mutableSetOf()) { it.id }
                        }

                        if (targets.isEmpty()) {
                            toaster.show("没有符合条件的文件")
                        } else {
                            pendingDelete = targets
                        }
                    }
                },
            )
        }
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        val totalSize = files.filter { it.id in target }.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text("删除选中的 ${target.size} 个文件？共 ${formatBytes(totalSize)}。删除后无法恢复，聊天中的附件引用会相应移除，聊天文字会保留。") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        // Dismiss before performing the potentially expensive attachment
                        // and message-reference cleanup. Keep busy until it finishes.
                        pendingDelete = null
                        busy = true
                        scope.launch {
                            try {
                                val deleted = chatService.deleteManagedAttachments(target)
                                toaster.show(if (deleted == target.size) deletedToast else "已删除 $deleted/${target.size} 个附件，失败项请重试")
                                selectedIds = emptySet()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                toaster.show(deleteFailedToast)
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_files_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            cleanupMode = CleanupMode.INVALID_ATTACHMENTS
                            showCleanupSheet = true
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_cleanup_broom),
                            contentDescription = "清理文件",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = settingsScaffoldContainerColor(CustomColors.topBarColors.containerColor)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            FolderRow(
                folders = folders,
                selectedFolder = selectedFolder,
                onFolderSelected = { selectedFolder = it },
                selecting = selecting,
                enabled = !busy,
                onSelectingChange = {
                    selecting = !selecting
                    selectedIds = emptySet()
                },
            )

            if (selecting) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(enabled = !busy, onClick = {
                        selectedIds = if (selectedIds.size == files.size) emptySet() else files.map { it.id }.toSet()
                    }) { Text(if (selectedIds.size == files.size) "取消全选" else "全选") }
                    TextButton(
                        enabled = !busy && selectedIds.isNotEmpty(),
                        onClick = { pendingDelete = selectedIds.toSet() },
                    ) {
                        Text("删除（${selectedIds.size}）")
                    }
                }
            }

            if (busy) {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("正在移除附件引用，聊天文字会保留…", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.setting_files_page_no_files))
                }
            } else {
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    state = gridState,
                    columns = StaggeredGridCells.Fixed(2)
                ) {
                    items(files, key = { it.id }) { file ->
                        FileItem(
                            file = file,
                            fileOnDisk = filesManager.getFile(file),
                            onDelete = { if (!busy) pendingDelete = setOf(file.id) },
                            selecting = selecting,
                            selected = file.id in selectedIds,
                            enabled = !busy,
                            onSelectedChange = {
                                selectedIds = if (it) selectedIds + file.id else selectedIds - file.id
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folders: List<String>,
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
    selecting: Boolean,
    enabled: Boolean,
    onSelectingChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selecting,
            enabled = enabled,
            onClick = onSelectingChange,
            label = { Text("多选") },
        )
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                enabled = enabled,
                onClick = { onFolderSelected(folder) },
                label = { Text(folderDisplayName(folder)) },
            )
        }
    }
}

private enum class CleanupMode(val label: String) {
    INVALID_ATTACHMENTS("清理失效附件"),
    OLDER_THAN_7_DAYS("7 天前"),
    OLDER_THAN_14_DAYS("14 天前"),
    OLDER_THAN_30_DAYS("30 天前"),
    ALL_FILES("全部文件"),
}

@Composable
private fun CleanupSheetContent(
    selectedMode: CleanupMode,
    onModeSelected: (CleanupMode) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text("清理文件？", style = MaterialTheme.typography.headlineSmall)
        Text(
            "选择需要清理的文件。删除后无法恢复。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        CleanupMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedMode == mode, onClick = null)
                Text(mode.label, modifier = Modifier.padding(start = 16.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            modifier = Modifier.align(Alignment.End),
            onClick = onConfirm,
        ) {
            Text("清理")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun folderDisplayName(folder: String): String = when (folder) {
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    else -> folder
}

@Composable
private fun FileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    onDelete: () -> Unit,
    selecting: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (file.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = fileOnDisk,
                        contentDescription = file.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.Image02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (selecting) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = onSelectedChange,
                        enabled = enabled,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                } else IconButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatBytes(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1fKB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1fMB", mb)
    val gb = mb / 1024.0
    return String.format("%.1fGB", gb)
}
