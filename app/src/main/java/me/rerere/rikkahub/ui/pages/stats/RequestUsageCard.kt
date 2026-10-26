package me.rerere.rikkahub.ui.pages.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
internal fun RequestUsageCard(
    state: RequestUsageState,
    onRangeChange: (UsageTimeRange) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var breakdown by rememberSaveable { mutableStateOf(0) }
    Card(modifier = modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("请求用量账本 · 启用后", style = MaterialTheme.typography.titleMedium)
            Text(
                "仅记录此功能启用后的请求，不等同服务商账单。下方历史消息统计单独保留，不与账本相加。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageTimeRange.entries.forEach { range ->
                    FilterChip(selected = state.range == range, onClick = { onRangeChange(range) }, label = { Text(range.label) })
                }
            }
            Text("按本地时间统计", style = MaterialTheme.typography.labelSmall)
            when {
                state.isLoading -> {
                    CircularProgressIndicator()
                    Text("正在读取请求用量…", style = MaterialTheme.typography.bodyMedium)
                }
                state.error != null -> {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                state.summary != null -> {
                    val summary = state.summary
                    val totals = summary.totals
                    if (totals.requestCount == 0L) {
                        Text("这个时间范围还没有请求记录。新请求完成后会自动显示；旧消息仍可在下方历史统计查看。")
                    } else {
                        Text("${usageNumber(totals.requestCount)} 次请求", style = MaterialTheme.typography.titleLarge)
                        Text(reportedUsageLabel(totals.requestCount, totals.unreportedCount, totals.totalTokens))
                        if (totals.unreportedCount < totals.requestCount) {
                            Text(
                                "输入 ${usageNumber(totals.promptTokens)} · 输出 ${usageNumber(totals.completionTokens)} · 缓存 ${usageNumber(totals.cachedTokens)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("缓存属于输入的一部分，未额外计入总量。", style = MaterialTheme.typography.labelSmall)
                        }
                        if (totals.unreportedCount > 0) {
                            Text("${usageNumber(totals.unreportedCount)} 次未返回用量，实际消耗未知。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (totals.failedCount > 0) {
                            Text("${usageNumber(totals.failedCount)} 次未成功（中断或失败）", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("按时间", "按模型", "按用途").forEachIndexed { index, label ->
                                FilterChip(selected = breakdown == index, onClick = { breakdown = index }, label = { Text(label) })
                            }
                        }
                        when (breakdown) {
                            0 -> summary.buckets.forEach { bucket ->
                                UsageBreakdownRow(bucket.bucket, bucket.requestCount, bucket.totalTokens, bucket.unreportedCount)
                            }
                            1 -> summary.models.forEach { model ->
                                val name = model.modelName?.takeIf { it.isNotBlank() } ?: model.modelId ?: "未知模型"
                                val provider = model.providerId?.let { state.providerNames[it] } ?: "未知提供商"
                                UsageBreakdownRow("$name · $provider", model.requestCount, model.totalTokens, model.unreportedCount)
                            }
                            else -> summary.sources.forEach { source ->
                                UsageBreakdownRow(usageSourceLabel(source.source), source.requestCount, source.totalTokens, source.unreportedCount)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageBreakdownRow(title: String, requests: Long, totalTokens: Long, unreported: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${usageNumber(requests)} 次 · ${reportedUsageLabel(requests, unreported, totalTokens)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (unreported > 0) {
            Text("${usageNumber(unreported)} 次未返回用量", style = MaterialTheme.typography.labelSmall)
        }
    }
}
