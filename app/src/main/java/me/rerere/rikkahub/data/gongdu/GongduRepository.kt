package me.rerere.rikkahub.data.gongdu

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

data class GongduConfiguration(val endpoint: String = "", val token: String = "") {
    val configured get() = endpoint.isNotBlank()
    override fun toString() = "GongduConfiguration(configured=$configured, token=***)"
}

interface GongduTransport {
    suspend fun connect(config: McpServerConfig): McpStatus
    suspend fun call(serverId: Uuid, name: String, args: JsonObject, timeoutSeconds: Int): List<UIMessagePart>
}

private class ManagerGongduTransport(private val mcp: McpManager) : GongduTransport {
    override suspend fun connect(config: McpServerConfig): McpStatus {
        mcp.addClient(config)
        return mcp.getStatus(config).first()
    }
    override suspend fun call(serverId: Uuid, name: String, args: JsonObject, timeoutSeconds: Int) =
        mcp.callTool(serverId, name, args, timeoutSeconds = timeoutSeconds, failOnError = true)
}

class GongduRepository(private val store: SettingsStore, private val transport: GongduTransport, scope: AppScope) {
    constructor(store: SettingsStore, mcp: McpManager, scope: AppScope) : this(store, ManagerGongduTransport(mcp), scope)
    private val lock = Mutex()
    val setting = store.settingsFlow.map { it.gongdu }.stateIn(scope, SharingStarted.Eagerly, store.settingsFlow.value.gongdu)
    val configuration = store.settingsFlow.map { settings ->
        val server = settings.mcpServers.find { it.id == settings.gongdu.serverId } as? McpServerConfig.StreamableHTTPServer
        GongduConfiguration(server?.url.orEmpty(), server?.commonOptions?.headers
            ?.firstOrNull { it.first.equals("Authorization", true) }?.second?.removePrefix("Bearer ").orEmpty())
    }.stateIn(scope, SharingStarted.Eagerly, GongduConfiguration())
    val status = MutableStateFlow("尚未连接")
    val connected = MutableStateFlow(false)

    suspend fun saveConfiguration(endpoint: String, token: String) = lock.withLock {
        val url = validateEndpoint(endpoint)
        require(!token.contains('\n') && !token.contains('\r')) { "访问令牌格式不正确" }
        val old = store.settingsFlowRaw.first()
        val id = old.gongdu.serverId ?: Uuid.random()
        val prior = old.mcpServers.find { it.id == id }
        val headers = if (token.isBlank()) emptyList() else listOf("Authorization" to "Bearer ${token.trim()}")
        val sameConnection = prior is McpServerConfig.StreamableHTTPServer && prior.url == url && prior.commonOptions.headers == headers
        val options = (prior?.commonOptions ?: McpCommonOptions()).copy(
            enable = true, name = "共度 · 小红书", headers = headers,
            tools = if (sameConnection) prior?.commonOptions?.tools.orEmpty() else emptyList(),
        )
        val server = McpServerConfig.StreamableHTTPServer(id = id, commonOptions = options, url = url)
        store.update { current -> current.copy(
            mcpServers = current.mcpServers.filterNot { it.id == id } + server,
            gongdu = current.gongdu.copy(serverId = id, inbox = if (sameConnection)
                current.gongdu.inbox else GongduInbox()),
        ) }
        connected.value = false
        status.value = "配置已保存，请连接服务"
    }

    suspend fun setAssistantEnabled(assistantId: Uuid, enabled: Boolean) {
        store.update { current ->
            val id = checkNotNull(current.gongdu.serverId) { "请先配置小红书服务" }
            check(current.assistants.any { it.id == assistantId }) { "当前助手已删除" }
            current.copy(assistants = current.assistants.map { if (it.id == assistantId) it.copy(
                mcpServers = if (enabled) it.mcpServers + id else it.mcpServers - id,
            ) else it })
        }
    }

    suspend fun configureDoorbell(conversationId: Uuid, enabled: Boolean) {
        store.update { it.copy(gongdu = it.gongdu.copy(targetConversationId = conversationId, doorbellEnabled = enabled)) }
    }

