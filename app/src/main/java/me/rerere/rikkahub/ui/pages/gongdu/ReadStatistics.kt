package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import me.rerere.rikkahub.data.reading.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlin.math.*

@Composable
internal fun ReadStatistics(repo: ReadRepository, bookId: String?, conversationId: String, assistantId: String?,
    assistantName: String, onDismiss: () -> Unit, readingWrite: Job? = null, readingPosition: String? = null) {
    var records by remember(bookId) { mutableStateOf<List<ReadRecord>?>(null) }
    var error by remember(bookId) { mutableStateOf<String?>(null) }
    LaunchedEffect(bookId, readingWrite) {
        try {
            readingWrite?.join()
            records = repo.readingRecords(bookId)
            error = null
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "阅读记录未能载入" }
    }
    if (error != null) { ReadSheetHeading("阅读统计", onDismiss); Text(error!!, color = MaterialTheme.colorScheme.error) }
    else if (records == null) { ReadSheetHeading("阅读统计", onDismiss); CircularProgressIndicator() }
    else ReadStatisticsContent(records!!, bookId == null, conversationId, assistantId, assistantName, onDismiss, readingPosition)
}

internal fun readDuration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600} 时 ${seconds % 3600 / 60} 分"
    seconds >= 60 -> "${seconds / 60} 分"
    else -> "$seconds 秒"
}

@Composable
internal fun ReadStatisticsContent(records: List<ReadRecord>, allBooks: Boolean, conversationId: String,
    assistantId: String?, assistantName: String, onDismiss: () -> Unit = {}, readingPosition: String? = null) {
    var companion by remember { mutableStateOf(false) }
    var chartInfo by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val visible = if (companion) records.filter { it.conversationId == conversationId && it.assistantId == assistantId }
        else records
    val stats = readStats(visible, today, zone)
    val receipts = visible.filter { it.kind == "assistant_reading" }
    val daily = if (companion) receipts.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
        .mapValues { (_, rows) -> rows.sumOf { it.characters.toLong() } } else stats.dailySeconds
    val colors = readSettingColors()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("read-statistics"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            val target = if (companion) "看我的" else "看${assistantName}的"
            val scope = if (allBooks) "全部书籍" else "这本书"
            Layout(modifier = Modifier.weight(1f), content = {
                Text(if (companion) "${assistantName}的阅读记录" else "我的阅读统计",
                    modifier = Modifier.padding(top = 6.dp), fontSize = 22.sp, lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium)
                Surface(onClick = { companion = !companion }, modifier = Modifier.semantics { role = Role.Button },
                    shape = RoundedCornerShape(7.dp), color = Color.Transparent, contentColor = colors.ink,
                    border = BorderStroke(.75.dp, colors.edge)) {
                    Text("$target·$scope", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Normal,
                        autoSize = TextAutoSize.StepBased(11.sp, 12.sp, .5.sp), lineHeight = 16.sp,
                        letterSpacing = .2.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }) { children, constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val title = children[0].measure(loose)
                val button = children[1].measure(loose.copy(maxWidth = title.width))
                layout(constraints.maxWidth, title.height + button.height) {
                    title.placeRelative(0, 0)
                    button.placeRelative(0, title.height)
                }
            }
            IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, "关闭阅读统计", Modifier.size(21.dp)) }
        }
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(thickness = .75.dp, color = colors.edge)
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (companion) {
                    val todayReceipts = receipts.count { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() == today }
                    StatMetric("$todayReceipts 条", "今日片段", Modifier.weight(1f))
                    StatMetric("${stats.assistantFragments} 条", "累计片段", Modifier.weight(1f))
                    StatMetric("${stats.assistantCharacters} 字", "读取原文", Modifier.weight(1f))
                } else {
                    StatMetric(readDuration(stats.todaySeconds), "今日", Modifier.weight(1f))
                    StatMetric(readDuration(stats.totalSeconds), "累计", Modifier.weight(1f))
                    StatMetric("${stats.streak} 天", "连续阅读", Modifier.weight(1f))
                }
            }
            if (!companion && readingPosition != null) {
                Text("我的进度 · $readingPosition", Modifier.padding(bottom = 12.dp),
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(thickness = .75.dp, color = colors.edge)
        }
        if (daily.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (companion) "每天读了哪些" else "每天读了多少", fontSize = 18.sp)
            Text(if (companion) "一个点是一天 · 大小表示读取的原文字数" else "一个点是一天 · 点越大越深，读得越久",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReadDailyDots(daily, today)
        }
        if (companion || stats.sittings.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (companion) "一起读过的片段" else "一般几点读", fontSize = 18.sp)
            Text(if (companion) "来自实际读取记录，未换算为阅读时长。" else "近 12 周 · 一瓣是一条阅读记录 · 点图看说明",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (companion) {
            Text(if (receipts.isEmpty()) "还没有${assistantName}的读取记录。" else
                "${assistantName}读过 ${stats.assistantFragments} 个片段，共 ${stats.assistantCharacters} 字。",
                Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReadTimeFlower(stats.sittings, today, zone) { chartInfo = true }
        }
        }
        if (stats.totalSeconds == 0L && !companion) Text("暂无阅读时长记录",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (chartInfo) AlertDialog(onDismissRequest = { chartInfo = false }, title = { Text("这张图怎么读") },
        text = { Text("00 点在上，06 点在右。每瓣方向来自记录时间，长短表示阅读时长，今天的记录有描边。旧版按天合并的记录仍显示为一瓣，无法还原成多次阅读。") },
        confirmButton = { TextButton(onClick = { chartInfo = false }) { Text("知道了") } })
}

@Composable
private fun StatMetric(value: String, label: String, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val formatted = buildAnnotatedString {
        append(value)
        value.forEachIndexed { index, char ->
            if (char.isDigit()) addStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium), index, index + 1)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 18.sp, color = muted)
        Text(formatted, fontSize = 13.sp, lineHeight = 26.sp)
    }
}

