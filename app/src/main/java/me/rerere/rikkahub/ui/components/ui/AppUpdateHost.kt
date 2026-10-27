package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.utils.ProjectLinks
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UpdateDownloadState
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.updateDownloadForPrompt
import org.koin.compose.koinInject

/** Lives above navigation, so an available update is visible on any application page. */
@Composable
fun AppUpdateHost(checker: UpdateChecker = koinInject()) {
    val context = LocalContext.current
    val info by checker.prompt.collectAsStateWithLifecycle()
    val download by checker.downloadState.collectAsStateWithLifecycle()
    LaunchedEffect(checker) { checker.checkUpdate().collect {} }
    val current = info ?: return
    val currentDownload = updateDownloadForPrompt(current, download)
    AlertDialog(
        onDismissRequest = checker::dismissPrompt,
        title = { Text("发现新版本 ${current.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MarkdownBlock(
                    content = current.changelog,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (val state = currentDownload) {
                    is UpdateDownloadState.Downloading -> {
                        Text(state.download.name, style = MaterialTheme.typography.bodySmall)
                        if (state.total > 0) {
                            LinearProgressIndicator(
                                progress = { (state.received.toFloat() / state.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("正在下载 ${(100.0 * state.received / state.total).toInt().coerceIn(0, 100)}%")
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("正在下载更新…")
                        }
                        Text("下载会继续，可稍后返回这里安装", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateDownloadState.Ready -> Text("下载完成，安装包已通过版本和签名检查")
                    is UpdateDownloadState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    UpdateDownloadState.Idle -> Unit
                }
                if (currentDownload !is UpdateDownloadState.Downloading && currentDownload !is UpdateDownloadState.Ready) {
                    current.downloads.forEach { item ->
                        Button(
                            onClick = { checker.downloadUpdate(context, item) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("下载更新 · ${item.size}") }
                    }
                }
            }
        },
        confirmButton = {
            val ready = currentDownload as? UpdateDownloadState.Ready
            if (ready != null) {
                TextButton(onClick = { checker.installDownloadedUpdate(context, ready) }) { Text("安装更新") }
            } else {
                TextButton(onClick = { context.openUrl(ProjectLinks.RELEASES) }) { Text("版本发布页") }
            }
        },
        dismissButton = { TextButton(onClick = checker::dismissPrompt) { Text("稍后") } },
    )
}
