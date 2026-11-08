package me.rerere.rikkahub.ui.pages.gongdu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Modality
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
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.getChatModelOf
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
    val uriHandler = LocalUriHandler.current
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
    var assistantUpdating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun runAction(showProgress: Boolean = true, action: suspend () -> Unit) {
        if (busy || assistantUpdating) return
        if (showProgress) busy = true else assistantUpdating = true
        message = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "操作未完成，请稍后重试" }
            finally { if (showProgress) busy = false else assistantUpdating = false }
        }
    }
    LaunchedEffect(id) {
        assistantId = id?.let { conversations.getConversationById(it)?.assistantId }
        bell.start()
    }
    suspend fun share(note: GongduNote, video: Boolean) {
        val target = checkNotNull(id) { "当前聊天不可用，请从聊天中重新打开共度" }
        val conversation = checkNotNull(conversations.getConversationById(target)) { "当前聊天已删除" }
        val currentSettings = settingsStore.settingsFlow.value
        val model = checkNotNull(currentSettings.getChatModelOf(conversation)) { "请先为当前会话选择可用的聊天模型" }
        val vision = Modality.IMAGE in model.inputModalities
        attachmentReferenceGuard.beginWrite().use {
            val parts = if (video) {
                check(vision) { "当前聊天模型未启用识图，请先选择支持图片的模型" }
                fun optionalSeconds(value: String): Double? = if (value.isBlank()) null else
                    requireNotNull(value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }) { "补看时间请填写非负秒数" }
                videos.read(note, model, model.findProvider(currentSettings.providers), GongduVideoRequest(optionalSeconds(videoStart), optionalSeconds(videoEnd)))
            } else gongduNoteShareParts(note, vision)
            check(conversations.getConversationById(target)?.assistantId == conversation.assistantId) { "当前聊天的助手已改变，请重新打开笔记" }
            checkNotNull(chat.submitMessage(target, parts)) { "未能加入聊天，请返回聊天后重试" }
            selected = null
            message = "已送到当前聊天，等待机查看"
        }
    }
    val openNote: (GongduFeed) -> Unit = { feed -> runAction { selected = repo.detail(feed) } }

    GongduWaterBackdrop {
    Scaffold(containerColor = Color.Transparent, topBar = { RoomHeader("一起逛", "小红书 · 一起看，也一起聊", connected, onNavigateBack) }) { padding ->
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
                        RecommendationHeader(busy) { runAction { feeds = repo.feeds() } }
                    }, empty = "点「换一批」，开始一起逛小红书。", onOpen = openNote,
                )
                1 -> if (!config.configured) EmptyServiceBox { tab = 3 } else FeedGrid(
                    results, busy, header = {
                        SearchHeader(keyword, onKeywordChange = { keyword = it }, busy = busy,
                            onSearch = { word -> keyword = word; runAction { results = repo.search(word) } })
                    }, empty = "", onOpen = openNote,
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
                    assistantUpdating = assistantUpdating,
                    onAssistantEnabled = { enabled -> runAction(showProgress = false) {
                        repo.setAssistantEnabled(checkNotNull(assistantId) { "当前聊天不可用" }, enabled)
                    } },
                    onSave = { endpoint, token -> runAction { repo.saveConfiguration(endpoint, token); message = "配置已保存，连接后可扫码登录" } },
                    onConnect = { runAction { repo.connect(); login = repo.loginStatus() } },
                    onQr = { runAction { qr = repo.loginQrCode() } },
                    onLoginStatus = { runAction { login = repo.loginStatus() } },
                )
            }
        }
    }
    }
    selected?.let { note ->
        Dialog(onDismissRequest = { if (!busy) selected = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            GongduNoteDetail(note, busy, message,
                onBack = { selected = null },
                onOriginal = {
                    runCatching { uriHandler.openUri(gongduNoteUrl(note)) }
                        .onFailure { message = "无法打开原文，请检查是否安装浏览器或小红书" }
                },
                onShare = { runAction { share(note, false) } },
            ) {
                    if (note.body["video"] is JsonObject) {
                        var expanded by remember(note.feed.id) { mutableStateOf(false) }
                        GongduButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { runAction { share(note, true) } }) { Text("让机看视频") }
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起视频选项" else "视频补看片段") }
                        if (expanded) {
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
internal fun RoomHeader(title: String, subtitle: String, connected: Boolean, onBack: () -> Unit) {
    TopAppBar(
        expandedHeight = maxOf(64.dp, (44 * LocalDensity.current.fontScale + 16).dp),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        modifier = Modifier,
        navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, contentDescription = "返回大厅") } },
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontFamily = GongduBook,
                    fontWeight = FontWeight.Normal, letterSpacing = .5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        actions = { ConnectionChip(connected, Modifier.padding(end = 12.dp)) },
    )
}

@Composable
internal fun ConnectionChip(connected: Boolean, modifier: Modifier = Modifier) {
    val color = if (connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    Row(
        modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (connected) Color(0xFF56836B) else color))
        Spacer(Modifier.width(6.dp))
        Text(if (connected) "已连接" else "未连接", color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** A raised selected key inside a recessed track; each key keeps its full touch target. */
@Composable
internal fun RoomTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)
        .gongduGlass(RoundedCornerShape(16.dp), inset = true)
        .padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        tabs.forEachIndexed { index, title ->
            val active = index == selected
            Column(
                modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                    .then(if (active) Modifier.gongduGlass(RoundedCornerShape(12.dp), raised = true) else Modifier)
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    fontWeight = FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)

            }
        }
    }
}

