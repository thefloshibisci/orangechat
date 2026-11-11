package me.rerere.rikkahub.ui.pages.gongdu

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.reading.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private class ReadInteractionClock(var lastInteractionAt: Long)

/** A local bookshelf and an explicitly shared reading session for the current conversation. */
@Composable
fun GongduReadRoom(conversationId: String, onNavigateBack: () -> Unit, onShare: (String) -> Unit) {
    key(conversationId) { ReadRoom(conversationId, onNavigateBack, onShare) }
}

@Composable
private fun ReadRoom(conversationId: String, onNavigateBack: () -> Unit, onShare: (String) -> Unit) {
    val repo = koinInject<ReadRepository>()
    val conversations = koinInject<ConversationRepository>()
    val library by repo.library.collectAsStateWithLifecycle()
    val currentBook by repo.currentBook.collectAsStateWithLifecycle()
    val progress by repo.progress.collectAsStateWithLifecycle()
    val annotations by repo.annotations.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val session by repo.session.collectAsStateWithLifecycle()
    val loadError by repo.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var viewingBookId by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    var assistantId by remember(conversationId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ReadBookEntry?>(null) }
    var selection by remember { mutableStateOf<ReadParagraphSelection?>(null) }
    var jumpSerial by remember { mutableIntStateOf(0) }
    var navigating by remember { mutableStateOf(false) }
    var progressJob by remember { mutableStateOf<Job?>(null) }
    val interaction = remember { ReadInteractionClock(SystemClock.elapsedRealtime()) }
    val book = currentBook?.takeIf { it.id == viewingBookId && session.conversationId == conversationId }
    val ownAnnotations = annotations.filter { it.conversationId == conversationId && it.assistantId == assistantId }

    fun launchAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { feedback = error.message ?: "操作未完成，请重试"; feedbackError = true }
            finally { busy = false }
        }
    }
    fun leaveBook() {
        progressJob?.cancel()
        book?.let { repo.clearViewport(it.id, conversationId) }
        repo.setActive(conversationId, false)
        viewingBookId = null
        panel = null
        selection = null
    }
    fun navigate(chapter: Int, paragraph: Int = 0, offset: Int = 0, resetPage: Boolean = true) {
        if (navigating) return
        val target = book ?: return
        navigating = true
        repo.clearViewport(target.id, conversationId)
        progressJob?.cancel()
        scope.launch {
            try {
                repo.updateProgress(target.id, chapter, paragraph, conversationId, offset = offset)
                if (resetPage) jumpSerial++
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { feedback = error.message ?: "未能定位到这一段"; feedbackError = true }
            finally { navigating = false }
        }
    }
    fun share(target: ReadBook, chapter: Int, paragraph: Int, quote: String, note: String = "") {
        try {
            feedback = null
            onShare(readShareText(target, chapter, paragraph, quote, note))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { feedback = error.message ?: "书摘未能带到聊天，请重试"; feedbackError = true }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) launchAction {
            val imported = repo.importBook(uri)
            feedback = "《${imported.title}》已放入书架"
            feedbackError = false
        }
    }
    LaunchedEffect(conversationId) {
        try { assistantId = conversations.getConversationById(Uuid.parse(conversationId))?.assistantId?.toString() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { feedback = error.message ?: "当前聊天未能载入，请返回后重试"; feedbackError = true }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, book?.id, conversationId, panel, selection, deleting) {
        fun updateActive() = repo.setActive(conversationId,
            book != null && panel == null && selection == null && deleting == null &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> updateActive() }
        lifecycle.addObserver(observer)
        updateActive()
        onDispose { lifecycle.removeObserver(observer); repo.setActive(conversationId, false) }
    }
    BackHandler(book != null) { leaveBook() }
    val foreground by rememberUpdatedState(book != null && panel == null && selection == null && deleting == null)
    LaunchedEffect(book?.id, conversationId, assistantId) {
        val target = book ?: return@LaunchedEffect
        val id = assistantId ?: return@LaunchedEffect
        interaction.lastInteractionAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(30_000)
            try { repo.recordReading(target.id, conversationId, id, 30,
                foreground && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), interaction.lastInteractionAt) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { feedback = error.message ?: "阅读记录未能保存"; feedbackError = true }
        }
    }

    ReadTheme(settings) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (book != null) leaveBook() else onNavigateBack() }) {
                        Icon(HugeIcons.ArrowLeft01, if (book != null) "返回书架" else "返回共度")
                    }
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(book?.title ?: "一起读", fontFamily = GongduBook, fontSize = 24.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (book == null) "TWO READERS · ONE BOOK" else "${progress.chapter + 1} / ${book.chapters.size} 章",
                            fontFamily = GongduBook, fontSize = 10.sp, letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { panel = "appearance" }) { Text("外观") }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) interaction.lastInteractionAt = SystemClock.elapsedRealtime()
                    }
                }
            }) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = .7.dp)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("read-loading"))
                val message = feedback ?: loadError
                if (message != null) {
                    Surface(color = if (feedback == null || feedbackError) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                            color = if (feedback == null || feedbackError) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                if (book == null) {
                    ReadShelf(library, busy, Modifier.weight(1f),
                        onImport = { importer.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
                        onOpen = { entry -> launchAction {
                            repo.openBook(entry.id, conversationId)
                            viewingBookId = entry.id
                            jumpSerial++
                        } }, onDelete = { deleting = it }, cover = { repo.coverImage(it) })
                    TextButton(onClick = { panel = "extras" }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("札记与备份")
                    }
                } else {
                    ReadConsent(
                        authorized = session.authorized && session.conversationId == conversationId && session.assistantId == assistantId,
                        enabled = assistantId != null,
                        onAuthorize = { enabled ->
                            try { repo.authorize(conversationId, checkNotNull(assistantId), enabled) }
                            catch (error: Exception) { feedback = error.message ?: "陪读授权未能更改"; feedbackError = true }
                        },
                    )
                    key(book.id, if (settings.mode == "scroll") progress.chapter else null, settings.mode, jumpSerial) {
                        val renderedPosition = progress
                        val renderedSettings = settings
                        ReadPage(book, progress, settings, ownAnnotations, Modifier.weight(1f),
                            onProgress = { chapter, paragraph ->
                                if (!navigating) {
                                    val expected = progress
                                    progressJob?.cancel()
                                    progressJob = scope.launch {
                                        try { repo.updateProgress(book.id, chapter, paragraph, conversationId, expectedProgress = expected) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Exception) { feedback = error.message ?: "阅读进度未能保存"; feedbackError = true }
                                    }
                                }
                            }, onSelect = { selection = it },
                            onPrevious = {
                                if (progress.chapter > 0) {
                                    val chapter = progress.chapter - 1
                                    val paragraph = if (settings.mode != "scroll") book.chapters[chapter].paragraphs.lastIndex else 0
                                    navigate(chapter, paragraph,
                                        if (settings.mode != "scroll") book.chapters[chapter].paragraphs[paragraph].length else 0,
                                        resetPage = settings.mode == "scroll")
                                }
                            }, onNext = {
                                if (progress.chapter < book.chapters.lastIndex) {
                                    navigate(progress.chapter + 1, resetPage = settings.mode == "scroll")
                                }
                            }, trackProgress = !navigating && session.active,
                            onPosition = { position ->
                                if (!navigating) {
                                    val expected = progress
                                    progressJob?.cancel()
                                    progressJob = scope.launch {
                                        try { repo.updateProgress(book.id, position.chapter, position.paragraph, conversationId,
                                            expectedProgress = expected, offset = position.offset) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Exception) { feedback = error.message ?: "阅读进度未能保存"; feedbackError = true }
                                    }
                                }
                            }, onNavigatePosition = { navigate(it.chapter, it.paragraph, it.offset, resetPage = false) },
                            onViewport = { viewport -> repo.updateViewport(book.id, conversationId, renderedPosition, viewport,
                                expectedSettings = renderedSettings) })
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { panel = "contents" }) { Text("目录") }
                        TextButton(onClick = { panel = "annotations" }) { Text("批注 ${ownAnnotations.size}") }
                        TextButton(enabled = book.chapters.getOrNull(progress.chapter)?.images?.containsKey(progress.paragraph) != true, onClick = {
                            book.chapters.getOrNull(progress.chapter)?.paragraphs?.getOrNull(progress.paragraph)?.let { text ->
                                share(book, progress.chapter, progress.paragraph, text)
                            }
                        }) { Text("聊这一段") }
                        TextButton(onClick = { panel = "extras" }) { Text("札记与备份") }
                    }
                }
            }
        }
        if (panel == "appearance") {
            ReadSheet(onDismiss = { panel = null }, error = feedback?.takeIf { feedbackError }) {
                ReadAppearance(settings, onChange = { updated -> scope.launch {
                    try {
                        book?.let { repo.clearViewport(it.id, conversationId) }
                        repo.updateSettings(updated)
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { feedback = error.message ?: "外观未能保存"; feedbackError = true }
                } })
            }
        }
        if (panel == "extras") ReadExtrasSheet(repo, conversationId, assistantId, onDismiss = { panel = null })
        if (panel == "contents" && book != null) {
            ReadSheet(onDismiss = { panel = null }, error = feedback?.takeIf { feedbackError }) {
                Text("目录", fontFamily = GongduBook, style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    itemsIndexed(book.chapters) { index, chapter ->
                        ListItem(headlineContent = { Text(chapter.title) }, leadingContent = {
                            Text((index + 1).toString().padStart(2, '0'), fontFamily = GongduBook)
                        }, trailingContent = { if (index == progress.chapter) Text("正在读") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { navigate(index); panel = null })
                    }
                }
            }
        }
        if (panel == "annotations" && book != null) {
            ReadSheet(onDismiss = { panel = null }, error = feedback?.takeIf { feedbackError }) {
                ReadAnnotationList(ownAnnotations, busy,
                    onLocate = { navigate(it.chapter, it.paragraph, it.start); panel = null },
                    onDelete = { annotation -> launchAction { repo.deleteAnnotation(annotation.id, conversationId) } },
                    onShare = { annotation -> share(book, annotation.chapter, annotation.paragraph,
                        annotation.quote, annotation.note) })
            }
        }
        selection?.let { selected ->
            ReadSheet(onDismiss = { if (!busy) selection = null }, error = feedback?.takeIf { feedbackError }) {
                ReadAnnotationEditor(selected, busy = busy, onSave = { start, end, note ->
                    val id = assistantId
                    if (id == null) { feedback = "当前助手尚未载入，请稍后再试"; feedbackError = true }
                    else launchAction {
                        repo.addAnnotation(selected.bookId, conversationId, id,
                            selected.chapter, selected.paragraph, start, end, note)
                        selection = null
                        feedback = "这一段的想法，留在书页里了"
                        feedbackError = false
                    }
                }, onShare = { quote, note ->
                    val selectedBook = currentBook?.takeIf { it.id == selected.bookId }
                    if (selectedBook != null) share(selectedBook, selected.chapter, selected.paragraph, quote, note)
                })
            }
        }
        deleting?.let { entry ->
            AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("移出这本书？") },
                text = { Text("《${entry.title}》的本地正文、进度和批注会一起删除，原始文件保留。") },
                confirmButton = { TextButton(enabled = !busy, onClick = { launchAction {
                    repo.deleteBook(entry.id)
                    if (viewingBookId == entry.id) leaveBook()
                    deleting = null
                } }) { Text("删除本地书籍") } },
                dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("保留") } })
        }
    }
}

