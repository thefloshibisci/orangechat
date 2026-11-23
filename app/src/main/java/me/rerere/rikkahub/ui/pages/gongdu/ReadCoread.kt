package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.border
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.data.reading.*

internal data class ReadPalette(val paper: Color, val sheet: Color, val ink: Color, val body: Color, val muted: Color)
internal val LocalReadPalette = staticCompositionLocalOf {
    ReadPalette(Color(0xFFFFFCF3), Color(0xFFF4F1EA), Color(0xFF2C2A26), Color(0xFF333333), Color(0xFF76726A))
}

@Composable
internal fun ReadTheme(settings: ReadSettings, content: @Composable () -> Unit) {
    val dark = settings.theme in setOf("dark", "bluecard-night")
    val palette = if (dark) ReadPalette(Color(0xFF1A1A1A), Color(0xFF242424), Color(0xFFDDDDDD), Color(0xFFCCCCCC), Color(0xFFA39F97))
        else ReadPalette(when (settings.theme) { "shelf" -> Color(0xFFFAF7F2); "white" -> Color(0xFFFEFDFC); else -> Color(0xFFF1E8D9) },
            Color(0xFFE8DECE), Color(0xFF292720), Color(0xFF292720), Color(0xFF746E63))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(primary = palette.ink, onPrimary = palette.paper, primaryContainer = palette.sheet,
        onPrimaryContainer = palette.ink, secondary = palette.muted, secondaryContainer = palette.sheet,
        onSecondaryContainer = palette.ink, surface = palette.paper, onSurface = palette.ink,
        onSurfaceVariant = palette.muted, surfaceContainerLowest = palette.paper, surfaceContainerLow = palette.paper,
        surfaceContainer = palette.sheet, surfaceContainerHigh = palette.sheet, surfaceContainerHighest = palette.sheet,
        outline = palette.muted, outlineVariant = palette.muted.copy(alpha = .20f))
    val t = MaterialTheme.typography
    fun androidx.compose.ui.text.TextStyle.readType() = copy(
        fontFamily = GongduSans, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None)
    CompositionLocalProvider(LocalReadPalette provides palette) {
        MaterialTheme(colorScheme = colors, typography = t.copy(
            bodyLarge = t.bodyLarge.readType(), bodyMedium = t.bodyMedium.readType(), bodySmall = t.bodySmall.readType(),
            titleLarge = t.titleLarge.readType(), titleMedium = t.titleMedium.readType(), titleSmall = t.titleSmall.readType(),
            displayLarge = t.displayLarge.readType(), displayMedium = t.displayMedium.readType(), displaySmall = t.displaySmall.readType(),
            headlineLarge = t.headlineLarge.readType(), headlineMedium = t.headlineMedium.readType(),
            headlineSmall = t.headlineSmall.readType(), labelLarge = t.labelLarge.readType(),
            labelMedium = t.labelMedium.readType(), labelSmall = t.labelSmall.readType()), content = content)
    }
}

@Composable
internal fun ReadRoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
        Box(Modifier.size(36.dp)
            .dropShadow(CircleShape, Shadow(6.dp, Color.Black.copy(alpha = .09f), offset = DpOffset(0.dp, 2.dp)))
            .background(if (dark) Color(0xFF302E2C) else Color(0xFFFFFCF8), CircleShape)
            .border(.75.dp, Color.White.copy(alpha = if (dark) .14f else .72f), CircleShape),
            contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(17.dp)) }
    }
}

@Composable
internal fun ReadSheetHeading(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, "关闭$title", Modifier.size(21.dp)) }
    }
}