@Composable
private fun EmptyServiceBox(onConfigure: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp)) { GongduEmptyService(onConfigure) }
}

@Composable
internal fun FeedGrid(
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
        if (feeds.isEmpty() && empty.isNotBlank()) item(span = StaggeredGridItemSpan.FullLine) {
            Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 24.dp))
        }
        items(feeds, key = { it.id }) { feed -> FeedCard(feed, enabled = !busy) { onOpen(feed) } }
    }
}

@Composable
internal fun FeedCard(feed: GongduFeed, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().gongduGlass(RoundedCornerShape(12.dp)),
    ) {
        if (feed.cover.isNotBlank()) {
            // Covers keep their own aspect ratio; the cap stops very tall images from dominating a column.
            AsyncImage(feed.cover, contentDescription = feed.title, contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
        } else CoverPlaceholder(feed.title)
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(cleanSummary(feed.title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal,
                lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
internal fun SearchHeader(keyword: String, onKeywordChange: (String) -> Unit, busy: Boolean, onSearch: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            keyword, onKeywordChange, singleLine = true, shape = RoundedCornerShape(16.dp),
            placeholder = { Text("想和机一起看什么？") },
            leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (!busy && keyword.isNotBlank()) onSearch(keyword.trim()) }),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .5f)),
            modifier = Modifier.fillMaxWidth().gongduGlass(RoundedCornerShape(16.dp), inset = true),
            textStyle = MaterialTheme.typography.bodyLarge,
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SuggestedKeywords.forEach { word ->
                GongduButton(onClick = { onSearch(word) }, enabled = !busy,
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(word, maxLines = 1)
                }
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
internal fun InboxTab(
    configured: Boolean, setting: GongduSetting, conversationId: Uuid?, assistantId: Uuid?, busy: Boolean,
    onDoorbell: (Boolean) -> Unit, onRefresh: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = roomCardColors(), modifier = Modifier.gongduGlass(RoundedCornerShape(20.dp))) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("新消息告诉当前聊天", style = MaterialTheme.typography.titleSmall)
                        Text("APP 在前台时约每分钟检查；关闭 APP 后不保证即时提醒。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GongduSwitch(checked = setting.doorbellEnabled && setting.targetConversationId == conversationId,
                        enabled = configured && !busy && assistantId != null, onCheckedChange = onDoorbell)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val pending = setting.inbox.events.count { !it.acknowledged }
                SectionTitle("消息", if (pending > 0) "$pending 条待送到聊天" else "暂无待送到聊天的消息", Modifier.weight(1f))
                TextButton(enabled = configured && !busy, onClick = onRefresh) {
                    Icon(HugeIcons.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("检查新消息")
                }
            }
        }
        item {
            // Acknowledged means delivered to chat (or initial sync), not read by the user.
            // Keep pending deliveries first, newest first within each group.
            val events = setting.inbox.events.asReversed().sortedBy { it.acknowledged }
            Card(shape = RoundedCornerShape(20.dp), colors = roomCardColors(), modifier = Modifier.gongduGlass(RoundedCornerShape(20.dp))) {
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
            // Badges group related events; they cannot invent a verb (e.g. 收藏 is not 赞).
            Text(
                name.ifBlank { "小红书互动" },
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (body.isNotBlank()) Text(cleanSummary(body), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!event.acknowledged) Box(
            Modifier.padding(start = 8.dp, top = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error)
        )
    }
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
        GongduButton(onClick = onConfigure) { Text("配置小红书服务") }
    }
}

@Composable
internal fun GongduSetup(
    config: GongduConfiguration, status: String, connected: Boolean, login: String, busy: Boolean,
    assistantEnabled: Boolean, onSave: (String, String) -> Unit, onConnect: () -> Unit, onQr: () -> Unit,
    onLoginStatus: () -> Unit, onAssistantEnabled: (Boolean) -> Unit,
    assistantUpdating: Boolean = false,
) {
    var endpoint by remember(config.endpoint) { mutableStateOf(config.endpoint) }
    var token by remember(config.token) { mutableStateOf(config.token) }
    // Service fields stay folded away once configured; first-time setup opens them directly.
    var advanced by rememberSaveable { mutableStateOf(!config.configured) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { AccountCard(connected, login, busy, onQr, onLoginStatus) }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = roomCardColors(), modifier = Modifier.gongduGlass(RoundedCornerShape(20.dp))) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("让当前助手使用小红书", style = MaterialTheme.typography.titleSmall)
                        Text("启用后，机可以调用服务提供的浏览和互动工具。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GongduSwitch(assistantEnabled, onCheckedChange = onAssistantEnabled,
                        enabled = config.configured && !busy && !assistantUpdating)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = roomCardColors(), modifier = Modifier.gongduGlass(RoundedCornerShape(20.dp))) {
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
                            GongduButton(enabled = !busy && endpoint.isNotBlank(), onClick = { onSave(endpoint, token) }) { Text("保存配置") }
                            GongduButton(enabled = !busy && config.configured, onClick = onConnect) { Text("连接服务") }
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
    Card(shape = RoundedCornerShape(20.dp), colors = roomCardColors(), modifier = Modifier.gongduGlass(RoundedCornerShape(20.dp))) {
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
                GongduButton(enabled = connected && !busy, onClick = onQr) { Text("扫码登录") }
                GongduButton(enabled = connected && !busy, onClick = onLoginStatus) { Text("检查登录状态") }
            }
        }
    }
}


