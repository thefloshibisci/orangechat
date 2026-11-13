package me.rerere.rikkahub.ui.pages.gongdu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Next
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Previous
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.RepeatOne01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Shuffle
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.data.listen.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ui.QRCode
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val PAGE_PLAYER = "player"
private const val PAGE_FIND = "find"
private const val PAGE_QUEUE = "queue"
private const val PAGE_NOTES = "notes"
private const val PAGE_CONNECT = "connect"
private const val LYRIC_SHARE_LIMIT = 8

/** Native listening room. Playback lives in the Media3 service when this page is closed. */
@Composable
fun GongduListenRoom(conversationId: String, onNavigateBack: () -> Unit, onShare: (String) -> Unit) {
    val repo = koinInject<ListenRepository>()
    val conversations = koinInject<ConversationRepository>()
    val config by repo.config.collectAsStateWithLifecycle()
    val playback by repo.state.collectAsStateWithLifecycle()
    val state = if (playback.conversationId.isBlank() || playback.conversationId == conversationId) playback else ListenState()
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(if (config.configured) PAGE_PLAYER else PAGE_CONNECT) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    val actions = remember(scope) { ListenRoomActions(scope,
        onStart = { message = null; messageIsError = false },
        onFailure = { message = it.message ?: "操作未完成，请稍后重试"; messageIsError = true }) }
    val busy = actions.busy
    var assistantId by remember(conversationId) { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ListenSong>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<ListenPlaylist>>(emptyList()) }
    var resultTitle by remember { mutableStateOf("挑一首，听听看") }
    var searched by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var noteSong by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf(ListenNotes()) }
    var account by remember { mutableStateOf<ListenAccount?>(null) }
    var login by remember { mutableStateOf<ListenLogin?>(null) }
    var loginMessage by remember { mutableStateOf("") }
    var lyricsSelection by remember { mutableStateOf<Pair<ListenSong, List<ListenLyric>>?>(null) }
    var notesLoading by remember { mutableStateOf(false) }
    var notesAttempt by remember { mutableIntStateOf(0) }

    fun action(block: suspend () -> Unit) = actions.action(block)
    fun transport(block: suspend () -> Unit) = actions.transport(block)
    fun notify(text: String) { message = text; messageIsError = false }

    BackHandler(enabled = page != PAGE_PLAYER) { page = PAGE_PLAYER }
    LaunchedEffect(conversationId) {
        assistantId = runCatching { conversations.getConversationById(Uuid.parse(conversationId))?.assistantId?.toString() }.getOrNull()
    }
    LaunchedEffect(config.baseUrl, config.token) {
        noteSong = null
        notes = ListenNotes()
        results = emptyList()
        playlists = emptyList()
        searched = false
        account = null
        login = null
        lyricsSelection = null
    }
    LaunchedEffect(page, state.current?.song?.id, config.baseUrl, config.token, notesAttempt) {
        val song = state.current?.song
        if (page == PAGE_NOTES && song != null && config.configured && noteSong != song.id) {
            noteSong = null
            notes = ListenNotes()
            notesLoading = true
            if (messageIsError) message = null
            try {
                notes = repo.notes(song.id)
                noteSong = song.id
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "手记读取失败"; messageIsError = true }
            finally { notesLoading = false }
        }
    }
    // Feedback is a passing remark, not a banner that stays until the room closes.
    LaunchedEffect(message) {
        if (message != null) {
            delay(if (messageIsError) 5000 else 2600)
            message = null
        }
    }
    // The code is confirmed inside NetEase; nobody should have to come back and press "check".
    LaunchedEffect(login?.key, page) {
        val key = login?.key ?: return@LaunchedEffect
        if (page != PAGE_CONNECT) return@LaunchedEffect
        while (true) {
            delay(2500)
            val status = try { repo.loginStatus(key) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
            loginMessage = listenLoginMessage(status)
            if (status == "authorized") {
                account = runCatching { repo.account() }.getOrNull() ?: account
                login = null
                notify("网易云账号已登录")
                break
            }
            if (status == "expired") break
        }
    }

    ListenTheme {
        val colors = MaterialTheme.colorScheme
        Surface(color = colors.surface, contentColor = colors.onSurface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.surfaceContainerLow, colors.surface)))
                .systemBarsPadding().imePadding()) {
                Column(Modifier.fillMaxSize()) {
                    if (page == PAGE_PLAYER) {
                        ListenTopBar("一起听", if (state.current?.origin == "assistant") "a song for you" else "side by side",
                            "返回共度", onNavigateBack, book = true) {
                            ListenIcon(HugeIcons.Search01, "找歌") { page = if (config.configured) PAGE_FIND else PAGE_CONNECT }
                            ListenIcon(HugeIcons.Settings03, "连接与账号") { page = PAGE_CONNECT }
                        }
                    } else {
                        val title = when (page) { PAGE_FIND -> "找歌"; PAGE_QUEUE -> "待播"; PAGE_NOTES -> "手记"; else -> "连接" }
                        ListenTopBar(title, null, "返回播放页", { page = PAGE_PLAYER })
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (page) {
                            PAGE_PLAYER -> ListenPlayer(state, Modifier.fillMaxSize(),
                                onFind = { page = if (config.configured) PAGE_FIND else PAGE_CONNECT },
                                onToggle = { transport { repo.toggle() } }, onNext = { transport { repo.next() } },
                                onPrevious = { transport { repo.previous() } }, onSeek = { transport { repo.seekTo(it) } },
                                onShare = { state.current?.song?.let { onShare(listenShareText(it)) } },
                                onLyrics = { state.current?.song?.let { lyricsSelection = it to state.lyrics.toList() } },
                                onNotes = { page = PAGE_NOTES }, onMode = { transport { repo.setMode(it) } },
                                onQueue = { page = PAGE_QUEUE })
                            PAGE_FIND -> ListenDiscovery(query, { query = it }, busy, searched, resultTitle, results, playlists,
                                onSearch = { action { results = repo.search(query.trim()); playlists = emptyList(); resultTitle = "搜索结果"; searched = true } },
                                onDaily = { action { results = repo.daily(); playlists = emptyList(); resultTitle = "每日推荐"; searched = true } },
                                onRadar = { action { results = repo.radar(); playlists = emptyList(); resultTitle = "私人雷达"; searched = true } },
                                onPlaylists = { action { playlists = repo.playlists(); results = emptyList(); resultTitle = "我的歌单"; searched = true } },
                                onPlaylist = { list -> action { results = repo.playlist(list.id); resultTitle = list.title } },
                                onBackToPlaylists = { results = emptyList(); resultTitle = "我的歌单" },
                                onPlay = { song -> action { repo.bindConversation(conversationId); repo.play(song); page = PAGE_PLAYER } },
                                onEnqueue = { song -> action { repo.bindConversation(conversationId); repo.enqueue(song, next = true); notify("已排在下一首") } },
                                onShare = { onShare(listenShareText(it)) }, onConnect = { page = PAGE_CONNECT }, configured = config.configured)
                            PAGE_QUEUE -> ListenQueue(state, busy, onPlay = { id -> action { repo.playQueueItem(id); page = PAGE_PLAYER } },
                                onRemove = { transport { repo.remove(it) } }, onMode = { transport { repo.setMode(it) } },
                                onFind = { page = if (config.configured) PAGE_FIND else PAGE_CONNECT })
                            PAGE_NOTES -> ListenNotesEditor(state.current?.song, notes, noteSong == state.current?.song?.id,
                                loading = notesLoading, busy = busy, onRetry = { notesAttempt++ },
                                onChange = { notes = it }, onSave = { state.current?.song?.let { song -> action {
                                    repo.saveNotes(song.id, notes); notify("这一首的心情，记下了")
                                } } }, onShare = { state.current?.song?.let { song -> onShare(listenShareText(song,
                                    message = listOf("我的听歌手记", notes.notes, notes.feeling).filter(String::isNotBlank).joinToString("\n"))) } })
                            else -> ListenConnection(config, conversationId, assistantId, busy, account, login, loginMessage,
                                onSave = { url, token -> action { repo.saveConfiguration(url, token); account = repo.account(); login = null; notify("音乐服务已连接") } },
                                onAuthorize = { enabled -> assistantId?.let {
                                    // Consent must remain revocable while an unrelated network operation is running.
                                    try { repo.authorize(conversationId, it, enabled) }
                                    catch (error: Exception) { message = error.message ?: "陪听授权未能更改"; messageIsError = true }
                                } },
                                onAccount = { action { account = repo.account() } },
                                onLogin = { action { login = repo.loginStart(); loginMessage = "用网易云音乐扫一扫，或截图后从相册识别" } },
                                onCheck = { action { login?.let {
                                    loginMessage = listenLoginMessage(repo.loginStatus(it.key))
                                    account = repo.account()
                                    if (account?.loggedIn == true) login = null
                                } } },
                                onLogout = { action { repo.logout(); account = repo.account(); login = null } })
                        }
                    }
                }
                // Overlays: neither the progress line nor a remark may push the page around.
                if (busy || state.loading || notesLoading) LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter).testTag("listen-loading"),
                    trackColor = Color.Transparent)
                message?.let { text ->
                    Surface(color = if (messageIsError) colors.errorContainer else colors.primaryContainer,
                        contentColor = if (messageIsError) colors.onErrorContainer else colors.onPrimaryContainer,
                        shape = RoundedCornerShape(18.dp), shadowElevation = 3.dp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp, start = 24.dp, end = 24.dp)
                            .clip(RoundedCornerShape(18.dp)).clickable(onClickLabel = "关闭提示") { message = null }) {
                        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
        lyricsSelection?.let { (song, lyrics) ->
            ListenLyricsPicker(lyrics, onDismiss = { lyricsSelection = null }) { lines ->
                onShare(listenShareText(song, lines))
                lyricsSelection = null
            }
        }
    }
}

