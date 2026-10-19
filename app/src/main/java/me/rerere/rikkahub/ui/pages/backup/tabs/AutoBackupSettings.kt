package me.rerere.rikkahub.ui.pages.backup.tabs

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.AutoBackupDestination
import me.rerere.rikkahub.data.sync.AutoBackupScheduler
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.buildCardGroupItems
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AutoBackupSettings(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.autoBackupConfig
    val loaded = !settings.init
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                vm.updateAutoBackupConfig { it.copy(directoryUri = uri.toString()) }
            }.onFailure { toaster.show("无法保留文件夹权限，请重新选择") }
        }
    }
    val ready = when (config.destination) {
        AutoBackupDestination.LOCAL -> config.directoryUri.isNotBlank()
        AutoBackupDestination.WEBDAV -> settings.webDavConfig.url.isNotBlank()
        AutoBackupDestination.S3 -> settings.s3Config.endpoint.isNotBlank() && settings.s3Config.bucket.isNotBlank()
    }
    CardGroup(modifier = Modifier.fillMaxWidth(), title = { Text("自动备份") }, items = buildCardGroupItems {
        item(
            headlineContent = { Text("定时自动备份") },
            supportingContent = { Text("由系统在后台执行，省电策略可能延迟运行。首次开启会安排一次备份。") },
            trailingContent = {
                Switch(checked = config.enabled, enabled = loaded && (ready || config.enabled),
                    onCheckedChange = { enabled -> vm.updateAutoBackupConfig { it.copy(enabled = enabled) } })
            },
        )
        item(
            headlineContent = { Text("备份位置") },
            supportingContent = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoBackupDestination.entries.forEach { destination ->
                            FilterChip(selected = config.destination == destination, enabled = loaded,
                                onClick = { vm.updateAutoBackupConfig { it.copy(destination = destination, enabled = false, lastError = "") } },
                                label = { Text(when (destination) {
                                    AutoBackupDestination.LOCAL -> "本地"
                                    AutoBackupDestination.WEBDAV -> "WebDAV"
                                    AutoBackupDestination.S3 -> "S3"
                                }) })
                        }
                    }
                    if (config.destination == AutoBackupDestination.LOCAL) {
                        TextButton(enabled = loaded, onClick = { directoryPicker.launch(null) }) {
                            Text(if (config.directoryUri.isBlank()) "选择备份文件夹" else "更换备份文件夹")
                        }
                        if (config.directoryUri.isNotBlank()) Text(android.net.Uri.decode(config.directoryUri.substringAfterLast('/')))
                        Text("备份聊天、附件和配置，保留最近 7 份自动备份。可在“导入导出”中恢复。")
                    } else {
                        Text("使用对应标签页中的连接和备份范围配置。")
                    }
                    if (!ready) Text("请先选择本地文件夹，或填写对应的远程连接配置。")
                }
            },
        )
        item(
            headlineContent = { Text("备份间隔") },
            supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6L, 24L, 72L).forEach { hours ->
                        FilterChip(selected = config.intervalHours == hours, enabled = loaded,
                            onClick = { vm.updateAutoBackupConfig { it.copy(intervalHours = hours) } },
                            label = { Text("${hours}小时") })
                    }
                }
            },
        )
        if (config.destination != AutoBackupDestination.LOCAL) {
            item(headlineContent = { Text("仅在非计费网络备份") }, trailingContent = {
                Switch(checked = config.wifiOnly, enabled = loaded,
                    onCheckedChange = { wifi -> vm.updateAutoBackupConfig { it.copy(wifiOnly = wifi) } })
            })
        }
        item(
            headlineContent = {
                Text(if (config.lastSuccessTime == 0L) "尚无自动备份记录"
                    else "最近成功：${DateFormat.getDateTimeInstance().format(Date(config.lastSuccessTime))}")
            },
            supportingContent = { if (config.lastError.isNotBlank()) Text(config.lastError) },
            trailingContent = {
                TextButton(enabled = loaded && ready && config.enabled, onClick = {
                    AutoBackupScheduler.runNow(context, config)
                    toaster.show("已安排备份，等待系统满足运行条件")
                }) { Text("立即备份") }
            },
        )
    })
}
