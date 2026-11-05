package me.rerere.rikkahub.ui.pages.gongdu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import me.rerere.rikkahub.data.gongdu.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** Entry from the chat attachment menu: the room hub first, then the chosen room. */
@Composable
fun GongduPage(conversationId: String, onNavigateBack: () -> Unit) {
    val toaster = LocalToaster.current
    var room by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = room != null) { room = null }
    when (room) {
        "look" -> GongduLookRoom(conversationId, onNavigateBack = { room = null })
        else -> GongduHub(
            onNavigateBack = onNavigateBack,
            onEnterRoom = { room = it.id },
            onUnavailableRoom = { toaster.show("这间还在筹备中") },
        )
    }
}

private val GongduTabs = listOf("推荐", "搜索", "消息", "设置")
private val SuggestedKeywords = listOf("周末去哪", "家常菜", "穿搭", "好书", "歌单")

@Composable
internal fun GongduLookRoom(conversationId: String, onNavigateBack: () -> Unit) {
    val repo = koinInject<GongduRepository>()
    val videos = koinInject<GongduVideoReader>()
    val bell = koinInject<GongduDoorbell>()
    val conversations = koinInject<ConversationRepository>()
    val settingsStore = koinInject<SettingsStore>()
    val chat = koinInject<ChatService>()
    val config by repo.configuration.collectAsStateWithLifecycle()
    val setting by repo.setting.collectAsStateWithLifecycle()
    val status by repo.status.collectAsStateWithLifecycle()
    val connected by repo.connected.collectAsStateWithLifecycle()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val id = remember(conversationId) { runCatching { Uuid.parse(conversationId) }.getOrNull() }
    var assistantId by remember(id) { mutableStateOf<Uuid?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(if (config.configured) 0 else 3) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var feeds by remember { mutableStateOf<List<GongduFeed>>(emptyList()) }
    var results by remember { mutableStateOf<List<GongduFeed>>(emptyList()) }
    var selected by remember { mutableStateOf<GongduNote?>(null) }
    var videoStart by remember(selected?.feed?.id) { mutableStateOf("") }
    var videoEnd by remember(selected?.feed?.id) { mutableStateOf("") }
    var qr by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "操作未完成，请稍后重试" }
            finally { busy = false }
        }
    }
    LaunchedEffect(id) {
        assistantId = id?.let { conversations.getConversationById(it)?.assistantId }
        bell.start()
    }
    suspend fun share(note: GongduNote, video: Boolean) {
        val target = checkNotNull(id) { "当前聊天不可用，请从聊天中重新打开共度" }
        val conversation = checkNotNull(conversations.getConversationById(target)) { "当前聊天已删除" }
        val vision = Modality.IMAGE in settingsStore.settingsFlow.value.getChatModelForAssistant(conversation.assistantId)?.inputModalities.orEmpty()
        attachmentReferenceGuard.beginWrite().use {
            val parts = if (video) {
                check(vision) { "当前聊天模型未启用识图，请先选择支持图片的模型" }
                fun optionalSeconds(value: String): Double? = if (value.isBlank()) null else
                    requireNotNull(value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }) { "补看时间请填写非负秒数" }
                videos.read(note, conversation.assistantId, GongduVideoRequest(optionalSeconds(videoStart), optionalSeconds(videoEnd)))
            } else buildList {
                add(UIMessagePart.Text("[共度·小红书]\n${note.feed.title}\n${note.content}\n作者：${note.feed.author}\n笔记ID：${note.feed.id}\n我们一起聊聊这篇吧。"))
                if (vision && note.feed.cover.isNotBlank()) add(UIMessagePart.Image(note.feed.cover))
            }
            check(conversations.getConversationById(target)?.assistantId == conversation.assistantId) { "当前聊天的助手已改变，请重新打开笔记" }
            checkNotNull(chat.submitMessage(target, parts)) { "未能加入聊天，请返回聊天后重试" }
            selected = null
            message = "已送到当前聊天，等待机查看"
        }
    }
    val openNote: (GongduFeed) -> Unit = { feed -> runAction { selected = repo.detail(feed) } }

    Scaffold(topBar = { RoomHeader("一起逛", "小红书 · 一起看，也一起聊", connected, onNavigateBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            RoomTabs(GongduTabs, tab, onSelect = { tab = it }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let {
                Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
            when (tab) {
                0 -> if (!config.configured) EmptyServiceBox { tab = 3 } else FeedGrid(
                    feeds, busy, header = {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("一起逛逛", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            FilledTonalButton(enabled = !busy, onClick = { runAction { feeds = repo.feeds() } },
                                contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(32.dp)) {
                                Icon(HugeIcons.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                                Text("换一批", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }, empty = "点「换一批」，开始一起逛小红书。", onOpen = openNote,
                )
                1 -> if (!config.configured) EmptyServiceBox { tab = 3 } else FeedGrid(
                    results, busy, header = {
                        SearchHeader(keyword, onKeywordChange = { keyword = it }, busy = busy,
                            onSearch = { word -> keyword = word; runAction { results = repo.search(word) } })
                    }, empty = "输入关键词，找一篇想聊的笔记。", onOpen = openNote,
                )
                2 -> InboxTab(config.configured, setting, id, assistantId, busy,
                    onDoorbell = { enabled -> runAction {
                        if (enabled) {
                            repo.refreshInbox()
                            repo.setAssistantEnabled(checkNotNull(assistantId), true)
                        }
                        repo.configureDoorbell(checkNotNull(id), enabled)
                    } },
                    onRefresh = { runAction { repo.refreshInbox() } })
                else -> GongduSetup(config, status, connected, login, busy,
                    assistantEnabled = assistantId?.let { aid -> settings.assistants.any { it.id == aid && setting.serverId in it.mcpServers } } == true,
                    onSave = { endpoint, token -> runAction { repo.saveConfiguration(endpoint, token); message = "配置已保存，连接后可扫码登录" } },
                    onConnect = { runAction { repo.connect(); login = repo.loginStatus() } },
                    onQr = { runAction { qr = repo.loginQrCode() } },
                    onLoginStatus = { runAction { login = repo.loginStatus() } },
                    onAssistantEnabled = { enabled -> runAction { repo.setAssistantEnabled(checkNotNull(assistantId) { "当前聊天不可用" }, enabled) } },
                )
            }
        }
    }
    selected?.let { note ->
        ModalBottomSheet(onDismissRequest = { if (!busy) selected = null }) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Text(note.feed.title, style = MaterialTheme.typography.headlineSmall) }
                item { Text(note.content.ifBlank { "这篇笔记没有文字正文" }) }
                item {
                    Button(enabled = !busy, onClick = { runAction { share(note, false) } }) { Text("和机聊这篇") }
                    val body = (note.raw["note"] as? JsonObject) ?: note.raw
                    if (body["video"] is JsonObject) {
                        OutlinedButton(enabled = !busy, onClick = { runAction { share(note, true) } }) { Text("让机看视频") }
                        Text("按时长抽取最多24帧，带时间标记；声音由支持音频的模型直接分析，或用语音识别读取对白。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(videoStart, { videoStart = it }, modifier = Modifier.weight(1f), label = { Text("补看起点（秒）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            OutlinedTextField(videoEnd, { videoEnd = it }, modifier = Modifier.weight(1f), label = { Text("补看终点（秒）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        Text("留空看全片概览；填写后补看指定片段，每次最多30秒。授权当前助手使用小红书后，机也可自主补看。仅转写文字时不能分析语气、音乐或音效。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    qr?.let { image ->
        AlertDialog(onDismissRequest = { qr = null }, title = { Text("小红书扫码登录") },
            text = { Column { AsyncImage(image, contentDescription = "小红书登录二维码", modifier = Modifier.size(260.dp)); Text("二维码会过期，扫码授权后检查登录状态。") } },
            confirmButton = { TextButton(onClick = { qr = null; runAction { login = repo.loginStatus() } }) { Text("检查登录状态") } },
            dismissButton = { TextButton(onClick = { qr = null }) { Text("关闭") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomHeader(title: String, subtitle: String, connected: Boolean, onBack: () -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, contentDescription = "返回大厅") } },
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = { ConnectionChip(connected, Modifier.padding(end = 12.dp)) },
    )
}

@Composable
internal fun ConnectionChip(connected: Boolean, modifier: Modifier = Modifier) {
    val color = if (connected) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.error
    Row(
        modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(if (connected) "已连接" else "未连接", color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** Capsule segmented control: the selected option is a raised surface pill. */
@Composable
internal fun RoomTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(40.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(3.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            val active = index == selected
            Surface(
                onClick = { onSelect(index) }, shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                shadowElevation = if (active) 2.dp else 0.dp,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyServiceBox(onConfigure: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp)) { GongduEmptyService(onConfigure) }
}

@Composable
private fun FeedGrid(
    feeds: List<GongduFeed>, busy: Boolean, header: @Composable () -> Unit, empty: String, onOpen: (GongduFeed) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = StaggeredGridItemSpan.FullLine) { Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) { header() } }
        if (feeds.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
            Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 24.dp))
        }
        items(feeds, key = { it.id }) { feed -> FeedCard(feed, enabled = !busy) { onOpen(feed) } }
    }
}

@Composable
internal fun FeedCard(feed: GongduFeed, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (feed.cover.isNotBlank()) {
            // Covers keep their own aspect ratio; the cap stops very tall images from dominating a column.
            AsyncImage(feed.cover, contentDescription = feed.title, contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(cleanSummary(feed.title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (feed.author.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(feed.author, 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(feed.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun InitialAvatar(name: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).ifBlank { "?" }, color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = (size.value * 0.5f).sp, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHeader(keyword: String, onKeywordChange: (String) -> Unit, busy: Boolean, onSearch: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            keyword, onKeywordChange, singleLine = true, shape = CircleShape,
            placeholder = { Text("想和机一起看什么？") },
            leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (!busy && keyword.isNotBlank()) onSearch(keyword.trim()) }),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestedKeywords.forEach { word ->
                SuggestionChip(onClick = { if (!busy) onSearch(word) }, label = { Text(word, maxLines = 1) })
            }
        }
    }
}

internal enum class InboxKind { Follow, Like, Comment, Other }

/** The service only returns a summary line; infer the badge from its wording. */
/** Only explicit verbs decide the kind; a comment such as "好喜欢这篇" must not be shown as a like. */
internal fun inboxKind(summary: String): InboxKind {
    val body = splitInboxSummary(summary).second
    return when {
        listOf("关注了", "关注你").any { it in body } -> InboxKind.Follow
        listOf("赞了", "收藏了").any { it in body } -> InboxKind.Like
        listOf("评论了", "回复了", "@了").any { it in body } -> InboxKind.Comment
        else -> InboxKind.Other
    }
}

/** Split "名字：内容" produced by the codec into name and body. */
internal fun splitInboxSummary(summary: String): Pair<String, String> {
    val index = summary.indexOf('：')
    return if (index in 1..24) summary.take(index) to summary.drop(index + 1) else "" to summary
}

/** Strip markdown/template markers such as `$`, `#话题[话题]#` brackets and emphasis that leak into summaries. */
internal fun cleanSummary(text: String): String = text
    .replace(Regex("\\[[^\\]]{1,12}R?\\]"), "")
    .replace(Regex("[$*_`~]+"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

@Composable
private fun InboxTab(
    configured: Boolean, setting: GongduSetting, conversationId: Uuid?, assistantId: Uuid?, busy: Boolean,
    onDoorbell: (Boolean) -> Unit, onRefresh: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("新消息告诉当前聊天", style = MaterialTheme.typography.titleSmall)
                        Text("APP 在前台时约每分钟检查；关闭 APP 后不保证即时提醒。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = setting.doorbellEnabled && setting.targetConversationId == conversationId,
                        enabled = configured && !busy && assistantId != null, onCheckedChange = onDoorbell)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("消息", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(enabled = configured && !busy, onClick = onRefresh) {
                    Icon(HugeIcons.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("检查新消息")
                }
            }
        }
        item {
            val events = setting.inbox.events.asReversed()
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                when {
                    !configured -> InboxNote("配置小红书服务后可以接收互动消息。")
                    !setting.inbox.supported -> InboxNote("连接后将检查通知能力；服务不支持通知时，浏览和搜索仍可使用。")
                    events.isEmpty() -> InboxNote("暂时没有新的互动")
                    else -> Column {
                        events.forEachIndexed { index, event ->
                            InboxRow(event)
                            if (index != events.lastIndex) HorizontalDivider(Modifier.padding(start = 68.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(20.dp))
}

@Composable
internal fun InboxRow(event: GongduEvent) {
    val (name, body) = splitInboxSummary(event.summary)
    val kind = inboxKind(event.summary)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Box {
            InitialAvatar(name.ifBlank { "小" }, 40.dp)
            KindBadge(kind, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // The upstream summary often already reads "赞了你的笔记"; only add the verb when it is missing.
            // Show the verb only for recognised kinds whose summary does not already say it.
            val action = kindAction(kind).takeIf { it.isNotBlank() && it.take(3) !in body }.orEmpty()
            Text(
                listOf(name, action).filter(String::isNotBlank).joinToString(" ").ifBlank { "小红书互动" },
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (body.isNotBlank()) Text(cleanSummary(body), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!event.acknowledged) Box(
            Modifier.padding(start = 8.dp, top = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error)
        )
    }
}

private fun kindAction(kind: InboxKind) = when (kind) {
    InboxKind.Follow -> "关注了你"
    InboxKind.Like -> "赞了你"
    InboxKind.Comment -> "评论了你"
    InboxKind.Other -> ""
}

@Composable
private fun KindBadge(kind: InboxKind, modifier: Modifier) {
    val (color, icon) = when (kind) {
        InboxKind.Follow -> MaterialTheme.colorScheme.primary to HugeIcons.Add01
        InboxKind.Like -> Color(0xFFE5484D) to HugeIcons.Favourite
        InboxKind.Comment -> Color(0xFFF08A24) to HugeIcons.Message01
        InboxKind.Other -> return
    }
    Box(
        modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(2.dp)
            .clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp)) }
}

@Composable
internal fun GongduEmptyService(onConfigure: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("一起逛小红书", style = MaterialTheme.typography.titleLarge)
        Text("小红书服务还没有配置。先连接你自己的服务，之后就能扫码登录、浏览笔记和接收互动。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onConfigure) { Text("配置小红书服务") }
    }
}

@Composable
internal fun GongduSetup(
    config: GongduConfiguration, status: String, connected: Boolean, login: String, busy: Boolean,
    assistantEnabled: Boolean, onSave: (String, String) -> Unit, onConnect: () -> Unit, onQr: () -> Unit,
    onLoginStatus: () -> Unit, onAssistantEnabled: (Boolean) -> Unit,
) {
    var endpoint by remember(config.endpoint) { mutableStateOf(config.endpoint) }
    var token by remember(config.token) { mutableStateOf(config.token) }
    // Service fields stay folded away once configured; first-time setup opens them directly.
    var advanced by rememberSaveable { mutableStateOf(!config.configured) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { AccountCard(connected, login, busy, onQr, onLoginStatus) }
        item {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("让当前助手使用小红书", style = MaterialTheme.typography.titleSmall)
                        Text("启用后，机可以调用服务提供的浏览和互动工具。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(assistantEnabled, onCheckedChange = onAssistantEnabled, enabled = config.configured && !busy)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("高级设置", style = MaterialTheme.typography.titleSmall)
                            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(if (advanced) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, contentDescription = if (advanced) "收起" else "展开")
                    }
                    if (advanced) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(endpoint, { endpoint = it }, enabled = !busy, label = { Text("服务地址") },
                            placeholder = { Text("https://你的服务地址/mcp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(token, { token = it }, enabled = !busy, label = { Text("访问令牌（如服务需要）") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(enabled = !busy && endpoint.isNotBlank(), onClick = { onSave(endpoint, token) }) { Text("保存配置") }
                            OutlinedButton(enabled = !busy && config.configured, onClick = onConnect) { Text("连接服务") }
                        }
                    }
                }
            }
        }
        item {
            Text("发帖、评论等公开操作沿用聊天里的工具确认设置。服务地址与小红书账号登录是两步，连接成功后仍需检查登录状态。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun AccountCard(connected: Boolean, login: String, busy: Boolean, onQr: () -> Unit, onLoginStatus: () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar("红", 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("小红书账号", style = MaterialTheme.typography.titleSmall)
                    Text(login.ifBlank { if (connected) "尚未检查登录状态" else "连接服务后可扫码登录" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = connected && !busy, onClick = onQr) { Text("扫码登录") }
                OutlinedButton(enabled = connected && !busy, onClick = onLoginStatus) { Text("检查登录状态") }
            }
        }
    }
}