internal fun listenLoginMessage(status: String): String = when (status) {
    "waiting" -> "等待扫码"
    "scanned" -> "已扫码，请在网易云音乐中确认"
    "authorized" -> "登录成功"
    "expired" -> "二维码已过期，请重新生成"
    else -> "暂时无法确认登录状态，请重试"
}

@Composable
private fun ListenTopBar(
    title: String, subtitle: String?, backLabel: String, onBack: () -> Unit, book: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(HugeIcons.ArrowLeft01, backLabel) }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontFamily = if (book) GongduBook else GongduSans, fontSize = 18.sp, fontWeight = FontWeight.Normal,
                letterSpacing = if (book) 1.sp else 0.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontFamily = GongduBook, fontSize = 10.sp, letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Row(Modifier.align(Alignment.CenterEnd)) { actions() }
    }
}

/** Slow service requests serialize; playback controls stay usable while those requests are pending. */
internal class ListenRoomActions(
    private val scope: CoroutineScope,
    private val onStart: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    var busy by mutableStateOf(false)
        private set

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        onStart()
        scope.launch {
            try { execute(block) }
            finally { busy = false }
        }
    }

    fun transport(block: suspend () -> Unit) {
        scope.launch { execute(block) }
    }

    private suspend fun execute(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onFailure(error) }
    }
}