@Composable
internal fun ReadShelf(
    books: List<ReadBookEntry>, busy: Boolean, modifier: Modifier = Modifier,
    onImport: () -> Unit, onOpen: (ReadBookEntry) -> Unit, onDelete: (ReadBookEntry) -> Unit,
    cover: suspend (String) -> ReadImage? = { null }, managing: Boolean = false,
) {
    val palette = LocalReadPalette.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 600.dp) GridCells.Fixed(3) else GridCells.Adaptive(128.dp)
        LazyVerticalGrid(columns, Modifier.fillMaxSize().testTag("read-shelf"),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (books.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(HugeIcons.BookOpen01, null, Modifier.size(42.dp), tint = palette.muted)
                    Text("书架还空着", fontSize = 22.sp)
                    Text("选一本书，留一点页边话给彼此。", color = palette.muted, fontSize = 14.sp)
                    OutlinedButton(onClick = onImport, enabled = !busy) { Text("导入一本书") }
                    Text("TXT · EPUB", color = palette.muted, fontSize = 12.sp)
                }
            }
            items(books, key = { it.id }) { book ->
                val image by produceState<ReadImage?>(null, book.id) {
                    try { value = cover(book.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { value = null }
                }
                val jacket = RoundedCornerShape(topStart = 2.dp, topEnd = 11.dp, bottomEnd = 11.dp, bottomStart = 2.dp)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.padding(bottom = 4.dp)) {
                        Surface(shape = jacket, color = palette.sheet,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                                .dropShadow(jacket, Shadow(6.dp, Color(0x343D3026), offset = DpOffset(1.dp, 3.dp)))
                                .testTag("read-book-${book.id}")
                                .semantics { contentDescription = "继续读《${book.title}》" }
                                .clickable(enabled = !busy) { onOpen(book) }) {
                            Box(Modifier.fillMaxSize()) {
                                if (image != null) ReadBookImage(image, Modifier.fillMaxSize(), cover = true)
                                else Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                                    Color(0xFFE5DDCF), Color(0xFFD1C7B3)))).padding(start = 12.dp, end = 8.dp, top = 16.dp),
                                    verticalArrangement = Arrangement.SpaceBetween) {
                                    Text(book.title, fontFamily = GongduSans, fontSize = 15.sp, lineHeight = 22.sp,
                                        color = Color(0xFF39372E), maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    Text(book.author.ifBlank { book.format.uppercase() }, Modifier.padding(bottom = 16.dp),
                                        fontSize = 10.sp, maxLines = 2, color = Color(0xFF555044))
                                }
                                Box(Modifier.width(7.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(
                                    Color.Black.copy(alpha = .16f), Color.White.copy(alpha = .23f), Color.Black.copy(alpha = .12f), Color.Transparent))))
                            }
                        }
                        if (managing) IconButton(onClick = { onDelete(book) }, enabled = !busy,
                            modifier = Modifier.align(Alignment.TopStart).testTag("read-delete-${book.id}")) {
                            Surface(modifier = Modifier.offset(x = (-7).dp, y = (-7).dp), shape = CircleShape, color = Color.Black.copy(alpha = .48f)) {
                                Icon(HugeIcons.Cancel01, "移出书架《${book.title}》", Modifier.padding(5.dp).size(9.dp), tint = Color.White)
                            }
                        }
                    }
                    Text(book.title, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("第 ${book.progress.chapter + 1} / ${book.chapterCount} 章", fontSize = 11.sp, color = palette.muted)
                }
            }
            if (books.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImport, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Icon(HugeIcons.Add01, "导入一本书", Modifier.size(28.dp), tint = palette.muted)
                    }
                    Text("导入书籍", fontSize = 14.sp, color = palette.muted)
                }
            }
        }
    }
}

internal data class ReadSettingColors(val ink: Color, val wash: Color, val paper: Color, val edge: Color)

@Composable
internal fun readSettingColors(): ReadSettingColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return if (dark) ReadSettingColors(Color(0xFFDFBE9B), Color(0xFF3D332C), Color(0xFF262422), Color(0xFF544A40))
    else ReadSettingColors(Color(0xFF705238), Color(0xFFE7D8C8), Color(0xFFFBF7EF), Color(0xFFCCBEAD))
}

