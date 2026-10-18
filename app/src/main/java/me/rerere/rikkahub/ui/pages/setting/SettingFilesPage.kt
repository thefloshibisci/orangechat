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
    var confirmCleanup by remember { mutableStateOf(false) }
    val files by filesManager.observe(selectedFolder).collectAsState(initial = emptyList())

    LaunchedEffect(files) {
        selectedIds = selectedIds.intersect(files.map { it.id }.toSet())
    }

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmCleanup = false },
            title = { Text("清理失效附件") },
            text = { Text("移除不存在、0B 或损坏图片的附件引用，并清理对应空文件。所有聊天文字和重新生成的消息分支都会保留。") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
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
                            confirmCleanup = false
                        }
                    }
                }) { Text(if (busy) "正在清理…" else "清理") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmCleanup = false }) { Text("取消") } },
        )
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text("删除选中的 ${target.size} 个附件？聊天中的附件引用也会移除，聊天文字会保留。") },
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
                onFolderSelected = { selectedFolder = it }
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(enabled = !busy, onClick = { selecting = !selecting; selectedIds = emptySet() }) {
                    Text(if (selecting) "退出多选" else "多选")
                }
                if (selecting) {
                    TextButton(enabled = !busy, onClick = {
                        selectedIds = if (selectedIds.size == files.size) emptySet() else files.map { it.id }.toSet()
                    }) { Text(if (selectedIds.size == files.size) "取消全选" else "全选") }
                    TextButton(enabled = !busy && selectedIds.isNotEmpty(), onClick = { pendingDelete = selectedIds.toSet() }) {
                        Text("删除（${selectedIds.size}）")
                    }
                }
                TextButton(enabled = !busy, onClick = { confirmCleanup = true }) { Text("清理失效附件") }
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
                label = { Text(folderDisplayName(folder)) }
            )
        }
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