@Composable
internal fun ListenTheme(content: @Composable () -> Unit) {
    val dark = LocalDarkMode.current
    val base = MaterialTheme.colorScheme
    val colors = base.copy(
        primary = if (dark) Color(0xFFD3BBD7) else Color(0xFF785A7D),
        onPrimary = if (dark) Color(0xFF35263C) else Color.White,
        primaryContainer = if (dark) Color(0xFF4B3A52) else Color(0xFFE8DCEB),
        onPrimaryContainer = if (dark) Color(0xFFF1E5F5) else Color(0xFF503658),
        surface = if (dark) Color(0xFF211D27) else Color(0xFFF5F0F4),
        surfaceContainer = if (dark) Color(0xFF302A38) else Color(0xFFEDE4ED),
        surfaceContainerLow = if (dark) Color(0xFF292430) else Color(0xFFFCF9FC),
        onSurface = if (dark) Color(0xFFEDE4EF) else Color(0xFF3F3644),
        onSurfaceVariant = if (dark) Color(0xFFC4B6C8) else Color(0xFF716476),
        outline = if (dark) Color(0xFF9F8AA8) else Color(0xFF89768E),
        outlineVariant = if (dark) Color(0xFF524458) else Color(0xFFDBCDDF),
    )
    val t = MaterialTheme.typography
    MaterialTheme(colorScheme = colors, typography = t.copy(
        bodyLarge = t.bodyLarge.copy(fontFamily = GongduSans), bodyMedium = t.bodyMedium.copy(fontFamily = GongduSans),
        bodySmall = t.bodySmall.copy(fontFamily = GongduSans), titleLarge = t.titleLarge.copy(fontFamily = GongduSans),
        titleMedium = t.titleMedium.copy(fontFamily = GongduSans), labelLarge = t.labelLarge.copy(fontFamily = GongduSans),
        labelMedium = t.labelMedium.copy(fontFamily = GongduSans), labelSmall = t.labelSmall.copy(fontFamily = GongduSans),
    ), content = content)
}