@Composable
internal fun ReadAppearance(settings: ReadSettings, onChange: (ReadSettings) -> Unit, onDismiss: () -> Unit = {},
    sample: String = "暮色落在窗边，书页上还留着一线光。", extraContent: @Composable ColumnScope.() -> Unit = {}) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var lineHeight by remember(settings.lineHeight) { mutableFloatStateOf(settings.lineHeight) }
    var letterSpacing by remember(settings.letterSpacing) { mutableFloatStateOf(settings.letterSpacing) }
    var paragraphSpacing by remember(settings.paragraphSpacing) { mutableFloatStateOf(settings.paragraphSpacing.toFloat()) }
    val dark = settings.theme in setOf("dark", "bluecard-night")
    val colors = readSettingColors()
    val accent = colors.ink
    val rule = colors.edge
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("read-appearance"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReadSheetHeading("阅读设置", onDismiss)
        val paperShape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 12.dp, bottomEnd = 12.dp)
        Column(Modifier.fillMaxWidth()
            .dropShadow(paperShape, Shadow(3.dp, Color.Black.copy(alpha = .08f), offset = DpOffset(0.dp, 2.dp)))
            .background(colors.paper, paperShape).border(.75.dp, rule, paperShape).padding(horizontal = 14.dp)) {
            Text(sample, Modifier.padding(top = 12.dp, bottom = 8.dp), fontFamily = GongduSans,
                fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * lineHeight).sp,
                letterSpacing = letterSpacing.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            HorizontalDivider(color = rule)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("字号", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = accent)
                TextButton(onClick = { onChange(settings.copy(fontSize = settings.fontSize - 1)) }, enabled = settings.fontSize > 14) { Text("A−") }
                TextButton(onClick = { onChange(settings.copy(fontSize = 20)) }, modifier = Modifier.testTag("read-font-default")
                    .semantics { contentDescription = "恢复默认字号20" }) { Text(settings.fontSize.toString(), fontSize = 18.sp) }
                TextButton(onClick = { onChange(settings.copy(fontSize = settings.fontSize + 1)) }, enabled = settings.fontSize < 30) { Text("A+") }
            }
        }
        ReadSettingSegments("纸色", listOf("日间", "夜间"), if (dark) 1 else 0, colors, capsules = true) {
            onChange(settings.copy(theme = if (it == 0) "paper" else "dark"))
        }
        ReadSettingSegments("翻页", listOf("滑动", "点按"), if (settings.tapTurn) 1 else 0, colors, capsules = true) {
            onChange(settings.copy(tapTurn = it == 1))
        }
        ReadTurnOptions(settings, onChange)
        HorizontalDivider(color = rule)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { advanced = !advanced },
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (advanced) "收起更多排版" else "更多排版", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp).rotate(if (advanced) -90f else 90f), tint = accent)
        }
        if (advanced) {
            ReadSpacingSlider("行距", "%.1f".format(lineHeight), lineHeight, 1.4f..2.4f, accent,
                { lineHeight = it }, { onChange(settings.copy(lineHeight = lineHeight)) })
            ReadSpacingSlider("字距", "%.1f".format(letterSpacing), letterSpacing, 0f..3f, accent,
                { letterSpacing = it }, { onChange(settings.copy(letterSpacing = letterSpacing)) })
            ReadSpacingSlider("段距", paragraphSpacing.toInt().toString(), paragraphSpacing, 8f..36f, accent,
                { paragraphSpacing = it }, { onChange(settings.copy(paragraphSpacing = paragraphSpacing.toInt())) })
            val modes = listOf("scroll", "page", "spread")
            ReadSettingSegments("版式", listOf("上下滚动", "单页翻读", "双页对读"), modes.indexOf(settings.mode), colors) {
                onChange(settings.copy(mode = modes[it]))
            }
            ReadSettingSwitchRow("阅读尺", "显示阅读尺", settings.readingRuler,
                onChange = { onChange(settings.copy(readingRuler = it)) })
        }
        extraContent()
    }
}

@Composable
private fun ReadSettingSegments(label: String, labels: List<String>, selected: Int, colors: ReadSettingColors, capsules: Boolean = false, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.widthIn(min = 50.dp), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(if (capsules) 6.dp else 0.dp)) {
            labels.forEachIndexed { index, text ->
                val active = index == selected
                Box(Modifier.weight(1f).heightIn(min = 48.dp).selectable(active, role = Role.RadioButton) { onSelect(index) },
                    contentAlignment = Alignment.Center) {
                    val ink = MaterialTheme.colorScheme.onSurface
                    if (capsules) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 34.dp)
                            .background(if (active) colors.wash else Color.Transparent, CircleShape)
                            .border(1.dp, if (active) colors.ink.copy(alpha = .65f) else colors.edge, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Text(text, style = MaterialTheme.typography.labelLarge,
                                color = if (active) colors.ink else ink)
                        }
                    } else {
                        Text(text, Modifier.background(if (active) colors.wash else Color.Transparent, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge,
                            color = if (active) colors.ink else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadSpacingSlider(label: String, displayValue: String, value: Float, range: ClosedFloatingPointRange<Float>,
    accent: Color, onValueChange: (Float) -> Unit, onFinish: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value, onValueChange, valueRange = range, onValueChangeFinished = onFinish,
            modifier = Modifier.weight(1f).semantics { contentDescription = "阅读$label" },
            thumb = { Box(Modifier.size(16.dp).background(accent, CircleShape)) },
            track = {
                val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(accent.copy(alpha = .20f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(accent))
                }
            })
        Text(displayValue, modifier = Modifier.widthIn(min = 26.dp), style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

private val ReadSettingRowInset = 18.dp

@Composable
internal fun ReadSettingSwitchRow(label: String, actionLabel: String, checked: Boolean,
    supportingText: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = actionLabel }
        .padding(horizontal = ReadSettingRowInset, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (supportingText != null) Text(supportingText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val colors = readSettingColors()
        Switch(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.testTag("read-toggle-$actionLabel"),
            colors = SwitchDefaults.colors(checkedTrackColor = colors.ink, checkedThumbColor = colors.paper,
                uncheckedTrackColor = colors.paper, uncheckedBorderColor = colors.edge,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

@Composable
internal fun ReadMenuRow(icon: ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = ReadSettingRowInset),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(21.dp))
        Text(text, Modifier.weight(1f), fontSize = 16.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ReadShelfMenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, Modifier.size(17.dp))
        Text(text, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 19.sp)
        Icon(HugeIcons.ArrowRight01, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