/** The shared glass modifier supplies each card surface. */
@Composable
internal fun roomCardColors() = CardDefaults.cardColors(containerColor = Color.Transparent)

@Composable
internal fun roomCardBorder() = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
internal fun SectionTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Soft gradient stand-in for notes without a cover; height varies a little so the grid stays staggered. */
@Composable
internal fun CoverPlaceholder(title: String) {
    val colors = MaterialTheme.colorScheme
    val height = 150 + (title.hashCode().mod(5)) * 18
    Box(
        Modifier.fillMaxWidth().height(height.dp)
            .background(Brush.linearGradient(listOf(colors.surfaceContainerLow, colors.surfaceContainer))),
        contentAlignment = Alignment.Center,
    ) {
        // A quiet mark only: the title is already shown right below the cover.
        Text("✦", color = colors.onSurfaceVariant.copy(alpha = 0.25f), fontSize = 18.sp)
    }
}

@Composable
internal fun RecommendationHeading(modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("挑一篇，带回聊天", style = MaterialTheme.typography.titleMedium, fontFamily = GongduBook,
            fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
        GongduCelestialRule(MaterialTheme.colorScheme.outline)
    }
}

@Composable
internal fun RecommendationHeader(busy: Boolean, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RecommendationHeading(Modifier.weight(1f))
        GongduButton(enabled = !busy, onClick = onRefresh,
            contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(HugeIcons.Refresh, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("换一批", style = MaterialTheme.typography.labelMedium)
        }
    }
}