/** Record and title on top, lyrics breathing in the middle, the transport always under the thumb. */
@Composable
internal fun ListenPlayer(
    state: ListenState, modifier: Modifier = Modifier,
    onFind: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Long) -> Unit, onShare: () -> Unit, onLyrics: () -> Unit, onNotes: () -> Unit,
    onMode: (String) -> Unit = {}, onQueue: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val item = state.current
    if (item == null) {
        ListenEmpty("把一首歌，分成两份", "从你喜欢的歌开始，留一句话给正在陪你听的人。", "挑一首歌", onFind, modifier)
        return
    }
    BoxWithConstraints(modifier.testTag("listen-player")) {
        val record = if (maxHeight < 560.dp) 112.dp else 168.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            ListenRecord(item.song.cover, Modifier.size(record), spinning = state.playing)
            Spacer(Modifier.height(18.dp))
            Text(item.song.title, fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(state.error ?: item.song.artist, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = if (state.error != null) colors.error else colors.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (item.origin == "assistant" || item.message.isNotBlank()) {
                Surface(shape = RoundedCornerShape(14.dp), color = colors.primaryContainer, modifier = Modifier.padding(top = 10.dp)) {
                    Text(item.message.ifBlank { "这首是助手放给你的" }, Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        color = colors.onPrimaryContainer, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            ListenLyrics(item.id, state.lyrics, state.currentLyricIndex, onSeek, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onShare) { Text("这首给你") }
                TextButton(onClick = onLyrics, enabled = state.lyrics.isNotEmpty()) { Text("选几句词") }
                TextButton(onClick = onNotes) { Text("写手记") }
            }
            ListenProgress(state.positionMs, state.durationMs, onSeek)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                val (modeIcon, modeLabel, nextMode) = when (state.mode) {
                    "shuffle" -> Triple(HugeIcons.Shuffle, "随机播放", "repeat")
                    "repeat" -> Triple(HugeIcons.RepeatOne01, "单曲循环", "order")
                    else -> Triple(HugeIcons.Repeat, "顺序播放", "shuffle")
                }
                ListenIcon(modeIcon, "播放模式：$modeLabel") { onMode(nextMode) }
                ListenIcon(HugeIcons.Previous, "上一首", size = 24.dp, onClick = onPrevious)
                val active = state.playing || state.loading
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(64.dp)) {
                    Icon(if (active) HugeIcons.Pause else HugeIcons.Play, if (active) "暂停" else "播放", Modifier.size(26.dp))
                }
                ListenIcon(HugeIcons.Next, "下一首", size = 24.dp, onClick = onNext)
                ListenIcon(HugeIcons.LeftToRightListBullet, "待播列表", onClick = onQueue)
            }
        }
    }
}

