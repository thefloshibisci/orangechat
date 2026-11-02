package me.rerere.rikkahub.ui.pages.gongdu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import me.rerere.rikkahub.data.gongdu.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GongduPage(conversationId: String, onNavigateBack: () -> Unit) {
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
    var tab by rememberSaveable { mutableIntStateOf(3) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var feeds by remember { mutableStateOf<List<GongduFeed>>(emptyList()) }
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
    Scaffold(topBar = {
        TopAppBar(title = { Text("共度") }, navigationIcon = { TextButton(onClick = onNavigateBack) { Text("返回") } },
            actions = { Text("小红书", modifier = Modifier.padding(end = 20.dp), style = MaterialTheme.typography.labelLarge) })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(selectedTabIndex = tab) {
                listOf("推荐", "搜索", "消息", "设置").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.primary) }
            when (tab) {
                3 -> GongduSetup(config, status, connected, login, busy,
                    assistantEnabled = assistantId?.let { aid -> settings.assistants.any { it.id == aid && setting.serverId in it.mcpServers } } == true,
                    onSave = { endpoint, token -> runAction { repo.saveConfiguration(endpoint, token); message = "配置已保存，连接后可扫码登录" } },
                    onConnect = { runAction { repo.connect(); login = repo.loginStatus() } },
                    onQr = { runAction { qr = repo.loginQrCode() } },
                    onLoginStatus = { runAction { login = repo.loginStatus() } },
                    onAssistantEnabled = { enabled -> runAction { repo.setAssistantEnabled(checkNotNull(assistantId) { "当前聊天不可用" }, enabled) } },
                )
                2 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("小红书消息", style = MaterialTheme.typography.titleLarge)
                        Text("读取消息会同步平台已读状态。首次连接仅建立基准，不重放旧通知。", style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("新消息告诉当前聊天")
                                Text("APP 在前台时约每分钟检查；关闭 APP 后不保证即时提醒。", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = setting.doorbellEnabled && setting.targetConversationId == id,
                                enabled = config.configured && !busy && assistantId != null,
                                onCheckedChange = { enabled -> runAction {
                                    if (enabled) {
                                        repo.refreshInbox()
                                        repo.setAssistantEnabled(checkNotNull(assistantId), true)
                                    }
                                    repo.configureDoorbell(checkNotNull(id), enabled)
                                } })
                        }
                    }
                    item { Button(enabled = config.configured && !busy, onClick = { runAction { repo.refreshInbox() } }) { Text("检查新消息") } }
                    if (!setting.inbox.supported) item { Text("连接后将检查通知能力；服务不支持通知时，浏览和搜索仍可使用。") }
                    if (setting.inbox.supported && setting.inbox.events.isEmpty()) item { Text("暂时没有新的互动") }
                    items(setting.inbox.events.asReversed(), key = { it.id }) { event ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                            Text(event.summary)
                            Text(if (event.acknowledged) "已处理或首次同步" else "待送到聊天", style = MaterialTheme.typography.labelSmall)
                        } }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!config.configured) item { GongduEmptyService { tab = 3 } }
                    else {
                        item {
                            if (tab == 1) {
                                OutlinedTextField(keyword, { keyword = it }, label = { Text("想和机一起看什么？") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(10.dp))
                                Button(enabled = !busy && keyword.isNotBlank(), onClick = { runAction { feeds = repo.search(keyword) } }) { Text("搜索小红书") }
                            } else Button(enabled = !busy, onClick = { runAction { feeds = repo.feeds() } }) { Text("刷新推荐") }
                        }
                        if (feeds.isEmpty()) item { Text(if (tab == 1) "输入关键词，找一篇想聊的笔记。" else "刷新推荐，开始一起逛小红书。") }
                        items(feeds, key = { it.id }) { feed ->
                            Card(Modifier.fillMaxWidth().clickable(enabled = !busy) { runAction { selected = repo.detail(feed) } }) {
                                if (feed.cover.isNotBlank()) AsyncImage(feed.cover, contentDescription = feed.title, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
                                Column(Modifier.padding(16.dp)) {
                                    Text(feed.title, style = MaterialTheme.typography.titleMedium)
                                    if (feed.author.isNotBlank()) Text(feed.author, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
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

@Composable
internal fun GongduEmptyService(onConfigure: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("一起逛小红书", style = MaterialTheme.typography.headlineSmall)
        Text("小红书服务还没有配置。先连接你自己的服务，之后就能扫码登录、浏览笔记和接收互动。")
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("小红书连接", style = MaterialTheme.typography.titleLarge); Text(status) }
        item { OutlinedTextField(endpoint, { endpoint = it }, enabled = !busy, label = { Text("服务地址") },
            placeholder = { Text("https://你的服务地址/mcp") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(token, { token = it }, enabled = !busy, label = { Text("访问令牌（如服务需要）") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !busy && endpoint.isNotBlank(), onClick = { onSave(endpoint, token) }) { Text("保存配置") }
            OutlinedButton(enabled = !busy && config.configured, onClick = onConnect) { Text("连接服务") }
        } }
        item { HorizontalDivider() }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("让当前助手使用小红书"); Text("启用后，机可以调用服务提供的浏览和互动工具。", style = MaterialTheme.typography.bodySmall) }
                Switch(assistantEnabled, onCheckedChange = onAssistantEnabled, enabled = config.configured && !busy)
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = connected && !busy, onClick = onQr) { Text("扫码登录") }
            OutlinedButton(enabled = connected && !busy, onClick = onLoginStatus) { Text("检查登录状态") }
        } }
        if (login.isNotBlank()) item { Text(login) }
        item { Text("发帖、评论等公开操作沿用聊天里的工具确认设置。服务地址与小红书账号登录是两步，连接成功后仍需检查登录状态。", style = MaterialTheme.typography.bodySmall) }
    }
}
