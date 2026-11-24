package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
    onToggleControls: () -> Unit = {},
    showPageButtons: Boolean = true,
    onBackToShelf: () -> Unit = {},
    paperTopInset: androidx.compose.ui.unit.Dp = 0.dp,
    paperBottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val chapterIndex = progress.chapter.coerceIn(0, book.chapters.lastIndex)
    val chapter = book.chapters[chapterIndex]
    val paragraphIndex = progress.paragraph.coerceIn(0, chapter.paragraphs.lastIndex)
    val scrolling = settings.mode == "scroll"
    val bodyStyle = TextStyle(fontFamily = GongduSans, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None, fontSize = settings.fontSize.sp,
        lineHeight = (settings.fontSize * settings.lineHeight).sp, letterSpacing = settings.letterSpacing.sp,
        textIndent = TextIndent(firstLine = (settings.fontSize * 2).sp),
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
    val previousEnabled = trackProgress && (scrolling || pageSettled) && (chapterIndex > 0 || !scrolling && spreadStart > 0)
    val nextEnabled = trackProgress && (if (scrolling) chapterIndex < book.chapters.lastIndex else
        pageSettled && pageTotal > 0 && (spreadStart + columns < pageTotal || chapterIndex < book.chapters.lastIndex))
    val tap: (Float) -> Unit = { fraction ->
        if (trackProgress) {
            if (!scrolling && settings.tapTurn && fraction < .30f) { if (previousEnabled) previousPage() }
            else if (!scrolling && settings.tapTurn && fraction > .70f) { if (nextEnabled) nextPage() }
            else onToggleControls()
        }
    }
    val latestTap by rememberUpdatedState(tap)
    val chrome = !showPageButtons
    val chromeScale = density.fontScale.coerceAtLeast(1f)
    val headingHeight = 48.dp * chromeScale
    val footerHeight = 24.dp * chromeScale
    val paperTop = if (chrome) 8.dp else 14.dp
    val paperBottom = if (chrome) 4.dp else 14.dp
    Column(modifier.fillMaxWidth().testTag("read-page")) {
        if (chrome && scrolling) ReadPageHeading(chapter.title, trackProgress, onBackToShelf)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (chrome) 0.dp else 4.dp)
            .testTag("read-body").clipToBounds().pointerInput(Unit) {
                detectTapGestures(onTap = { latestTap(it.x / size.width) })
            }) {
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
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().testTag("read-scroll"), state = state,
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacing.dp)) {
                        itemsIndexed(chapter.paragraphs) { index, text ->
                            val imageId = chapter.images[index]
                            if (imageId != null) ReadBookImage(book.images[imageId], Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 320.dp))
                            else ReadParagraphText(text, annotations.filter { it.chapter == chapterIndex && it.paragraph == index },
                                0, text.length, bodyStyle, Modifier.fillMaxWidth().testTag("read-paragraph-$index"),
                                onLayout = { lineOffsets[index] = it }, onTap = { latestTap(it) }, onSelect = {
                                    onSelect(ReadParagraphSelection(book.id, chapterIndex, index, chapter.title, text))
                                })

                        }
                    }
                }
            } else {
                val count = if (settings.mode == "spread" && maxWidth >= 680.dp) 2 else 1
                SideEffect { columns = count }
                val pageWidth = ((maxWidth - if (count == 2) 16.dp else 0.dp) / count).coerceAtMost(560.dp)
                val width = with(density) { (pageWidth - if (chrome) 40.dp else 32.dp).roundToPx() }.coerceAtLeast(1)
                val height = with(density) {
                    maxHeight.roundToPx() - paperTop.roundToPx() - paperBottom.roundToPx() -
                        if (chrome) paperTopInset.roundToPx() + paperBottomInset.roundToPx() +
                            headingHeight.roundToPx() + footerHeight.roundToPx() else 0
                }.coerceAtLeast(1)
                val gap = with(density) { settings.paragraphSpacing.dp.roundToPx() }
                val imageHeight = minOf(height, (width * .85f).roundToInt())
                val adjacentLayouts = remember(book.id, settings, width, height, count) {
                    mutableStateMapOf<Int, List<ReadLayoutPage>>()
                }
                LaunchedEffect(chapter, settings, bodyStyle, width, height, gap) {
                    layouts = null
                    latestViewport(null)
                    layouts = adjacentLayouts[chapterIndex] ?: withContext(Dispatchers.Default) {
                        paginateReadChapter(chapter, measurer, bodyStyle, width, height, gap, imageHeight)
                    }
                    adjacentLayouts[chapterIndex] = checkNotNull(layouts)
                    adjacentLayouts.keys.filter { kotlin.math.abs(it - chapterIndex) > 1 }.forEach { adjacentLayouts.remove(it) }
                }
                val ready = layouts
                val frame = ready?.takeIf { it.isNotEmpty() }?.let {
                    ReadTurnFrame(chapterIndex, chapter, it, spreadStart, count, pageWidth,
                        with(density) { imageHeight.toDp() }, bodyStyle, settings.paragraphSpacing, height)
                }
                LaunchedEffect(frame, settings.pageTurn) {
                    if (frame == null || settings.pageTurn != "curl") return@LaunchedEffect
                    val targets = buildList {
                        if (spreadStart == 0 && chapterIndex > 0) add(chapterIndex - 1)
                        if (spreadStart + count >= frame.pages.size && chapterIndex < book.chapters.lastIndex) add(chapterIndex + 1)
                    }
                    targets.filterNot { adjacentLayouts.containsKey(it) }.forEach { index ->
                        adjacentLayouts[index] = withContext(Dispatchers.Default) {
                            paginateReadChapter(book.chapters[index], measurer, bodyStyle, width, height, gap, imageHeight)
                        }
                    }
                }
                ReadTurnStage(frame, settings.pageTurn, trackProgress, previousPage, nextPage,
                    onSettled = {
                        settledFrame = it
                        // Revoke the readable range in the gesture callback itself. Waiting for
                        // recomposition leaves a stale viewport during the first dragged frame.
                        if (it == null) latestViewport(null)
                    }, neighbor = { direction ->
                        frame?.let { source ->
                            val target = source.start + direction * source.columns
                            if (target in source.pages.indices) source.copy(start = target)
                            else {
                                val index = chapterIndex + direction
                                adjacentLayouts[index]?.takeIf { it.isNotEmpty() }?.let { pages ->
                                    source.copy(chapterIndex = index, chapter = book.chapters[index], pages = pages,
                                        start = if (direction > 0) 0 else pages.lastIndex / count * count)
                                }
                            }
                        }
                    }) { rendered, interactive ->
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    if (chrome) {
                        Spacer(Modifier.height(paperTopInset))
                        ReadPageHeading(rendered.chapter.title, interactive, onBackToShelf)
                    }
                    Row(Modifier.weight(1f).fillMaxWidth().testTag(if (rendered.columns == 2) "read-spread" else "read-single-page"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    repeat(rendered.columns) { column ->
                        val page = rendered.pages.getOrNull(rendered.start + column)
                        Surface(color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.width(rendered.pageWidth).fillMaxHeight().testTag("read-paper-$column")) {
                            Column(Modifier.fillMaxSize().padding(start = if (chrome) 20.dp else 16.dp,
                                end = if (chrome) 20.dp else 16.dp, top = paperTop, bottom = paperBottom)
                                .clipToBounds(), verticalArrangement = Arrangement.spacedBy(rendered.paragraphSpacing.dp)) {
                                page?.fragments?.forEach { fragment ->
                                    val source = rendered.chapter.paragraphs[fragment.paragraph]
                                    if (fragment.image) ReadBookImage(book.images[rendered.chapter.images[fragment.paragraph]],
                                        Modifier.fillMaxWidth().height(rendered.imageHeight))
                                    else {
                                        val end = source.substring(fragment.start, fragment.end).trimEnd('\n', '\r').length + fragment.start
                                        ReadParagraphText(source, annotations.filter {
                                            it.chapter == rendered.chapterIndex && it.paragraph == fragment.paragraph
                                        }, fragment.start, end, rendered.style,
                                            Modifier.fillMaxWidth().testTag("read-paragraph-${fragment.paragraph}"),
                                            enabled = interactive, onTap = { latestTap(it) }, onSelect = {
                                                onSelect(ReadParagraphSelection(book.id, rendered.chapterIndex, fragment.paragraph,
                                                    rendered.chapter.title, source, fragment.start, fragment.end))
                                            })

                                    }
                                }
                            }
                        }
                    }
                    }
                    if (chrome) ReadPaperFooter(
                        if (rendered.columns == 2) "${rendered.start + 1}–${minOf(rendered.start + 2, rendered.pages.size)} / ${rendered.pages.size} 页"
                        else "${rendered.start + 1} / ${rendered.pages.size} 页",
                        interactive, previousEnabled, nextEnabled, previousPage, nextPage, onToggleControls)
                    if (chrome) Spacer(Modifier.height(paperBottomInset))
                }
                }
            }
            if (settings.readingRuler) ReadRuler()
        }
        if (showPageButtons || scrolling) Box(Modifier.fillMaxWidth().height((if (showPageButtons) 68.dp else 32.dp) * density.fontScale.coerceAtLeast(1f))) {
            if (!showPageButtons) {
                var time by remember { mutableStateOf(java.time.LocalTime.now()) }
                LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); time = java.time.LocalTime.now() } }
                Text(time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp))
            }
            Text(if (scrolling) "${chapterIndex + 1} / ${book.chapters.size} 章" else if (pageTotal == 0) "排版中" else
                if (columns == 2) "${spreadStart + 1}–${minOf(spreadStart + 2, pageTotal)} / $pageTotal 页" else "${pageIndex + 1} / $pageTotal 页",
                fontFamily = GongduSans, fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(if (showPageButtons) Alignment.BottomCenter else Alignment.CenterEnd)
                    .padding(horizontal = 20.dp, vertical = 3.dp).testTag("read-page-number")
                    .semantics {
                        customActions = buildList {
                            add(CustomAccessibilityAction("阅读工具") { onToggleControls(); true })
                            if (previousEnabled) add(CustomAccessibilityAction(if (scrolling) "上一章" else "上一页") { previousPage(); true })
                            if (nextEnabled) add(CustomAccessibilityAction(if (scrolling) "下一章" else "下一页") { nextPage(); true })
                        }
                    })
            // Small arrows give keyboard and switch users a direct alternative to gestures.
            if (showPageButtons) {
            IconButton(onClick = previousPage, enabled = previousEnabled,
                modifier = Modifier.align(Alignment.TopStart)) {
                Icon(HugeIcons.ArrowLeft01, if (scrolling) "上一章" else "上一页", Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
            }
            IconButton(onClick = nextPage, enabled = nextEnabled,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 48.dp)) {
                Icon(HugeIcons.ArrowRight01, if (scrolling) "下一章" else "下一页", Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
            }
            }
        }
    }
}