/** The sung line rests at the centre; a drag hands control to the reader for a few seconds. */
@Composable
private fun ListenLyrics(itemId: String, lyrics: List<ListenLyric>, current: Int, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (lyrics.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("这首暂时没有歌词", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        return
    }
    val scroll = rememberLazyListState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var follow by remember(itemId) { mutableStateOf(true) }
    LaunchedEffect(dragged) {
        if (dragged) follow = false
        else if (!follow) { delay(3000); follow = true }
    }
    LaunchedEffect(itemId, current, follow) {
        if (follow && current >= 0) scroll.animateScrollToItem(current)
    }
    BoxWithConstraints(modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, .2f to Color.Black, .8f to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn)
        }) {
        // Half a viewport of padding puts the item that scrolling targets on the centre line.
        val inset = (maxHeight / 2 - 22.dp).coerceAtLeast(0.dp)
        LazyColumn(Modifier.fillMaxSize().testTag("listen-lyrics"), state = scroll, contentPadding = PaddingValues(vertical = inset)) {
            itemsIndexed(lyrics, key = { index, lyric -> "${lyric.timeMs}-$index" }) { index, lyric ->
                val distance = if (current < 0) 2 else kotlin.math.abs(index - current)
                val tone = when (distance) { 0 -> 1f; 1 -> .78f; 2 -> .6f; else -> .45f }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "从这一句开始听", role = Role.Button) { follow = true; onSeek(lyric.timeMs) }
                    .padding(horizontal = 8.dp, vertical = 9.dp)) {
                    Text(lyric.text, color = if (distance == 0) colors.onSurface else colors.onSurfaceVariant.copy(alpha = tone),
                        fontSize = if (distance == 0) 18.sp else 15.sp, lineHeight = 26.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                    if (lyric.translation.isNotBlank()) Text(lyric.translation, color = colors.onSurfaceVariant.copy(alpha = tone),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
internal fun ListenRecord(cover: String, modifier: Modifier = Modifier, spinning: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = withInfiniteAnimationFrameNanos { it }
        while (true) withInfiniteAnimationFrameNanos { now ->
            angle = (angle + (now - last) / 1_000_000_000f * 14f) % 360f
            last = now
        }
    }
    Box(modifier.graphicsLayer { rotationZ = angle }.clip(CircleShape)
        .background(Brush.linearGradient(listOf(Color(0xFF49404C), Color(0xFF211C25))))
        .border(1.dp, colors.outline.copy(alpha = .3f), CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 1..12) drawCircle(Color.White.copy(alpha = .045f), radius = size.minDimension * (.25f + i * .018f), style = Stroke(1.dp.toPx()))
            drawLine(Color.White.copy(alpha = .08f), Offset(size.width * .15f, 0f), Offset(size.width * .85f, size.height), 30.dp.toPx())
        }
        if (cover.isNotBlank()) AsyncImage(cover, "专辑封面", Modifier.fillMaxSize(.56f).clip(CircleShape), contentScale = ContentScale.Crop)
        else Icon(HugeIcons.MusicNote03, null, Modifier.fillMaxSize(.2f), tint = Color(0xFFD6BFD9))
        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.surface).border(1.dp, colors.outline, CircleShape))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListenProgress(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val end = durationMs.coerceAtLeast(1).toFloat()
    val value = scrubbing ?: positionMs.toFloat().coerceIn(0f, end)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(listenTime(value.toLong()), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Slider(value = value, onValueChange = { scrubbing = it },
            onValueChangeFinished = { scrubbing?.let { onSeek(it.toLong()) }; scrubbing = null },
            valueRange = 0f..end, enabled = durationMs > 0,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).semantics { contentDescription = "播放进度" },
            thumb = { Box(Modifier.size(14.dp).background(colors.primary, CircleShape)) },
            track = {
                Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    val radius = CornerRadius(size.height / 2)
                    val thumb = 7.dp.toPx()
                    drawRoundRect(colors.outlineVariant, size = size, cornerRadius = radius)
                    drawRoundRect(colors.primary, cornerRadius = radius,
                        size = Size(thumb + (size.width - thumb * 2).coerceAtLeast(0f) * (value / end), size.height))
                }
            })
        Text(listenTime(durationMs), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

internal fun listenTime(ms: Long): String = (ms.coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }

@Composable
private fun ListenDiscovery(
    query: String, onQuery: (String) -> Unit, busy: Boolean, searched: Boolean, title: String,
    songs: List<ListenSong>, playlists: List<ListenPlaylist>, onSearch: () -> Unit, onDaily: () -> Unit,
    onRadar: () -> Unit, onPlaylists: () -> Unit, onPlaylist: (ListenPlaylist) -> Unit, onBackToPlaylists: () -> Unit,
    onPlay: (ListenSong) -> Unit, onEnqueue: (ListenSong) -> Unit, onShare: (ListenSong) -> Unit,
    onConnect: () -> Unit, configured: Boolean,
) {
    if (!configured) { ListenEmpty("连上你的小小音乐房间", "填好音乐服务地址，就能搜索、点歌和一起听。", "去连接", onConnect); return }
    val focus = LocalFocusManager.current
    // A playlist or a recommendation may carry the same song twice; lazy keys must stay unique.
    val uniqueSongs = remember(songs) { songs.distinctBy { it.id } }
    val uniqueLists = remember(playlists) { playlists.distinctBy { it.id } }
    val search: () -> Unit = { if (query.isNotBlank() && !busy) { focus.clearFocus(); onSearch() } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            placeholder = { Text("歌名、歌手或专辑") }, singleLine = true, shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
            trailingIcon = { IconButton(onClick = search, enabled = query.isNotBlank() && !busy) { Icon(HugeIcons.Search01, "搜索歌曲") } })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("每日推荐" to onDaily, "私人雷达" to onRadar, "我的歌单" to onPlaylists).forEach { (label, load) ->
                FilterChip(selected = title == label, onClick = { focus.clearFocus(); load() }, enabled = !busy, label = { Text(label) })
            }
        }
        val insidePlaylist = uniqueSongs.isNotEmpty() && uniqueLists.isNotEmpty()
        Row(Modifier.fillMaxWidth().padding(start = if (insidePlaylist) 8.dp else 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (insidePlaylist) ListenIcon(HugeIcons.ArrowLeft01, "返回我的歌单", onClick = onBackToPlaylists)
            Text(title, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (uniqueSongs.isEmpty() && uniqueLists.isEmpty() && !busy) {
            Text(if (searched) "这里还没有歌曲，换个关键词或推荐试试。" else "搜一首喜欢的歌，或让今日推荐带路。",
                Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
            if (uniqueSongs.isEmpty()) items(uniqueLists, key = { "list-${it.id}" }) { list ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = !busy) { onPlaylist(list) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ListenArtwork(list.cover, Modifier.size(54.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(list.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${list.count} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp))
                }
            }
            items(uniqueSongs, key = { "song-${it.id}" }) { song -> ListenSongRow(song, enabled = !busy, onPlay = { onPlay(song) }, trailing = {
                ListenIcon(HugeIcons.Message01, "分享 ${song.title}") { onShare(song) }
                ListenIcon(HugeIcons.Add01, "下一首播放 ${song.title}", enabled = !busy) { onEnqueue(song) }
            }) }
        }
    }
}

@Composable
internal fun ListenSongRow(
    song: ListenSong, subtitle: String = song.artist, enabled: Boolean = true, highlighted: Boolean = false,
    onPlay: () -> Unit, trailing: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onPlay).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ListenArtwork(song.cover, Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = if (highlighted) colors.onPrimaryContainer else colors.onSurface)
                Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = if (highlighted) colors.onPrimaryContainer else colors.onSurfaceVariant)
            }
        }
        trailing()
    }
}

