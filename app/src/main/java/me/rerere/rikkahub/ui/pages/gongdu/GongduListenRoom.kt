package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.data.listen.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ui.QRCode
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** Native listening room. Playback lives in the Media3 service when this page is closed. */
@Composable
fun GongduListenRoom(conversationId: String, onNavigateBack: () -> Unit, onShare: (String) -> Unit) {
    val repo = koinInject<ListenRepository>()
    val conversations = koinInject<ConversationRepository>()
    val config by repo.config.collectAsStateWithLifecycle()
    val playback by repo.state.collectAsStateWithLifecycle()
    val state = if (playback.conversationId.isBlank() || playback.conversationId == conversationId) playback else ListenState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(if (config.configured) 0 else 4) }
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
    LaunchedEffect(tab, state.current?.song?.id, config.baseUrl, config.token, notesAttempt) {
        val song = state.current?.song
        if (tab == 3 && song != null && config.configured && noteSong != song.id) {
            noteSong = null
            notes = ListenNotes()
            notesLoading = true
            try {
                notes = repo.notes(song.id)
                noteSong = song.id
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "手记读取失败"; messageIsError = true }
            finally { notesLoading = false }
        }
    }

    ListenTheme {
        val colors = MaterialTheme.colorScheme
        Scaffold(
            containerColor = colors.surface,
            topBar = {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onNavigateBack) { Icon(HugeIcons.ArrowLeft01, "返回共度") }
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text("一起听", fontSize = 24.sp, fontWeight = FontWeight.Normal)
                            Text("SIDE BY SIDE · LISTEN", fontFamily = GongduBook, fontSize = 10.sp,
                                letterSpacing = 1.6.sp, color = colors.onSurfaceVariant)
                        }
                        Text("III", fontFamily = GongduBook, fontSize = 24.sp, color = colors.primary)
                    }
                    ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp, containerColor = Color.Transparent,
                        contentColor = colors.primary, divider = {}) {
                        listOf("听歌", "找歌", "待播", "手记", "连接").forEachIndexed { index, label ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
                if (busy || state.loading || notesLoading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("listen-loading"))
                val feedback = message ?: state.error
                if (feedback != null) {
                    val error = if (message != null) messageIsError else true
                    Surface(color = if (error) colors.errorContainer else colors.primaryContainer,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(12.dp)) {
                        Text(feedback, Modifier.padding(12.dp), color = if (error) colors.onErrorContainer else colors.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                when (tab) {
                    0 -> ListenPlayer(state, Modifier.weight(1f),
                        onFind = { tab = if (config.configured) 1 else 4 },
                        onToggle = { transport { repo.toggle() } }, onNext = { transport { repo.next() } },
                        onPrevious = { transport { repo.previous() } }, onSeek = { transport { repo.seekTo(it) } },
                        onShare = { state.current?.song?.let { onShare(listenShareText(it)) } },
                        onLyrics = { state.current?.song?.let { lyricsSelection = it to state.lyrics.toList() } }, onNotes = { tab = 3 })
                    1 -> ListenDiscovery(query, { query = it }, busy, searched, resultTitle, results, playlists,
                        onSearch = { action { results = repo.search(query.trim()); playlists = emptyList(); resultTitle = "搜索结果"; searched = true } },
                        onDaily = { action { results = repo.daily(); playlists = emptyList(); resultTitle = "每日推荐"; searched = true } },
                        onRadar = { action { results = repo.radar(); playlists = emptyList(); resultTitle = "私人雷达"; searched = true } },
                        onPlaylists = { action { playlists = repo.playlists(); results = emptyList(); resultTitle = "我的歌单"; searched = true } },
                        onPlaylist = { list -> action { results = repo.playlist(list.id); playlists = emptyList(); resultTitle = list.title } },
                        onPlay = { song -> action { repo.bindConversation(conversationId); repo.play(song); tab = 0 } },
                        onEnqueue = { song -> action { repo.bindConversation(conversationId); repo.enqueue(song, next = true); message = "已排在下一首" } },
                        onShare = { onShare(listenShareText(it)) }, onConnect = { tab = 4 }, configured = config.configured)
                    2 -> ListenQueue(state, onPlay = { id -> action { repo.playQueueItem(id); tab = 0 } },
                        onRemove = { action { repo.remove(it) } }, onMode = { action { repo.setMode(it) } },
                        onFind = { tab = 1 })
                    3 -> ListenNotesEditor(state.current?.song, notes, noteSong == state.current?.song?.id,
                        loading = notesLoading, busy = busy, onRetry = { notesAttempt++ },
                        onChange = { notes = it }, onSave = { state.current?.song?.let { song -> action {
                            repo.saveNotes(song.id, notes); message = "这一首的心情，记下了"
                        } } }, onShare = { state.current?.song?.let { song -> onShare(listenShareText(song,
                            message = listOf("我的听歌手记", notes.notes, notes.feeling).filter(String::isNotBlank).joinToString("\n"))) } })
                    4 -> ListenConnection(config, conversationId, assistantId, busy, account, login, loginMessage,
                        onSave = { url, token -> action { repo.saveConfiguration(url, token); account = repo.account(); login = null; message = "音乐服务已连接" } },
                        onAuthorize = { enabled -> assistantId?.let {
                            // Consent must remain revocable while an unrelated network operation is running.
                            try { repo.authorize(conversationId, it, enabled) }
                            catch (error: Exception) { message = error.message ?: "陪听授权未能更改"; messageIsError = true }
                        } },
                        onAccount = { action { account = repo.account() } },
                        onLogin = { action { login = repo.loginStart(); loginMessage = "用网易云音乐扫一扫，或截图后从相册识别" } },
                        onCheck = { action { login?.let {
                            loginMessage = when (repo.loginStatus(it.key)) {
                                "waiting" -> "等待扫码"
                                "scanned" -> "已扫码，请在网易云音乐中确认"
                                "authorized" -> "登录成功"
                                "expired" -> "二维码已过期，请重新生成"
                                else -> "暂时无法确认登录状态，请重试"
                            }
                            account = repo.account()
                            if (account?.loggedIn == true) login = null
                        } } },
                        onLogout = { action { repo.logout(); account = repo.account(); login = null } })
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListenPlayer(
    state: ListenState, modifier: Modifier = Modifier,
    onFind: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Long) -> Unit, onShare: () -> Unit, onLyrics: () -> Unit, onNotes: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val item = state.current
    if (item == null) {
        ListenEmpty("把一首歌，分成两份", "从你喜欢的歌开始，留一句话给正在陪你听的人。", "挑一首歌", onFind, modifier)
        return
    }
    val scroll = rememberLazyListState()
    var followLyrics by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, state.currentLyricIndex, followLyrics) {
        if (followLyrics && state.currentLyricIndex >= 0) scroll.animateScrollToItem(state.currentLyricIndex + 1)
    }
    LazyColumn(modifier.fillMaxWidth().testTag("listen-player"), state = scroll, contentPadding = PaddingValues(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Text(if (item.origin == "assistant") "A SONG FOR YOU" else "OUR LISTENING ROOM", fontFamily = GongduBook,
                fontSize = 11.sp, letterSpacing = 2.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
            ListenRecord(item.song.cover, Modifier.size(218.dp))
            Spacer(Modifier.height(26.dp))
            Text(item.song.title, fontSize = 24.sp, textAlign = TextAlign.Center, lineHeight = 33.sp)
            Text(item.song.artist, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
            if (item.origin == "assistant" || item.message.isNotBlank()) {
                Surface(shape = RoundedCornerShape(14.dp), color = colors.primaryContainer, modifier = Modifier.padding(top = 16.dp)) {
                    Text(item.message.ifBlank { "这首是助手放给你的" }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = colors.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(22.dp))
            ListenProgress(state.positionMs, state.durationMs, onSeek)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ListenIcon(HugeIcons.ArrowLeft01, "上一首", onPrevious)
                FilledIconButton(onClick = onToggle, enabled = !state.loading, modifier = Modifier.size(68.dp)) {
                    Icon(if (state.playing) HugeIcons.Pause else HugeIcons.Play, if (state.playing) "暂停" else "播放", Modifier.size(28.dp))
                }
                ListenIcon(HugeIcons.ArrowRight01, "下一首", onNext)
            }
            FlowRow(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onShare) { Text("这首给你") }
                TextButton(onClick = onLyrics, enabled = state.lyrics.isNotEmpty()) { Text("选几句词") }
                TextButton(onClick = onNotes) { Text("写手记") }
            }
            if (state.lyrics.isNotEmpty()) TextButton(onClick = { followLyrics = !followLyrics }) {
                Text(if (followLyrics) "自由浏览歌词" else "跟随歌词")
            }
        }
        if (state.lyrics.isEmpty()) item {
            Text("这首暂时没有歌词", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        itemsIndexed(state.lyrics, key = { index, lyric -> "${lyric.timeMs}-$index" }) { index, lyric ->
            val current = state.currentLyricIndex == index
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSeek(lyric.timeMs) }.padding(12.dp)) {
                Text(lyric.text, color = if (current) colors.primary else colors.onSurfaceVariant,
                    fontSize = if (current) 19.sp else 16.sp, lineHeight = 28.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                if (lyric.translation.isNotBlank()) Text(lyric.translation, color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun ListenRecord(cover: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF49404C), Color(0xFF211C25))))
        .border(1.dp, colors.outline.copy(alpha = .3f), CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 1..12) drawCircle(Color.White.copy(alpha = .045f), radius = size.minDimension * (.25f + i * .018f), style = Stroke(1.dp.toPx()))
            drawLine(Color.White.copy(alpha = .08f), Offset(size.width * .15f, 0f), Offset(size.width * .85f, size.height), 30.dp.toPx())
        }
        if (cover.isNotBlank()) AsyncImage(cover, "专辑封面", Modifier.fillMaxSize(.47f).clip(CircleShape), contentScale = ContentScale.Crop)
        else Icon(HugeIcons.MusicNote03, null, Modifier.size(42.dp), tint = Color(0xFFD6BFD9))
        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.surface).border(1.dp, colors.outline, CircleShape))
    }
}

@Composable
internal fun ListenProgress(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val end = durationMs.coerceAtLeast(1).toFloat()
    Slider(value = scrubbing ?: positionMs.toFloat().coerceIn(0f, end), onValueChange = { scrubbing = it },
        onValueChangeFinished = { scrubbing?.let { onSeek(it.toLong()) }; scrubbing = null },
        valueRange = 0f..end, enabled = durationMs > 0, modifier = Modifier.semantics { contentDescription = "播放进度" })
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(listenTime((scrubbing?.toLong() ?: positionMs)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(listenTime(durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun listenTime(ms: Long): String = (ms.coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListenDiscovery(
    query: String, onQuery: (String) -> Unit, busy: Boolean, searched: Boolean, title: String,
    songs: List<ListenSong>, playlists: List<ListenPlaylist>, onSearch: () -> Unit, onDaily: () -> Unit,
    onRadar: () -> Unit, onPlaylists: () -> Unit, onPlaylist: (ListenPlaylist) -> Unit,
    onPlay: (ListenSong) -> Unit, onEnqueue: (ListenSong) -> Unit, onShare: (ListenSong) -> Unit,
    onConnect: () -> Unit, configured: Boolean,
) {
    if (!configured) { ListenEmpty("连上你的小小音乐房间", "填好音乐服务地址，就能搜索、点歌和一起听。", "去连接", onConnect); return }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            label = { Text("歌名、歌手或专辑") }, singleLine = true, shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank() && !busy) onSearch() }),
            trailingIcon = { IconButton(onClick = onSearch, enabled = query.isNotBlank() && !busy) { Icon(HugeIcons.Search01, "搜索歌曲") } })
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onDaily, enabled = !busy) { Text("每日推荐") }
            TextButton(onClick = onRadar, enabled = !busy) { Text("私人雷达") }
            TextButton(onClick = onPlaylists, enabled = !busy) { Text("我的歌单") }
        }
        Text(title, Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
        if (songs.isEmpty() && playlists.isEmpty() && !busy) {
            Text(if (searched) "这里还没有歌曲，换个关键词或推荐试试。" else "搜一首喜欢的歌，或让今日推荐带路。",
                Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(playlists, key = { it.id }) { list ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPlaylist(list) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ListenArtwork(list.cover, Modifier.size(54.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(list.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${list.count} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp))
                }
            }
            items(songs, key = { it.id }) { song -> ListenSongRow(song, onPlay = { onPlay(song) },
                trailing = { ListenIcon(HugeIcons.Message01, "分享 ${song.title}") { onShare(song) }; ListenIcon(HugeIcons.Add01, "下一首播放 ${song.title}") { onEnqueue(song) } }) }
        }
    }
}

@Composable
internal fun ListenSongRow(song: ListenSong, subtitle: String = song.artist, onPlay: () -> Unit, trailing: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onPlay).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ListenArtwork(song.cover, Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListenQueue(state: ListenState, onPlay: (String) -> Unit, onRemove: (String) -> Unit, onMode: (String) -> Unit, onFind: () -> Unit) {
    if (state.queue.isEmpty()) { ListenEmpty("下一首，留给谁来选", "你和助手排进来的歌，会一起留在这里。", "去找歌", onFind); return }
    LazyColumn(contentPadding = PaddingValues(20.dp)) {
        item {
            Text("接下来一起听", style = MaterialTheme.typography.titleLarge)
            FlowRow(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("order" to "顺序", "shuffle" to "随机", "repeat" to "单曲").forEach { (mode, label) ->
                    FilterChip(selected = state.mode == mode, onClick = { onMode(mode) }, label = { Text(label) })
                }
            }
        }
        items(state.queue, key = { it.id }) { item ->
            val index = state.queue.indexOf(item)
            val origin = if (item.origin == "assistant") "助手为你选的" else item.song.artist
            Surface(color = if (index == state.currentIndex) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                ListenSongRow(item.song, listOf(origin, item.message).filter(String::isNotBlank).joinToString(" · "), onPlay = { onPlay(item.id) }) {
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
    val valid = notes.notes.length <= 4000 && notes.feeling.length <= 1000 &&
        notes.favoriteLines.size <= 20 && notes.favoriteLines.all { it.length <= 300 } &&
        notes.tags.size <= 20 && notes.tags.all { it.length <= 40 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("这一首的手记", style = MaterialTheme.typography.titleLarge)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("把音乐带进来", style = MaterialTheme.typography.titleLarge)
        Text("连接你的音乐服务，再用网易云音乐账号扫码。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("音乐服务地址") },
            placeholder = { Text("https://music.example.com") }, singleLine = true)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("连接令牌") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Button(onClick = { onSave(url, token) }, enabled = !busy && url.isNotBlank() && token.isNotBlank()) { Text("保存并连接") }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("让当前助手陪我听", style = MaterialTheme.typography.titleMedium)
                Text("分享正在播放的歌和歌词，允许助手点歌与排队。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = config.conversationId == conversationId && config.assistantId == assistantId && assistantId != null,
                onCheckedChange = onAuthorize, enabled = config.configured && assistantId != null,
                modifier = Modifier.semantics { contentDescription = "让当前助手陪我听" })
        }
        HorizontalDivider()
        Text(account?.let { if (it.loggedIn) "已登录 · ${it.nickname}" else "网易云账号尚未登录" } ?: "网易云音乐", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onAccount, enabled = config.configured && !busy) { Text("检查登录") }
            if (account?.loggedIn == true) TextButton(onClick = onLogout, enabled = !busy) { Text("退出账号") }
            else Button(onClick = onLogin, enabled = config.configured && !busy) { Text("扫码登录") }
        }
        if (login != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                    QRCode(login.qrUrl, Modifier.padding(16.dp).size(200.dp), color = Color.Black, backgroundColor = Color.White)
                }
            }
            Text(loginMessage, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onCheck, enabled = !busy) { Text("我已扫码，检查状态") }
        }
    }
}

@Composable
private fun ListenLyricsPicker(lines: List<ListenLyric>, onDismiss: () -> Unit, onShare: (List<String>) -> Unit) {
    var selected by remember(lines) { mutableStateOf<Set<Int>>(emptySet()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选几句，递给对方") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(lines.indices.toList()) { index ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (index in selected) selected - index else if (selected.size < 3) selected + index else selected
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = index in selected, onCheckedChange = null)
                        Text(lines[index].text, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onShare(selected.sorted().map { lines[it].text }) }, enabled = selected.isNotEmpty()) { Text("分享到当前聊天") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ListenEmpty(title: String, description: String, button: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
        ListenRecord("", Modifier.size(132.dp))
        Text(title, textAlign = TextAlign.Center, fontSize = 22.sp, lineHeight = 32.sp)
        Text(description, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, lineHeight = 25.sp)
        if (button != null) OutlinedButton(onClick = onClick) { Text(button) }
    }
}

@Composable
internal fun ListenIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, label, Modifier.size(20.dp)) }
}
