package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.reading.*
import kotlin.math.roundToInt

@OptIn(FlowPreview::class)
@Composable
internal fun ReadPage(
    book: ReadBook, progress: ReadProgress, settings: ReadSettings, annotations: List<ReadAnnotation>,
    modifier: Modifier = Modifier, onProgress: (Int, Int) -> Unit,
    onSelect: (ReadParagraphSelection) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    trackProgress: Boolean = true, onPosition: (ReadProgress) -> Unit = { onProgress(it.chapter, it.paragraph) },
    onNavigatePosition: (ReadProgress) -> Unit = onPosition,
    onViewport: (ReadViewport?) -> Unit = {},
) {
    val chapterIndex = progress.chapter.coerceIn(0, book.chapters.lastIndex)
    val chapter = book.chapters[chapterIndex]
    val paragraphIndex = progress.paragraph.coerceIn(0, chapter.paragraphs.lastIndex)
    val scrolling = settings.mode == "scroll"
    val bodyStyle = TextStyle(fontFamily = GongduBook, fontSize = settings.fontSize.sp,
        lineHeight = (settings.fontSize * settings.lineHeight).sp, letterSpacing = settings.letterSpacing.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false))
    val latestPosition by rememberUpdatedState(onPosition)
    val latestViewport by rememberUpdatedState(onViewport)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 12)
    var layouts by remember(book.id, chapterIndex, settings) { mutableStateOf<List<ReadLayoutPage>?>(null) }
    var settledFrame by remember(book.id) { mutableStateOf<ReadTurnFrame?>(null) }
    var columns by remember { mutableIntStateOf(1) }
    val pageIndex = readPageForPosition(layouts.orEmpty(), progress)
    val spreadStart = if (columns == 2) pageIndex / 2 * 2 else pageIndex
    val pageTotal = layouts?.size ?: 0
    val pageSettled = settledFrame?.let { it.chapterIndex == chapterIndex && it.pages == layouts &&
        it.start == spreadStart && it.columns == columns && it.style == bodyStyle } == true
    LaunchedEffect(layouts, spreadStart, columns, trackProgress, progress, settings, pageSettled) {
        val visible = layouts?.drop(spreadStart)?.take(columns)
        if (!scrolling && trackProgress && pageSettled && !visible.isNullOrEmpty()) {
            val viewport = ReadViewport(chapterIndex, spreadStart, pageTotal, visible.flatMap { it.fragments }.map {
                ReadVisibleFragment(it.paragraph, it.start, it.end)
            })
            latestViewport(viewport)
            val head = visible.first().position(chapterIndex)
            if (head != progress) latestPosition(head)
        } else latestViewport(null)
    }
    DisposableEffect(book.id, chapterIndex) { onDispose { latestViewport(null) } }
    val previousPage: () -> Unit = {
        val previous = layouts?.getOrNull((spreadStart - columns).coerceAtLeast(0))
        if (!scrolling && spreadStart > 0 && previous != null) onNavigatePosition(previous.position(chapterIndex))
        else if (chapterIndex > 0) onPrevious()
    }
    val nextPage: () -> Unit = {
        val next = layouts?.getOrNull(spreadStart + columns)
        if (!scrolling && next != null) onNavigatePosition(next.position(chapterIndex))
        else if (chapterIndex < book.chapters.lastIndex) onNext()
    }
    Column(modifier.fillMaxWidth().testTag("read-page")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 13.dp, bottom = 10.dp)) {
            Text(chapter.title, fontFamily = GongduBook, fontSize = 21.sp, lineHeight = 29.sp,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (scrolling) "轻点文字，留一句页边话" else if (settings.mode == "spread" && columns == 1)
                "单页阅读 · 屏幕较窄，双页已收拢" else if (columns == 2) "双页阅读 · 轻点文字可批注" else "单页阅读 · 轻点文字可批注",
                Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)
            .testTag("read-body").clipToBounds()) {
            if (scrolling) {
                val state = rememberLazyListState(initialFirstVisibleItemIndex = paragraphIndex)
                var initialLayout by remember(chapterIndex) { mutableStateOf(true) }
                LaunchedEffect(book.id, chapterIndex) {
                    if (progress.offset > 0 && !chapter.images.containsKey(paragraphIndex)) {
                        val width = with(density) { (maxWidth - 32.dp).roundToPx() }.coerceAtLeast(1)
                        val result = measurer.measure(chapter.paragraphs[paragraphIndex], bodyStyle,
                            constraints = androidx.compose.ui.unit.Constraints(maxWidth = width))
                        val offset = progress.offset.coerceIn(0, chapter.paragraphs[paragraphIndex].lastIndex)
                        state.scrollToItem(paragraphIndex, result.getLineTop(result.getLineForOffset(offset)).roundToInt())
                    }
                    initialLayout = false
                }
                val lineOffsets = remember(book.id, chapterIndex) { mutableMapOf<Int, androidx.compose.ui.text.TextLayoutResult>() }
                LaunchedEffect(book.id, chapterIndex, trackProgress, initialLayout) {
                    if (trackProgress && !initialLayout) snapshotFlow {
                        val paragraph = state.firstVisibleItemIndex.coerceIn(chapter.paragraphs.indices)
                        val layout = lineOffsets[paragraph]
                        val offset = layout?.let {
                            val line = it.getLineForVerticalPosition(state.firstVisibleItemScrollOffset.toFloat())
                            it.getLineStart(line)
                        } ?: 0
                        ReadProgress(chapterIndex, paragraph, offset)
                    }.distinctUntilChanged().debounce(250).collect { latestPosition(it) }
                }
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().testTag("read-scroll"), state = state,
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacing.dp)) {
                        itemsIndexed(chapter.paragraphs) { index, text ->
                            val imageId = chapter.images[index]
                            if (imageId != null) ReadBookImage(book.images[imageId], Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 320.dp))
                            else Text(readHighlightedText(text, annotations.filter { it.chapter == chapterIndex && it.paragraph == index }),
                                style = bodyStyle, color = MaterialTheme.colorScheme.onSurface,
                                onTextLayout = { lineOffsets[index] = it },
                                modifier = Modifier.fillMaxWidth().testTag("read-paragraph-$index").clickable {
                                    onSelect(ReadParagraphSelection(book.id, chapterIndex, index, chapter.title, text))
                                })
                        }
                    }
                }
            } else {
                val count = if (settings.mode == "spread" && maxWidth >= 680.dp) 2 else 1
                SideEffect { columns = count }
                val pageWidth = ((maxWidth - if (count == 2) 16.dp else 0.dp) / count).coerceAtMost(560.dp)
                val width = with(density) { (pageWidth - 32.dp).roundToPx() }.coerceAtLeast(1)
                val height = with(density) { (maxHeight - 28.dp).roundToPx() }.coerceAtLeast(1)
                val gap = with(density) { settings.paragraphSpacing.dp.roundToPx() }
                val imageHeight = minOf(height, (width * .85f).roundToInt())
                LaunchedEffect(chapter, settings, bodyStyle, width, height, gap) {
                    layouts = null
                    latestViewport(null)
                    layouts = withContext(Dispatchers.Default) {
                        paginateReadChapter(chapter, measurer, bodyStyle, width, height, gap, imageHeight)
                    }
                }
                val ready = layouts
                val frame = ready?.takeIf { it.isNotEmpty() }?.let {
                    ReadTurnFrame(chapterIndex, chapter, it, spreadStart, count, pageWidth,
                        with(density) { imageHeight.toDp() }, bodyStyle, settings.paragraphSpacing, height)
                }
                ReadTurnStage(frame, settings.pageTurn, trackProgress, previousPage, nextPage,
                    onSettled = { settledFrame = it }) { rendered, interactive ->
                    Row(Modifier.fillMaxSize().testTag(if (rendered.columns == 2) "read-spread" else "read-single-page"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    repeat(rendered.columns) { column ->
                        val page = rendered.pages.getOrNull(rendered.start + column)
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.width(rendered.pageWidth).fillMaxHeight().testTag("read-paper-$column")) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)
                                .clipToBounds(), verticalArrangement = Arrangement.spacedBy(rendered.paragraphSpacing.dp)) {
                                page?.fragments?.forEach { fragment ->
                                    val source = rendered.chapter.paragraphs[fragment.paragraph]
                                    if (fragment.image) ReadBookImage(book.images[rendered.chapter.images[fragment.paragraph]],
                                        Modifier.fillMaxWidth().height(rendered.imageHeight))
                                    else {
                                        val end = source.substring(fragment.start, fragment.end).trimEnd('\n', '\r').length + fragment.start
                                        val decorated = readHighlightedText(source, annotations.filter {
                                            it.chapter == rendered.chapterIndex && it.paragraph == fragment.paragraph
                                        }).subSequence(fragment.start, end)
                                        Text(decorated, style = rendered.style, color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.fillMaxWidth().testTag("read-paragraph-${fragment.paragraph}").clickable(enabled = interactive) {
                                                onSelect(ReadParagraphSelection(book.id, rendered.chapterIndex, fragment.paragraph,
                                                    rendered.chapter.title, source, fragment.start, fragment.end))
                                            })
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
            if (settings.readingRuler) ReadRuler()
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = previousPage, enabled = trackProgress && (scrolling || pageSettled) &&
                (chapterIndex > 0 || !scrolling && spreadStart > 0)) { Text(if (scrolling) "上一章" else "上一页") }
            Text(if (scrolling) "${chapterIndex + 1} / ${book.chapters.size} 章" else if (pageTotal == 0) "排版中" else
                if (columns == 2) "${spreadStart + 1}–${minOf(spreadStart + 2, pageTotal)} / $pageTotal 页" else "${pageIndex + 1} / $pageTotal 页",
                fontFamily = GongduBook, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("read-page-number"))
            TextButton(onClick = nextPage, enabled = trackProgress && (if (scrolling) chapterIndex < book.chapters.lastIndex else
                pageSettled && pageTotal > 0 && (spreadStart + columns < pageTotal || chapterIndex < book.chapters.lastIndex))) {
                Text(if (scrolling) "下一章" else "下一页")
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.ReadRuler() {
    val density = LocalDensity.current
    val limit = with(density) { (maxHeight - 44.dp).roundToPx() }.coerceAtLeast(0).toFloat()
    var y by remember { mutableFloatStateOf(limit * .35f) }
    val step = with(density) { 24.dp.toPx() }
    Box(Modifier.offset { IntOffset(0, y.coerceIn(0f, limit).roundToInt()) }.fillMaxWidth().height(44.dp)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f))
        .draggable(rememberDraggableState { y = (y + it).coerceIn(0f, limit) }, Orientation.Vertical)
        .semantics {
            contentDescription = "阅读尺，可上下拖动"
            customActions = listOf(CustomAccessibilityAction("上移阅读尺") { y = (y - step).coerceIn(0f, limit); true },
                CustomAccessibilityAction("下移阅读尺") { y = (y + step).coerceIn(0f, limit); true })
        }.testTag("read-ruler"))
}