@Composable
internal fun ListenArtwork(cover: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (cover.isNotBlank()) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(HugeIcons.MusicNote03, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ListenQueue(state: ListenState, busy: Boolean, onPlay: (String) -> Unit, onRemove: (String) -> Unit, onMode: (String) -> Unit, onFind: () -> Unit) {
    if (state.queue.isEmpty()) { ListenEmpty("下一首，留给谁来选", "你和助手排进来的歌，会一起留在这里。", "去找歌", onFind); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("order" to "顺序播放", "shuffle" to "随机播放", "repeat" to "单曲循环").forEach { (mode, label) ->
                    FilterChip(selected = state.mode == mode, onClick = { onMode(mode) }, label = { Text(label) })
                }
            }
        }
        itemsIndexed(state.queue, key = { _, item -> item.id }) { index, item ->
            val current = index == state.currentIndex
            val origin = if (item.origin == "assistant") "助手为你选的" else item.song.artist
            Surface(color = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                ListenSongRow(item.song, listOf(origin, item.message).filter(String::isNotBlank).joinToString(" · "),
                    enabled = !busy, highlighted = current, onPlay = { onPlay(item.id) }) {
                    ListenIcon(HugeIcons.Cancel01, "移除 ${item.song.title}") { onRemove(item.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListenNotesEditor(
    song: ListenSong?, notes: ListenNotes, loaded: Boolean, onChange: (ListenNotes) -> Unit,
    onSave: () -> Unit, onShare: () -> Unit, loading: Boolean = false, busy: Boolean = false, onRetry: () -> Unit = {},
) {
    if (song == null) { ListenEmpty("每一首，都可以有回声", "开始听歌后，在这里记下喜欢的句子和此刻的心情。", null, {}); return }
    // Preserve trailing separators while typing; normalizing the displayed value eats newlines/commas.
    var favoriteLinesText by remember(song.id, loaded) { mutableStateOf(notes.favoriteLines.joinToString("\n")) }
    var tagsText by remember(song.id, loaded) { mutableStateOf(notes.tags.joinToString("，")) }
    val invalidLines = notes.favoriteLines.size > 20 || notes.favoriteLines.any { it.length > 300 }
    val invalidTags = notes.tags.size > 20 || notes.tags.any { it.length > 40 }
    val valid = notes.notes.length <= 4000 && notes.feeling.length <= 1000 && !invalidLines && !invalidTags
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${song.title} · ${song.artist}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!loaded) {
            Text(if (loading) "正在读取这一首的手记" else "手记尚未读取成功，可以重试。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!loading) OutlinedButton(onClick = onRetry, enabled = !busy) { Text("重试读取手记") }
        }
        OutlinedTextField(notes.feeling, { onChange(notes.copy(feeling = it)) }, Modifier.fillMaxWidth(), label = { Text("听到这里的心情") },
            supportingText = { Text("${notes.feeling.length} / 1000") }, isError = notes.feeling.length > 1000, enabled = loaded)
        OutlinedTextField(notes.notes, { onChange(notes.copy(notes = it)) }, Modifier.fillMaxWidth().heightIn(min = 170.dp), label = { Text("想留下的话") },
            supportingText = { Text("${notes.notes.length} / 4000") }, isError = notes.notes.length > 4000, enabled = loaded)
        OutlinedTextField(favoriteLinesText, {
            favoriteLinesText = it
            onChange(notes.copy(favoriteLines = it.lines().filter(String::isNotBlank)))
        }, Modifier.fillMaxWidth().testTag("listen-note-lines"), label = { Text("喜欢的句子，每行一句") },
            supportingText = { Text("最多 20 句，每句 300 字") }, isError = invalidLines, minLines = 2, enabled = loaded)
        OutlinedTextField(tagsText, {
            tagsText = it
            onChange(notes.copy(tags = it.split('，', ',').map(String::trim).filter(String::isNotBlank)))
        }, Modifier.fillMaxWidth().testTag("listen-note-tags"), label = { Text("标签，用逗号分开") },
            supportingText = { Text("最多 20 个，每个 40 字") }, isError = invalidTags, enabled = loaded)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = loaded && valid && !busy) { Text("保存手记") }
            OutlinedButton(onClick = onShare, enabled = loaded && valid && !busy) { Text("带回聊天") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListenConnection(
    config: ListenConfig, conversationId: String, assistantId: String?, busy: Boolean,
    account: ListenAccount?, login: ListenLogin?, loginMessage: String,
    onSave: (String, String) -> Unit, onAuthorize: (Boolean) -> Unit, onAccount: () -> Unit,
    onLogin: () -> Unit, onCheck: () -> Unit, onLogout: () -> Unit,
) {
    var url by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var token by remember(config.token) { mutableStateOf(config.token) }
    var showToken by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("连接你的音乐服务，再用网易云音乐账号扫码。", color = colors.onSurfaceVariant)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("音乐服务地址") },
            placeholder = { Text("https://music.example.com") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("连接令牌") },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            trailingIcon = { IconButton(onClick = { showToken = !showToken }) {
                Icon(if (showToken) HugeIcons.ViewOff else HugeIcons.View, if (showToken) "隐藏令牌" else "显示令牌")
            } })
        Button(onClick = { onSave(url, token) }, enabled = !busy && url.isNotBlank() && token.isNotBlank()) { Text("保存并连接") }
        HorizontalDivider()
        val canAuthorize = config.configured && assistantId != null
        val authorized = config.conversationId == conversationId && config.assistantId == assistantId && assistantId != null
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .toggleable(authorized, enabled = canAuthorize, role = Role.Switch, onValueChange = onAuthorize)
            .testTag("listen-companion").padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("让当前助手陪我听", style = MaterialTheme.typography.titleMedium)
                Text(when {
                    !config.configured -> "先保存并连接音乐服务，才能开启。"
                    assistantId == null -> "当前聊天不可用，请从聊天中重新打开一起听。"
                    else -> "分享正在播放的歌和歌词，允许助手点歌与排队。"
                }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(checked = authorized, onCheckedChange = null, enabled = canAuthorize)
        }
        HorizontalDivider()
        Text(account?.let { if (it.loggedIn) "已登录 · ${it.nickname}" else "网易云账号尚未登录" } ?: "网易云音乐", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onAccount, enabled = config.configured && !busy) { Text("检查登录") }
            if (account?.loggedIn == true) TextButton(onClick = { confirmLogout = true }, enabled = !busy) { Text("退出账号") }
            else Button(onClick = onLogin, enabled = config.configured && !busy) { Text(if (login == null) "扫码登录" else "换一张二维码") }
        }
        if (login != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                    QRCode(login.qrUrl, Modifier.padding(16.dp).size(200.dp), color = Color.Black, backgroundColor = Color.White)
                }
            }
            Text(loginMessage, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Text("确认后这里会自动更新。", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onCheck, enabled = !busy) { Text("我已扫码，立即检查") }
            }
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出网易云账号？") },
        text = { Text("会停止播放并清空待播列表，助手的陪听授权也会关闭。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("退出账号") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

@Composable
private fun ListenLyricsPicker(lines: List<ListenLyric>, onDismiss: () -> Unit, onShare: (List<String>) -> Unit) {
    var selected by remember(lines) { mutableStateOf<Set<Int>>(emptySet()) }
    val full = selected.size >= LYRIC_SHARE_LIMIT
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选几句，递给对方") },
        text = {
            Column {
                Text("已选 ${selected.size} / $LYRIC_SHARE_LIMIT", style = MaterialTheme.typography.bodySmall,
                    color = if (full) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    itemsIndexed(lines) { index, line ->
                        val checked = index in selected
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .toggleable(checked, enabled = checked || !full, role = Role.Checkbox) {
                                selected = if (it) selected + index else selected - index
                            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = checked || !full)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(line.text)
                                if (line.translation.isNotBlank()) Text(line.translation, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onShare(selected.sorted().map { lines[it].text }) }, enabled = selected.isNotEmpty()) { Text("分享到当前聊天") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ListenEmpty(title: String, description: String, button: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ListenRecord("", Modifier.size(132.dp))
            Text(title, textAlign = TextAlign.Center, fontSize = 22.sp, lineHeight = 32.sp)
            Text(description, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, lineHeight = 25.sp)
            if (button != null) OutlinedButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
internal fun ListenIcon(icon: ImageVector, label: String, enabled: Boolean = true, size: Dp = 20.dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(icon, label, Modifier.size(size)) }
}
