package me.rerere.rikkahub.ui.pages.gongdu

import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.materials.HazeMaterials
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import androidx.compose.ui.graphics.Brush
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.getKoin
import me.rerere.rikkahub.data.reading.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private class ReadInteractionClock(var lastInteractionAt: Long)

/** A local bookshelf and an explicitly shared reading session for the current conversation. */
@Composable
fun GongduReadRoom(conversationId: String, onNavigateBack: () -> Unit, onShare: (String) -> Unit, initialBookId: String? = null) {
    key(conversationId) { ReadRoom(conversationId, onNavigateBack, onShare, initialBookId) }
}

@Composable
private fun ReadRoom(conversationId: String, onNavigateBack: () -> Unit, onShare: (String) -> Unit, initialBookId: String?) {
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
    val koin = getKoin()
    val settingsStore = remember(koin) { koin.getOrNull<SettingsStore>() }
    val appSettings = settingsStore?.settingsFlow?.collectAsStateWithLifecycle()?.value
    var managing by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var viewingBookId by rememberSaveable(conversationId) { mutableStateOf(initialBookId) }
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
    var readingWrite by remember { mutableStateOf<Job?>(null) }
    var displayedViewport by remember { mutableStateOf<ReadViewport?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val interaction = remember { ReadInteractionClock(SystemClock.elapsedRealtime()) }
    val book = currentBook?.takeIf { it.id == viewingBookId && it.id == session.bookId && session.conversationId == conversationId }
    val assistantName = appSettings?.assistants?.firstOrNull { it.id.toString() == assistantId }?.name?.ifBlank { "助手" } ?: "助手"
    val authorized = session.authorized && session.conversationId == conversationId && session.assistantId == assistantId
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
        repo.setActive(conversationId, false, assistantId, interaction.lastInteractionAt)?.let { readingWrite = it }
        viewingBookId = null
        displayedViewport = null
        panel = null
        selection = null
    }
    fun navigate(chapter: Int, paragraph: Int = 0, offset: Int = 0, resetPage: Boolean = true) {
        if (navigating) return
        val target = book ?: return
        navigating = true
        displayedViewport = null
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
    fun share(card: () -> String) {
        try {
            feedback = null
            onShare(card())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { feedback = error.message ?: "书摘未能带到聊天，请重试"; feedbackError = true }
    }
    fun share(target: ReadBook, chapter: Int, paragraph: Int, quote: String, note: String = "") =
        share { readShareText(target, chapter, paragraph, quote, note) }
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
    val readingAssistant by rememberUpdatedState(assistantId)
    DisposableEffect(lifecycle, book?.id, conversationId, panel, selection, deleting) {
        fun setReadingActive(active: Boolean) {
            if (active && !repo.session.value.active) interaction.lastInteractionAt = SystemClock.elapsedRealtime()
            repo.setActive(conversationId, active, readingAssistant, interaction.lastInteractionAt)
                ?.let { readingWrite = it }
        }
        fun updateActive() = setReadingActive(book != null && panel == null && selection == null && deleting == null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> updateActive() }
        lifecycle.addObserver(observer)
        updateActive()
        onDispose { lifecycle.removeObserver(observer); setReadingActive(false) }
    }
    LaunchedEffect(panel, book?.id) {
        if (panel == "contents") drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue }.collect { value ->
            if (value == DrawerValue.Closed && panel == "contents") panel = null
        }
    }
    BackHandler(book != null || panel == "shelf-menu") {
        when { panel != null -> panel = null; controlsVisible -> controlsVisible = false; else -> leaveBook() }
    }
    val foreground by rememberUpdatedState(book != null && panel == null && selection == null && deleting == null)
    LaunchedEffect(book?.id, conversationId, assistantId) {
        val target = book ?: return@LaunchedEffect
        val id = assistantId ?: return@LaunchedEffect
        while (true) {
            delay(30_000)
            try { repo.recordReading(target.id, conversationId, id, 30,
                foreground && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), interaction.lastInteractionAt) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { feedback = error.message ?: "阅读记录未能保存"; feedbackError = true }
        }
    }

    ReadTheme(if (book == null && settings.theme !in setOf("dark", "bluecard-night")) settings.copy(theme = "shelf") else settings) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            val lightPaper = MaterialTheme.colorScheme.surface.luminance() > 0.5f
            SideEffect {
                @Suppress("DEPRECATION")
                dialogWindow.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                dialogWindow.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= 29) {
                    dialogWindow.isNavigationBarContrastEnforced = false
                    dialogWindow.isStatusBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView).apply {
                    isAppearanceLightStatusBars = lightPaper
                    isAppearanceLightNavigationBars = lightPaper
                }
            }
        }
        ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                if (book != null) ReadContents(book, progress.chapter,
                    onSelect = { navigate(it); panel = null }, onClose = { panel = null })
                else ModalDrawerSheet(Modifier.width(280.dp)) { Spacer(Modifier.height(1.dp)) }
            }) {
        val shelfHaze = rememberHazeState()
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (book == null) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReadRoundButton(HugeIcons.ArrowLeft01, "返回共度", onNavigateBack)
                    Text("共读室", Modifier.weight(1f), fontSize = 20.sp, fontFamily = GongduSans)
                    ReadRoundButton(HugeIcons.Add01, "书架菜单", {
                        panel = if (panel == "shelf-menu") null else "shelf-menu"
                    })
                }
            },
        ) { padding ->
            val paged = book != null && settings.mode != "scroll"
            Box(Modifier.fillMaxSize().padding(padding)
                .then(when {
                    !paged -> Modifier.navigationBarsPadding().displayCutoutPadding()
                        .then(if (book != null) Modifier.statusBarsPadding() else Modifier)
                    else -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                })
                .background(MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxSize()
                .then(if (panel == "shelf-menu" && Build.VERSION.SDK_INT >= 31) Modifier.hazeSource(shelfHaze) else Modifier)
                .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (repo.session.value.active && event.changes.any { it.pressed }) {
                            interaction.lastInteractionAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
            }) {
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
                            controlsVisible = false
                            jumpSerial++
                        } }, onDelete = { deleting = it }, cover = { repo.coverImage(it) }, managing = managing)
                } else {
                    key(book.id, if (settings.mode == "scroll") progress.chapter else null, settings.mode, jumpSerial) {
                        val renderedPosition = progress
                        val renderedSettings = settings
                        ReadPage(book, progress, settings, ownAnnotations, Modifier.weight(1f),
                            paperTopInset = if (settings.mode == "scroll") 0.dp else
                                WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding(),
                            paperBottomInset = if (paged) WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() else 0.dp,
                            onBackToShelf = { leaveBook() },
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
                            onViewport = { viewport ->
                                if (viewport != null || panel == null && repo.session.value.active) displayedViewport = viewport
                                repo.updateViewport(book.id, conversationId, renderedPosition, viewport,
                                expectedSettings = renderedSettings) }, onToggleControls = { controlsVisible = !controlsVisible },
                            showPageButtons = false)
                    }
                }
            }
            if (book != null && controlsVisible) {
                ReadReaderControls(book, progress.chapter, settings.theme in setOf("dark", "bluecard-night"),
                    enabled = !busy && !navigating, onChapter = { navigate(it) },
                    onContents = { panel = "contents" }, onNight = { scope.launch {
                        try { repo.updateSettings(settings.copy(theme =
                            if (settings.theme in setOf("dark", "bluecard-night")) "paper" else "dark")) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { feedback = error.message; feedbackError = true }
                    } }, onSettings = { panel = "appearance" }, onAnnotations = { panel = "annotations" },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .then(if (paged) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier))
            }
            if (panel == "shelf-menu") {
                Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { panel = null }
                    .testTag("read-menu-scrim"))
                val glass = RoundedCornerShape(17.dp)
                val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
                val tint = if (dark) Color(0xFF302E2D) else Color(0xFFFFFCF7)
                Box(Modifier.align(Alignment.TopEnd).padding(end = 18.dp).width(188.dp)
                    .dropShadow(glass, Shadow(10.dp, Color.Black.copy(alpha = .15f), offset = DpOffset(1.dp, 4.dp)))
                    .clip(glass)
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.hazeEffect(shelfHaze,
                        style = HazeMaterials.ultraThin(containerColor = tint)) else Modifier)
                    .background(Brush.linearGradient(listOf(tint.copy(alpha = .90f), tint.copy(alpha = .72f))))
                    .border(1.dp, Color.White.copy(alpha = if (dark) .24f else .88f), glass).testTag("read-menu")) {
                    Column(Modifier.padding(vertical = 3.dp)) {
                        ReadShelfMenuRow(HugeIcons.Clock02, "阅读统计") { panel = "stats" }
                        ReadShelfMenuRow(HugeIcons.Menu03, if (managing) "完成管理" else "管理书籍") {
                            managing = !managing; panel = null
                        }
                        ReadShelfMenuRow(HugeIcons.Download01, "备份与恢复") { panel = "extras" }
                    }
                }
            }
            }
        }
        }

        if (panel == "appearance") {
            ReadSheet(onDismiss = { panel = null }, error = feedback?.takeIf { feedbackError }) {
                ReadAppearance(settings, sample = book?.chapters?.getOrNull(progress.chapter)?.let { chapter ->
                    chapter.paragraphs.getOrNull(progress.paragraph)?.takeIf { progress.paragraph !in chapter.images && it.isNotBlank() }
                        ?.drop(progress.offset).orEmpty().ifBlank { chapter.title }.take(100)
                }.orEmpty().ifBlank { "暮色落在窗边，书页上还留着一线光。" }, onDismiss = { panel = null }, onChange = { updated -> scope.launch {
                    try {
                        displayedViewport = null
                        book?.let { repo.clearViewport(it.id, conversationId) }
                        repo.updateSettings(updated)
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { feedback = error.message ?: "外观未能保存"; feedbackError = true }
                } }, extraContent = {
                    if (book != null) {
                        HorizontalDivider()
                        ReadConsent(authorized, assistantId != null) { enabled ->
                            try { repo.authorize(conversationId, checkNotNull(assistantId), enabled) }
                            catch (error: Exception) { feedback = error.message; feedbackError = true }
                        }
                        val scrolling = settings.mode == "scroll"
                        ReadMenuRow(HugeIcons.Message01, if (scrolling) "聊当前段" else "聊这一页",
                            enabled = if (scrolling) progress.paragraph !in book.chapters[progress.chapter].images
                            else displayedViewport != null) {
                            if (scrolling) share(book, progress.chapter, progress.paragraph,
                                book.chapters[progress.chapter].paragraphs[progress.paragraph])
                            else displayedViewport?.let { viewport -> share { readPageShareText(book, progress, viewport) } }
                        }
                        ReadMenuRow(HugeIcons.Menu03, "札记与导出") { panel = "extras" }
                        ReadMenuRow(HugeIcons.Clock02, "本书统计") { panel = "stats" }
                    }
                })
            }
        }
        if (panel == "stats") ReadSheet(onDismiss = { panel = null }) {
            ReadStatistics(repo, book?.id, conversationId, assistantId, assistantName, onDismiss = { panel = null },
                readingWrite = readingWrite, readingPosition = book?.let {
                    "第 ${progress.chapter + 1} / ${it.chapters.size} 章 · ${it.chapters[progress.chapter].title}"
                })
        }
        if (panel == "extras") ReadExtrasSheet(repo, conversationId, assistantId, onDismiss = { panel = null }, libraryOnly = book == null)
        if (panel == "annotations" && book != null) {
            ReadSheet(onDismiss = { panel = null }, error = feedback?.takeIf { feedbackError }, annotations = true) {
                ReadAnnotationList(ownAnnotations, busy,
                    onLocate = { navigate(it.chapter, it.paragraph, it.start); panel = null },
                    onDelete = { annotation -> launchAction { repo.deleteAnnotation(annotation.id, conversationId) } },
                    onShare = { annotation -> share(book, annotation.chapter, annotation.paragraph,
                        annotation.quote, annotation.note) },
                    assistantName = assistantName, book = book, onDismiss = { panel = null },
                    listModifier = Modifier.weight(1f, fill = false))
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
internal fun ReadConsent(authorized: Boolean, enabled: Boolean, onAuthorize: (Boolean) -> Unit) {
    ReadSettingSwitchRow("让当前助手陪我读", "让当前助手陪我读", authorized,
        supportingText = if (authorized) "可读已到内容与批注" else "开启后分享书页与批注",
        enabled = enabled, onChange = onAuthorize)
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
private fun ReadSheet(onDismiss: () -> Unit, error: String? = null, annotations: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    val maximumHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .90f
    ModalBottomSheet(onDismissRequest = onDismiss,
        modifier = if (annotations) Modifier.testTag("read-annotation-sheet") else Modifier,
        containerColor = if (annotations && MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFFFFFCF4)
            else MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().then(if (annotations) Modifier.heightIn(max = maximumHeight) else Modifier)
            .imePadding().padding(horizontal = if (annotations) 20.dp else 22.dp)
            .padding(bottom = if (annotations) 8.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (annotations) 0.dp else 16.dp)) {
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
        Text("在书页边，留一句话", fontFamily = GongduSans, style = MaterialTheme.typography.headlineSmall)
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
    assistantName: String = "助手", book: ReadBook? = null, onDismiss: () -> Unit = {},
    listModifier: Modifier = Modifier.heightIn(max = 640.dp),
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (book != null) ReadBookImage(book.coverImageId?.let { book.images[it] }, Modifier.size(28.dp, 40.dp), cover = true)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("全部批注", fontSize = 18.sp)
            Text("${annotations.size} 条", Modifier.align(Alignment.CenterVertically), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, "关闭批注", Modifier.size(21.dp)) }
    }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    LazyColumn(Modifier.fillMaxWidth().then(listModifier).testTag("read-annotations"),
        contentPadding = PaddingValues(bottom = 12.dp)) {
        if (annotations.isEmpty()) item {
            Text("还没有批注。长按正文的一段，选出想留下的句子。", Modifier.padding(vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(annotations, key = { it.id }) { annotation ->
            var expanded by rememberSaveable(annotation.id) { mutableStateOf(false) }
            var menu by remember { mutableStateOf(false) }
            val author = if (annotation.author == "assistant") assistantName else "我"
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Text(author.take(1), fontSize = 14.sp) }
                    }
                    Text("$author · 批注", Modifier.weight(1f).padding(start = 8.dp), fontSize = 14.sp)
                    Text(java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(annotation.createdAt)),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(HugeIcons.MoreVertical, "批注操作", Modifier.size(18.dp))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("回到原文") }, onClick = { menu = false; onLocate(annotation) })
                            DropdownMenuItem(text = { Text("聊这则批注") }, onClick = { menu = false; onShare(annotation) })
                            DropdownMenuItem(text = { Text("删除批注") }, enabled = !busy,
                                onClick = { menu = false; onDelete(annotation) })
                        }
                    }
                }
                Text(annotation.quote, fontSize = 14.sp, lineHeight = 22.sp, maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onLocate(annotation) })
                if (annotation.note.isNotBlank()) Text(annotation.note, fontSize = 16.sp, lineHeight = 26.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded })
                annotation.replies.forEach { reply ->
                    Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$assistantName · 回应", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(reply.text, fontSize = 16.sp, lineHeight = 26.sp)
                    }
                }
            }
            if (annotation != annotations.last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
        }
    }
}
