package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.reading.ReadBook

/** Controls overlay the page, so opening them never changes the reading position. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ReadReaderControls(book: ReadBook, chapter: Int, dark: Boolean, enabled: Boolean,
    onChapter: (Int) -> Unit, onContents: () -> Unit, onNight: () -> Unit,
    onSettings: () -> Unit, onAnnotations: () -> Unit, modifier: Modifier = Modifier) {
    var target by remember(chapter) { mutableFloatStateOf(chapter.toFloat()) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().testTag("read-controls"), horizontalAlignment = Alignment.End) {
        Box(Modifier.padding(end = 10.dp, bottom = 4.dp).size(48.dp)
            .clickable(role = Role.Button, onClick = onAnnotations)
            .semantics { contentDescription = "看批注" }, contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.read_annotation_bear), null, Modifier.size(36.dp),
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(.65f) }))
        }
        Surface(color = colors.surface) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .45f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TextButton(onClick = { onChapter(chapter - 1) }, enabled = enabled && chapter > 0,
                        contentPadding = PaddingValues(horizontal = 5.dp)) { Text("上一章", fontSize = 12.sp) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().height(32.dp).background(colors.surfaceContainer, CircleShape))
                        if (book.chapters.size > 1) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                Slider(target, { target = it }, enabled = enabled,
                                    valueRange = 0f..book.chapters.lastIndex.toFloat(),
                                    onValueChangeFinished = { onChapter(target.toInt().coerceIn(book.chapters.indices)) },
                                    thumb = { Spacer(Modifier.size(ReadChapterRabbitThumb, 30.dp)) },
                                    track = { Spacer(Modifier.fillMaxWidth().height(1.dp)) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                        .semantics { contentDescription = "章节进度" })
                                ReadChapterRabbit(target / book.chapters.lastIndex, enabled, Modifier.matchParentSize())
                            }
                        } else Text("1 / 1 章", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    TextButton(onClick = { onChapter(chapter + 1) }, enabled = enabled && chapter < book.chapters.lastIndex,
                        contentPadding = PaddingValues(horizontal = 5.dp)) { Text("下一章", fontSize = 12.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                    ReadToolbarAction(HugeIcons.Menu03, "目录", onContents, Modifier.weight(1f))
                    ReadToolbarAction(if (dark) HugeIcons.Sun03 else HugeIcons.Moon02,
                        if (dark) "日间" else "夜间", onNight, Modifier.weight(1f))
                    ReadToolbarAction(HugeIcons.Settings03, "设置", onSettings, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ReadToolbarAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        Icon(icon, null, Modifier.size(22.dp))
        Text(label, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
internal fun ReadContents(book: ReadBook, chapter: Int, onSelect: (Int) -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val details = readSettingColors()
    val chapterStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (chapter - 2).coerceAtLeast(0))
    LaunchedEffect(chapter) { state.scrollToItem((chapter - 2).coerceAtLeast(0)) }
    ModalDrawerSheet(Modifier.widthIn(max = 360.dp).fillMaxWidth(.9f).testTag("read-contents-drawer"),
        drawerShape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp), drawerContainerColor = colors.surface,
        drawerTonalElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("章节目录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("${book.chapters.size} 章", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            IconButton(onClick = onClose) { Icon(HugeIcons.Cancel01, "关闭目录", Modifier.size(18.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp), color = colors.surfaceContainer) {
                ReadBookImage(book.coverImageId?.let { book.images[it] }, Modifier.size(32.dp, 45.dp), cover = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(book.title, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) Text(book.author, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = colors.outlineVariant)
        book.chapters.getOrNull(chapter)?.let { current ->
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp)
                .semantics(mergeDescendants = true) {}.testTag("read-pinned-chapter")
                .border(.75.dp, details.edge, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(HugeIcons.Bookmark01, null, Modifier.size(15.dp), tint = details.ink)
                Text("正在读", style = chapterStyle, color = details.ink)
                Text(current.title, Modifier.weight(1f), style = chapterStyle,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurface)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().selectableGroup(), state = state,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            itemsIndexed(book.chapters) { index, entry ->
                val selected = index == chapter
                val shape = RoundedCornerShape(6.dp)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                    .then(if (selected) Modifier.background(details.wash) else Modifier)
                    .selectable(selected, role = Role.RadioButton) { onSelect(index) }
                    .testTag("read-chapter-$index").padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hasChapterNumber = entry.title.startsWith("第") && '章' in entry.title.substringBefore(' ')
                    if (!hasChapterNumber) Text("${index + 1}", Modifier.widthIn(min = 24.dp),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(entry.title, Modifier.weight(1f), style = chapterStyle, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) details.ink else colors.onSurface)
                    if (selected) Text("正在读", style = MaterialTheme.typography.labelSmall, color = details.ink)
                }
            }
        }
    }
}
