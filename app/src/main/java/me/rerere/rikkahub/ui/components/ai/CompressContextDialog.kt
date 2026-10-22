/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.theme.popupContainerColor

@Composable
fun CompressContextDialog(
    onDismiss: () -> Unit,
    onConfirm: suspend (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int, mergeWithPrevious: Boolean, onProgress: (String) -> Unit) -> Unit
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var selectedTokens by remember { mutableIntStateOf(2000) }
    var keepRecentMessages by remember { mutableIntStateOf(32) }
    var mergeWithPrevious by remember { mutableStateOf(true) }
    val tokenOptions = listOf(500, 1000, 2000, 4000)
    val keepRecentOptions = listOf(0, 16, 32, 64)
    var currentJob by remember { mutableStateOf<Job?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                onDismiss()
            }
        },
        containerColor = popupContainerColor(AlertDialogDefaults.containerColor),
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    // Loading state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.chat_page_compressing))
                            Text(progress, style = MaterialTheme.typography.bodySmall)
                            Text("整段一次提交，保留工具内容；单次最多等待 3 分钟，可随时取消。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    // Token size selector
                    Text(
                        text = stringResource(R.string.chat_page_compress_target_tokens),
                        style = MaterialTheme.typography.labelMedium
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tokenOptions.forEachIndexed { index, tokens ->
                            SegmentedButton(
                                selected = selectedTokens == tokens,
                                onClick = { selectedTokens = tokens },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = tokenOptions.size
                                )
                            ) {
                                Text("$tokens")
                            }
                        }
                    }

                    // Keep recent messages selector
                    Text(
                        text = stringResource(R.string.chat_page_compress_keep_recent),
                        style = MaterialTheme.typography.labelMedium
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        keepRecentOptions.forEachIndexed { index, count ->
                            SegmentedButton(
                                selected = keepRecentMessages == count,
                                onClick = { keepRecentMessages = count },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = keepRecentOptions.size
                                )
                            ) {
                                Text("$count")
                            }
                        }
                    }

                    // Additional context input
                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = mergeWithPrevious,
                            role = Role.Switch,
                            onValueChange = { mergeWithPrevious = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.chat_page_compress_merge_previous))
                            Text(
                                text = stringResource(if (mergeWithPrevious) {
                                    R.string.chat_page_compress_merge_previous_on
                                } else {
                                    R.string.chat_page_compress_merge_previous_off
                                }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = mergeWithPrevious, onCheckedChange = null)
                    }

                    // Warning text
                    Text(
                        text = stringResource(R.string.chat_page_compress_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    currentJob?.cancel()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = {
                    isLoading = true
                    error = null
                    progress = "正在准备压缩内容…"
                    currentJob = scope.launch {
                        try {
                            onConfirm(additionalPrompt, selectedTokens, keepRecentMessages, mergeWithPrevious) {
                                progress = it
                            }
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "压缩失败，请重试"
                        } finally {
                            isLoading = false
                            currentJob = null
                        }
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