@Composable
private fun ReadDailyDots(daily: Map<LocalDate, Long>, today: LocalDate) {
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(23)
    val maximum = daily.filterKeys { !it.isBefore(start) && !it.isAfter(today) }.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 22.dp).height(16.dp)) {
            for (week in 0..23) {
                val date = start.plusWeeks(week.toLong())
                if (week == 0 || date.month != date.minusWeeks(1).month) Text("${date.monthValue}月",
                    Modifier.offset(x = maxWidth * (week / 24f)), color = muted, fontSize = 9.sp,
                    softWrap = false, overflow = TextOverflow.Visible)
            }
        }
        Row(Modifier.height(108.dp)) {
            Column(Modifier.fillMaxHeight().width(22.dp)) {
                listOf("一", "", "三", "", "五", "", "日").forEach { label ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (label.isNotEmpty()) Text(label, fontSize = 9.sp, lineHeight = 11.sp, color = muted)
                    }
                }
            }
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "近24周阅读点阵，${daily.count { it.value > 0 }}天有记录" }) {
                val dx = size.width / 24
                val dy = size.height / 7
                for (week in 0..23) for (day in 0..6) {
                    val date = start.plusDays(week * 7L + day)
                    if (date.isAfter(today)) continue
                    val amount = daily[date] ?: 0
                    val ratio = sqrt(amount.toFloat() / maximum)
                    val center = Offset(dx * (week + .5f), dy * (day + .5f))
                    val radius = if (amount == 0L) 1.dp.toPx() else (1.3f + ratio * 4.2f).dp.toPx().coerceAtMost(minOf(dx, dy) * .40f)
                    val color = if (amount == 0L) muted.copy(alpha = .18f) else if (ratio < .35f) Color(0xFFBAD6EB)
                        else if (ratio < .7f) Color(0xFF7096D1) else Color(0xFF5C63A8)
                    drawCircle(color, radius, center)
                    if (date == today) drawCircle(muted.copy(alpha = .6f), radius + 2.dp.toPx(), center, style = Stroke(.7.dp.toPx()))
                }
            }
        }
        val peak = daily.filterKeys { !it.isBefore(start) && !it.isAfter(today) }.maxByOrNull { it.value }
        if (peak != null) Text("读得最多的一天 · ${peak.key.monthValue}/${peak.key.dayOfMonth}",
            Modifier.align(Alignment.End), fontSize = 10.sp, color = muted)
    }
}

@Composable
private fun ReadTimeFlower(records: List<ReadRecord>, today: LocalDate, zone: ZoneId, onInfo: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f).clickable(onClickLabel = "阅读时间图说明", onClick = onInfo)
        .semantics { contentDescription = "近12周阅读时间分布，${records.size}条记录" }) {
        listOf("00" to Alignment.TopCenter, "06" to Alignment.CenterEnd,
            "12" to Alignment.BottomCenter, "18" to Alignment.CenterStart).forEach { (label, alignment) ->
            Text(label, Modifier.align(alignment), color = muted, fontSize = 10.sp)
        }
        Canvas(Modifier.fillMaxSize().padding(22.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 - 4.dp.toPx()
            for (tick in 0..95) {
                val theta = tick * PI / 48 - PI / 2
                val length = if (tick % 4 == 0) 5.dp.toPx() else 2.dp.toPx()
                val vector = Offset(cos(theta).toFloat(), sin(theta).toFloat())
                drawLine(muted.copy(alpha = .28f), center + vector * radius, center + vector * (radius - length), 1.dp.toPx())
            }
            records.forEach { record ->
                val time = Instant.ofEpochMilli(record.createdAt).atZone(zone)
                if (time.toLocalDate().isBefore(today.minusWeeks(12)) || time.toLocalDate().isAfter(today)) return@forEach
                val angle = time.toLocalTime().toSecondOfDay() / 240f - 90f
                val sweep = (record.seconds / 240f).coerceIn(.8f, 90f)
                val extent = (radius * (.17f + sqrt(record.seconds / 60f) * .075f)).coerceAtMost(radius * .90f)
                val origin = center - Offset(extent, extent)
                val size = Size(extent * 2, extent * 2)
                val color = Color(0xFF555DB2)
                drawArc(color.copy(alpha = .18f), angle, sweep, true, origin, size)
                if (time.toLocalDate() == today) drawArc(color, angle, sweep, true, origin, size, style = Stroke(1.dp.toPx()))
            }
            drawCircle(Color(0xFF555DB2), 2.5.dp.toPx(), center)
        }
    }
}