@Composable
internal fun ReadTheme(settings: ReadSettings, content: @Composable () -> Unit) {
    val dark = settings.theme in setOf("dark", "bluecard-night")
    val blue = settings.theme in setOf("bluecard", "bluecard-night")
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val paper = settings.theme == "paper"
    val colors = base.copy(
        primary = if (dark) Color(0xFF93AADE) else Color(0xFF5A72B0),
        onPrimary = if (dark) Color(0xFF131828) else Color.White,
        primaryContainer = if (dark) Color(0xFF293654) else Color(0xFFE2EAF6),
        onPrimaryContainer = if (dark) Color(0xFFD6DEEE) else Color(0xFF39466B),
        secondary = if (dark) Color(0xFFBAC6E2) else Color(0xFF62769F),
        secondaryContainer = if (dark) Color(0xFF303C57) else Color(0xFFE9EDF5),
        onSecondaryContainer = if (dark) Color(0xFFD6DEEE) else Color(0xFF39466B),
        surface = if (dark) Color(0xFF131828) else if (blue) Color(0xFFF6F3EA) else if (paper) Color(0xFFF6F0E6) else Color(0xFFFEFDFC),
        onSurface = if (dark) Color(0xFFD6DEEE) else Color(0xFF39466B),
        onSurfaceVariant = if (dark) Color(0xFFACB8D1) else Color(0xFF697797),
        surfaceContainerLow = if (dark) Color(0xFF1C2336) else if (blue) Color(0xFFFDFCF6) else Color(0xFFFBF8F1),
        surfaceContainer = if (dark) Color(0xFF242C44) else Color(0xFFEDF1FA),
        surfaceContainerHigh = if (dark) Color(0xFF29324B) else Color(0xFFE8EDF7),
        surfaceContainerHighest = if (dark) Color(0xFF303B56) else Color(0xFFE2EAF6),
        surfaceVariant = if (dark) Color(0xFF242C44) else Color(0xFFEDF1FA),
        outline = if (dark) Color(0xFF7688C0) else Color(0xFF7E97C8),
        outlineVariant = if (dark) Color(0xFF333F60) else Color(0xFFC3CFE6),
    )
    val type = MaterialTheme.typography
    MaterialTheme(colorScheme = colors, typography = type.copy(
        bodyLarge = type.bodyLarge.copy(fontFamily = GongduSans),
        bodyMedium = type.bodyMedium.copy(fontFamily = GongduSans),
        bodySmall = type.bodySmall.copy(fontFamily = GongduSans),
        titleMedium = type.titleMedium.copy(fontFamily = GongduSans),
        labelLarge = type.labelLarge.copy(fontFamily = GongduSans),
    ), content = content)
}