@Composable
private fun ReadPageHeading(title: String, enabled: Boolean, onBack: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth().height(48.dp * fontScale).padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart) {
        Text("‹  $title", fontFamily = GongduSans, fontSize = 12.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize().wrapContentHeight()
                .clickable(enabled = enabled, onClick = onBack)
                .semantics { contentDescription = "返回书架" })
    }
}

@Composable
private fun ReadPaperFooter(label: String, interactive: Boolean, previousEnabled: Boolean, nextEnabled: Boolean,
    previous: () -> Unit, next: () -> Unit, tools: () -> Unit) {
    var time by remember { mutableStateOf(java.time.LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); time = java.time.LocalTime.now() } }
    Row(Modifier.fillMaxWidth().height(24.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
        .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(label, fontFamily = GongduSans, fontSize = 10.sp, lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("read-page-number").semantics {
                if (interactive) customActions = buildList {
                    add(CustomAccessibilityAction("阅读工具") { tools(); true })
                    if (previousEnabled) add(CustomAccessibilityAction("上一页") { previous(); true })
                    if (nextEnabled) add(CustomAccessibilityAction("下一页") { next(); true })
                }
            })
    }
}

@Composable
private fun ReadParagraphText(
    source: String, annotations: List<ReadAnnotation>, start: Int, end: Int, style: TextStyle,
    modifier: Modifier, enabled: Boolean = true, onLayout: (TextLayoutResult) -> Unit = {},
    onTap: (Float) -> Unit, onSelect: () -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val valid = annotations.filter { it.start >= 0 && it.end <= source.length && it.start < it.end &&
        source.substring(it.start, it.end) == it.quote && it.end > start && it.start < end }
    val tap by rememberUpdatedState(onTap)
    val select by rememberUpdatedState(onSelect)
    Text(source.substring(start, end), style = if (start == 0) style else style.copy(textIndent = TextIndent.None),
        color = LocalReadPalette.current.body,
        onTextLayout = { layout = it; onLayout(it) },
        modifier = modifier.drawBehind {
            val measured = layout ?: return@drawBehind
            valid.forEach { note ->
                val from = maxOf(note.start, start) - start
                val to = minOf(note.end, end) - start
                val tint = if (note.author == "human" && note.replies.isEmpty()) Color(0xFFE0AFC0) else Color(0xFFB6AFD7)
                if (from < to) {
                    val firstLine = measured.getLineForOffset(from)
                    val lastLine = measured.getLineForOffset(to - 1)
                    for (line in firstLine..lastLine) {
                        val a = maxOf(from, measured.getLineStart(line))
                        val b = minOf(to, measured.getLineEnd(line, visibleEnd = true))
                        if (a < b) {
                            val first = measured.getBoundingBox(a)
                            val last = measured.getBoundingBox(b - 1)
                            val top = first.top + first.height * .57f
                            drawRoundRect(tint.copy(alpha = .45f), Offset(minOf(first.left, last.left), top),
                                androidx.compose.ui.geometry.Size(kotlin.math.abs(last.right - first.left).coerceAtLeast(2f),
                                    first.height * .34f), CornerRadius(3.dp.toPx()))
                            if (line == firstLine && note.start >= start)
                                drawCircle(tint, 2.5.dp.toPx(), Offset(first.left, top))
                        }
                    }
                }
            }
        }.pointerInput(enabled) {
            if (enabled) detectTapGestures(onTap = { tap(it.x / size.width) }, onLongPress = { select() })
        }.semantics {
            if (enabled) onLongClick("留下页边话") { select(); true }
        })
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
