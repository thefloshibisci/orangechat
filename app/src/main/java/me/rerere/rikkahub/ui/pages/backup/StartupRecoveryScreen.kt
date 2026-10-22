
package me.rerere.rikkahub.ui.pages.backup

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.rikkahub.data.sync.*

/** Startup must remain independent of settings, navigation and chat dependency creation. */
@Composable
internal fun StartupRecoveryScreen(recovery: StartupRecovery) {
    val state by recovery.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diagnosticStatus by remember { mutableStateOf("") }
    LaunchedEffect(recovery) {
        withFrameNanos { }
        withFrameNanos { }
        StartupRecoveryDiagnostics.record(context, "恢复界面已显示")
        recovery.onRecoveryScreenShown()
    }
    RecoveryTheme {
        RecoveryContent(state, recovery::retry, recovery::cancelAndContinue, diagnosticStatus) {
            scope.launch {
                val report = withContext(Dispatchers.IO) { StartupRecoveryDiagnostics.report(context) }
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("OrangeChat 启动诊断", report))
                diagnosticStatus = "启动诊断已复制，不含聊天记录。"
            }
        }
    }
}

@Composable
internal fun RecoveryTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFBDC2FF), onPrimary = Color(0xFF282C66),
        primaryContainer = Color(0xFF353B69), onPrimaryContainer = Color(0xFFE2E4FF),
        background = Color(0xFF15151D), surface = Color(0xFF21212C),
        onSurface = Color(0xFFE8E7F0), onSurfaceVariant = Color(0xFFBDBCCB),
        outlineVariant = Color(0xFF484957),
    ) else lightColorScheme(
        primary = Color(0xFF6169B7), onPrimary = Color.White,
        primaryContainer = Color(0xFFE8E9FC), onPrimaryContainer = Color(0xFF444B87),
        background = Color(0xFFF7F7FC), surface = Color(0xFFFEFDFF),
        onSurface = Color(0xFF252632), onSurfaceVariant = Color(0xFF727382),
        outlineVariant = Color(0xFFDADBE8),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
internal fun RecoveryContent(
    state: StartupRecoveryState,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    diagnosticStatus: String = "",
    onCopyDiagnostics: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progress = (state as? StartupRecoveryState.Restoring)?.progress
    val cancelling = state == StartupRecoveryState.Cancelling
    val waiting = state == StartupRecoveryState.AwaitingConfirmation
    val failed = state as? StartupRecoveryState.Failed
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("恢复聊天记录", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(when {
                        cancelling -> "正在取消恢复，请稍候…"
                        failed != null -> "恢复未完成"
                        waiting -> "上次恢复尚未完成"
                        else -> "正在从备份恢复中，请稍候…"
                    }, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                    Text(if (waiting) "可以继续上次任务，或取消后进入应用。原备份文件仍会保留。"
                        else "恢复完成后将自动进入应用，\n聊天和后台任务随后恢复运行。",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(28.dp), color = colors.surface, shadowElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer) {
                                Icon(HugeIcons.Database02, null, Modifier.padding(12.dp).size(28.dp), tint = colors.primary)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(when {
                                    cancelling -> "正在取消"
                                    failed != null -> "需要处理"
                                    waiting -> "等待继续"
                                    else -> "正在恢复中"
                                }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(when {
                                    cancelling -> "正在等待当前写入回滚"
                                    failed != null -> "原备份文件仍然保留"
                                    waiting -> "请选择下方操作"
                                    else -> "当前步骤：${restoreStepTitle(progress?.step ?: ChatRestoreStep.READING)}"
                                }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                        }
                        if (failed != null) {
                            Text("恢复未完成：${failed.message}", color = colors.error, style = MaterialTheme.typography.bodyMedium)
                            Text("取消待恢复任务后进入应用，再重新选择原备份。", style = MaterialTheme.typography.bodyMedium)
                        } else if (cancelling) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("请等待回滚完成，完成后会自动进入应用。", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            if (waiting) LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                            else if (progress?.fraction != null) LinearProgressIndicator(
                                progress = { progress.fraction!! }, modifier = Modifier.fillMaxWidth().height(10.dp),
                                trackColor = colors.primaryContainer)
                            else LinearProgressIndicator(Modifier.fillMaxWidth().height(10.dp), trackColor = colors.primaryContainer)
                            progress?.let { Text(it.message, style = MaterialTheme.typography.labelLarge, color = colors.primary) }
                            RestoreSteps(active = if (waiting) null else progress?.step ?: ChatRestoreStep.READING)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(HugeIcons.Clock02, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                                Text("耗时取决于消息和附件数量，请保持应用打开。", style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (waiting) Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("继续恢复聊天记录")
                    }
                    OutlinedButton(onClick = onCancel, enabled = !cancelling && state != StartupRecoveryState.Ready,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
                        Text(if (cancelling) "正在取消，请稍候…" else "取消恢复并进入应用")
                    }
                    Text("取消会等待本次未提交的写入回滚；已提交的恢复不会撤销。原备份文件保留。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp))
                }
                Surface(shape = RoundedCornerShape(24.dp), color = colors.surface) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("遇到问题？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("如果恢复长时间没有进展，可以复制启动诊断反馈给开发者。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        FilledTonalButton(onClick = onCopyDiagnostics, modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.primaryContainer,
                                contentColor = colors.onPrimaryContainer)) {
                            Icon(HugeIcons.Copy01, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("复制启动诊断")
                        }
                        if (diagnosticStatus.isNotEmpty()) Text(diagnosticStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun restoreStepTitle(step: ChatRestoreStep) = when (step) {
    ChatRestoreStep.READING -> "读取聊天备份"
    ChatRestoreStep.VALIDATING -> "校验数据"
    ChatRestoreStep.WRITING -> "写入聊天记录"
    ChatRestoreStep.FINISHING -> "完成并进入应用"
}

@Composable
private fun RestoreSteps(active: ChatRestoreStep?) {
    val colors = MaterialTheme.colorScheme
    Column {
        ChatRestoreStep.entries.forEachIndexed { index, step ->
            val completed = active != null && step.ordinal < active.ordinal
            val current = step == active
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Canvas(Modifier.width(28.dp).fillMaxHeight()) {
                    val center = Offset(size.width / 2, 14.dp.toPx())
                    if (index != ChatRestoreStep.entries.lastIndex) drawLine(colors.outlineVariant,
                        center.copy(y = 28.dp.toPx()), center.copy(y = size.height), 2.dp.toPx())
                    when {
                        completed -> {
                            drawCircle(colors.primary, 12.dp.toPx(), center)
                            val tick = Path().apply {
                                moveTo(center.x - 5.dp.toPx(), center.y)
                                lineTo(center.x - 1.dp.toPx(), center.y + 4.dp.toPx())
                                lineTo(center.x + 6.dp.toPx(), center.y - 4.dp.toPx())
                            }
                            drawPath(tick, colors.onPrimary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                        }
                        current -> {
                            drawCircle(colors.primaryContainer, 14.dp.toPx(), center)
                            drawCircle(colors.primary.copy(alpha = 0.45f), 11.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                            drawCircle(colors.primary, 6.dp.toPx(), center)
                        }
                        else -> drawCircle(colors.outlineVariant, 10.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                    }
                }
                Column(Modifier.weight(1f).padding(top = 2.dp, bottom = if (index == 3) 0.dp else 18.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(restoreStepTitle(step), style = MaterialTheme.typography.titleSmall,
                        color = if (completed || current) colors.primary else colors.onSurfaceVariant)
                    Text(when { completed -> "已完成"; current -> "正在进行中…"; else -> "等待中" },
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