    suspend fun connect() = lock.withLock {
        connected.value = false
        status.value = "正在连接…"
        val server = server()
        try {
            val previousTools = server.commonOptions.tools.mapTo(mutableSetOf()) { it.name }
            val result = transport.connect(server)
            check(result == McpStatus.Connected) { (result as? McpStatus.Error)?.message ?: "小红书服务未连接，请检查地址与访问令牌" }
            val tools = toolNames()
            check(setOf("check_login_status", "search_feeds", "get_feed_detail").all { it in tools }) { "连接的服务缺少小红书工具，请核对服务地址" }
            store.update { current -> current.copy(mcpServers = current.mcpServers.map { config ->
                if (config.id != server.id) config else config.clone(commonOptions = config.commonOptions.copy(
                    tools = config.commonOptions.tools.map { tool -> if (tool.name !in previousTools && tool.name in PUBLIC_ACTIONS)
                        tool.copy(needsApproval = true) else tool },
                ))
            }) }
            connected.value = true
            status.value = "服务已连接，发现 ${tools.size} 个工具"
        } catch (error: Exception) {
            status.value = "连接失败，请检查服务地址、访问令牌和网络"
            throw error
        }
    }

    private suspend fun server(): McpServerConfig {
        val settings = store.settingsFlowRaw.first()
        return checkNotNull(settings.mcpServers.find { it.id == settings.gongdu.serverId }) { "请先在设置中填写小红书服务地址" }
    }

    suspend fun toolNames(): Set<String> = server().commonOptions.tools.filter { it.enable }.mapTo(mutableSetOf()) { it.name }

    suspend fun call(name: String, args: JsonObject = buildJsonObject {}): List<UIMessagePart> {
        val config = server()
        check(name in toolNames()) { "当前服务不支持此功能：$name" }
        return transport.call(config.id, name, args, if (name.startsWith("publish")) 600 else 120)
    }

    private suspend fun data(name: String, args: JsonObject = buildJsonObject {}) = GongduCodec.payload(
        call(name, args).filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
    )

    suspend fun loginStatus(): String = call("check_login_status").filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    suspend fun loginQrCode(): String = call("get_login_qrcode").filterIsInstance<UIMessagePart.Image>().firstOrNull()?.url
        ?: error("服务没有返回登录二维码；若已登录，请直接检查登录状态")
    suspend fun feeds() = GongduCodec.feeds(data("list_feeds"))
    suspend fun search(keyword: String): List<GongduFeed> {
        require(keyword.isNotBlank()) { "请输入搜索内容" }
        return GongduCodec.feeds(data("search_feeds", buildJsonObject { put("keyword", keyword.trim().take(200)) }))
    }
    suspend fun detail(feed: GongduFeed): GongduNote {
        require(feed.id.isNotBlank() && feed.token.isNotBlank()) { "笔记访问凭据已缺失，请重新搜索或刷新列表" }
        return GongduCodec.note(data("get_feed_detail", buildJsonObject { put("feed_id", feed.id); put("xsec_token", feed.token) }), feed)
    }

    suspend fun refreshInbox(): GongduInbox = lock.withLock {
        if ("list_notifications" !in toolNames()) {
            store.update { it.copy(gongdu = it.gongdu.copy(inbox = it.gongdu.inbox.copy(supported = false))) }
            return@withLock store.settingsFlowRaw.first().gongdu.inbox
        }
        val incoming = mutableListOf<GongduEvent>()
        for (tab in listOf("mentions", "likes", "connections")) {
            val events = GongduCodec.events(data("list_notifications", buildJsonObject { put("tab", tab); put("limit", 50) }))
            incoming += events.map { it.copy(id = "$tab:${it.id}") }
        }
        store.update { current -> current.copy(gongdu = current.gongdu.copy(inbox = GongduCodec.mergeInbox(
            current.gongdu.inbox, incoming, System.currentTimeMillis(),
        ))) }
        store.settingsFlowRaw.first().gongdu.inbox
    }

    suspend fun acknowledge(id: String) {
        store.update { current -> current.copy(gongdu = current.gongdu.copy(inbox = current.gongdu.inbox.copy(
            events = current.gongdu.inbox.events.map { if (it.id == id) it.copy(acknowledged = true) else it },
        ))) }
    }

    companion object {
        val PUBLIC_ACTIONS = setOf("publish_content", "publish_with_video", "post_comment_to_feed", "reply_comment_in_feed",
            "reply_notification", "like_feed", "favorite_feed", "like_notification")
        fun initialApproval(managedServer: Uuid?, server: Uuid, name: String): Boolean = managedServer == server && name in PUBLIC_ACTIONS
        fun validateEndpoint(value: String): String {
            val url = value.trim().toHttpUrlOrNull() ?: error("请输入完整的 HTTP 或 HTTPS 服务地址")
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "请将访问令牌填在单独的令牌栏" }
            return url.toString().trimEnd('/')
        }
    }
}