@Composable
internal fun ReadShelf(
    books: List<ReadBookEntry>, busy: Boolean, modifier: Modifier = Modifier,
    onImport: () -> Unit, onOpen: (ReadBookEntry) -> Unit, onDelete: (ReadBookEntry) -> Unit,
    cover: suspend (String) -> ReadImage? = { null },
) {
    LazyColumn(modifier.fillMaxWidth().testTag("read-shelf"), contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("把书翻到，我们这一页", fontFamily = GongduBook, fontSize = 27.sp, lineHeight = 36.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("读到喜欢的句子，留一点想法给彼此。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Image(painterResource(R.drawable.gongdu_room_fish), null,
                    Modifier.padding(start = 12.dp).size(76.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary.copy(alpha = .7f)))
            }
        }
        item {
            Button(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (busy) "正在放入书架…" else "导入一本书")
            }
            Text("TXT · EPUB / 正文、进度与批注保存在手机", Modifier.padding(top = 9.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (books.isEmpty()) item {
            Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("书架还空着", fontFamily = GongduBook, fontSize = 23.sp)
                    Text("从手机里选一本 TXT 或无加密的 EPUB。导入后就能读，不需要连接阅读服务器。",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(books, key = { it.id }) { book ->
            val image by produceState<ReadImage?>(null, book.id) {
                try { value = cover(book.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { value = null }
            }
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            ReadBookImage(image, Modifier.size(62.dp, 88.dp), cover = true)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(book.format.uppercase(), fontFamily = GongduBook, letterSpacing = 2.sp,
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(book.title, fontFamily = GongduBook, fontSize = 23.sp, lineHeight = 30.sp)
                            if (book.author.isNotBlank()) Text(book.author, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("${book.chapterCount} 章 · 读到第 ${book.progress.chapter + 1} 章，第 ${book.progress.paragraph + 1} 段",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onOpen(book) }, enabled = !busy) { Text("继续读") }
                        TextButton(onClick = { onDelete(book) }, enabled = !busy) { Text("移出书架") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReadConsent(authorized: Boolean, enabled: Boolean, onAuthorize: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text("让当前助手陪我读", style = MaterialTheme.typography.bodyMedium)
                Text(if (authorized) "可读已到内容与批注" else "开启后分享书页与批注",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(authorized, onAuthorize, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "让当前助手陪我读" })
        }
    }
}

internal data class ReadParagraphSelection(
    val bookId: String, val chapter: Int, val paragraph: Int, val chapterTitle: String, val text: String,
    val start: Int = 0, val end: Int = text.length,
)

internal fun readHighlightedText(text: String, annotations: List<ReadAnnotation>): AnnotatedString = buildAnnotatedString {
    append(text)
    annotations.forEach { annotation ->
        if (annotation.start >= 0 && annotation.end <= text.length && annotation.start < annotation.end &&
            text.substring(annotation.start, annotation.end) == annotation.quote) {
            addStyle(SpanStyle(background = if (annotation.author == "human" && annotation.replies.isEmpty()) Color(0x55E2A7B1)
                else Color(0x556B9CAD)), annotation.start, annotation.end)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadSheet(onDismiss: () -> Unit, error: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("read-sheet-error"))
            content()
        }
    }
}

@Composable
internal fun ReadAnnotationEditor(
    selected: ReadParagraphSelection, busy: Boolean,
    onSave: (Int, Int, String) -> Unit, onShare: (String, String) -> Unit,
) {
    var quote by remember(selected) { mutableStateOf(TextFieldValue(selected.text, TextRange(selected.start, selected.end))) }
    var note by rememberSaveable(selected.bookId, selected.chapter, selected.paragraph) { mutableStateOf("") }
    val start = quote.selection.min
    val end = quote.selection.max
    val valid = start >= 0 && end <= selected.text.length && start < end
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("read-annotation-editor"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("在书页边，留一句话", fontFamily = GongduBook, style = MaterialTheme.typography.headlineSmall)
        Text(selected.chapterTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(quote, { quote = it.copy(text = selected.text) }, readOnly = true,
            label = { Text("选中的原文") }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth()
                .testTag("read-quote-selection"))
        Text("默认选中当前页的这一段，长按原文可调整选区。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(note, { note = it.readTake(4000) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("我的想法（可留空）") }, minLines = 3, maxLines = 7,
            supportingText = { Text("${note.length} / 4000") }, enabled = !busy)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(start, end, note) }, enabled = valid && !busy) { Text("保存划线与批注") }
            OutlinedButton(onClick = { onShare(selected.text.substring(start, end), note) }, enabled = valid && !busy) {
                Text("和助手聊这句")
            }
        }
    }
}

@Composable
internal fun ReadAnnotationList(
    annotations: List<ReadAnnotation>, busy: Boolean,
    onLocate: (ReadAnnotation) -> Unit, onDelete: (ReadAnnotation) -> Unit, onShare: (ReadAnnotation) -> Unit,
) {
    Text("我们的页边话", fontFamily = GongduBook, style = MaterialTheme.typography.headlineSmall)
    Text("这里只展示当前聊天留下的批注与回应。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("read-annotations"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (annotations.isEmpty()) item {
            Text("还没有批注。轻点正文的一段，选出想留下的句子。", Modifier.padding(vertical = 20.dp))
        }
        items(annotations, key = { it.id }) { annotation ->
            val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val companion = annotation.author == "assistant"
                Surface(color = if (companion) { if (dark) Color(0xFF263650) else Color(0xFFE2EAF6) }
                    else if (dark) Color(0xFF443041) else Color(0xFFF3E7EC), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${if (companion) "助手的批注" else "我的划线"} · 第 ${annotation.chapter + 1} 章", style = MaterialTheme.typography.labelMedium)
                        Text("“${annotation.quote}”", fontFamily = GongduBook, fontSize = 18.sp)
                        if (annotation.note.isNotBlank()) Text(annotation.note)
                    }
                }
                annotation.replies.forEach { reply ->
                    Surface(color = if (dark) Color(0xFF314952) else Color(0xFFDDECF0), shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(start = 20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("助手的回应", style = MaterialTheme.typography.labelMedium)
                            Text(reply.text)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onLocate(annotation) }) { Text("回到原文") }
                    TextButton(onClick = { onShare(annotation) }) { Text("聊这则批注") }
                    TextButton(enabled = !busy, onClick = { onDelete(annotation) }) { Text("删除批注") }
                }
            }
        }
    }
}

@Composable
internal fun ReadAppearance(settings: ReadSettings, onChange: (ReadSettings) -> Unit) {
    var fontSize by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize.toFloat()) }
    var lineHeight by remember(settings.lineHeight) { mutableFloatStateOf(settings.lineHeight) }
    var letterSpacing by remember(settings.letterSpacing) { mutableFloatStateOf(settings.letterSpacing) }
    var paragraphSpacing by remember(settings.paragraphSpacing) { mutableFloatStateOf(settings.paragraphSpacing.toFloat()) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("read-appearance"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("适合这一刻的书页", fontFamily = GongduBook, style = MaterialTheme.typography.headlineSmall)
        Text("阅读底色", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("bluecard" to "蓝笺·日间", "bluecard-night" to "蓝笺·夜间", "paper" to "纸页", "white" to "素白").forEach { (value, label) ->
                FilterChip(settings.theme == value, onClick = { onChange(settings.copy(theme = value)) }, label = { Text(label) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("正文字号 · ${fontSize.toInt()}", Modifier.weight(1f))
            TextButton(onClick = {
                fontSize = 20f
                onChange(settings.copy(fontSize = 20))
            }, modifier = Modifier.testTag("read-font-default")) { Text("默认 20") }
        }
        Slider(fontSize, { fontSize = it }, valueRange = 16f..30f,
            onValueChangeFinished = { onChange(settings.copy(fontSize = fontSize.toInt())) },
            steps = 13, modifier = Modifier.semantics { contentDescription = "阅读字号" })
        ReadTurnOptions(settings, onChange)
        Text("行距 · ${"%.1f".format(lineHeight)}")
        Slider(lineHeight, { lineHeight = it }, valueRange = 1.4f..2.4f,
            onValueChangeFinished = { onChange(settings.copy(lineHeight = lineHeight)) },
            steps = 9, modifier = Modifier.semantics { contentDescription = "阅读行距" })
        Text("字距 · ${"%.1f".format(letterSpacing)}")
        Slider(letterSpacing, { letterSpacing = it }, valueRange = 0f..3f,
            onValueChangeFinished = { onChange(settings.copy(letterSpacing = letterSpacing)) },
            steps = 5, modifier = Modifier.semantics { contentDescription = "阅读字距" })
        Text("段距 · ${paragraphSpacing.toInt()}")
        Slider(paragraphSpacing, { paragraphSpacing = it }, valueRange = 8f..36f,
            onValueChangeFinished = { onChange(settings.copy(paragraphSpacing = paragraphSpacing.toInt())) },
            steps = 13, modifier = Modifier.semantics { contentDescription = "阅读段距" })
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
            Text("读到这里，慢一点也没关系。", Modifier.padding(16.dp), fontFamily = GongduBook,
                fontSize = fontSize.sp, lineHeight = (fontSize * lineHeight).sp, letterSpacing = letterSpacing.sp)
        }
        Text("阅读方式", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(settings.mode == "scroll", onClick = { onChange(settings.copy(mode = "scroll")) }, label = { Text("上下滚动") })
            FilterChip(settings.mode == "page", onClick = { onChange(settings.copy(mode = "page")) }, label = { Text("单页翻读") })
            FilterChip(settings.mode == "spread", onClick = { onChange(settings.copy(mode = "spread")) }, label = { Text("双页对读") })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("阅读尺", Modifier.weight(1f))
            Switch(settings.readingRuler, { onChange(settings.copy(readingRuler = it)) },
                modifier = Modifier.semantics { contentDescription = "显示阅读尺" })
        }
        Text("按屏幕与字号实际分页；窄屏双页自动收为单页。阅读尺可上下拖动。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
