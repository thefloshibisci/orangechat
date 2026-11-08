package me.rerere.rikkahub.data.gongdu

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.Modality
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

fun gongduTools(settings: Settings, assistantId: Uuid,
    loadSettings: suspend () -> Settings = { GlobalContext.get().get<SettingsStore>().settingsFlowRaw.first() },
): List<Tool> {
    val server = settings.gongdu.serverId ?: return emptyList()
    if (settings.assistants.none { it.id == assistantId && server in it.mcpServers }) return emptyList()
    if (settings.mcpServers.none { it.id == server && it.commonOptions.enable }) return emptyList()
    val model = settings.getChatModelForAssistant(assistantId)
    val resultTools = gongduResultTools(settings, assistantId, loadSettings) + gongduLinkTool(server, assistantId)
    if (model == null || Modality.IMAGE !in model.inputModalities) return resultTools
    val provider = model.findProvider(settings.providers)
    return resultTools + listOf(Tool(
        name = "gongdu_read_video",
        description = "读取共度小红书视频：全片概览按时长采样，最多24帧带时间拼图，含可用字幕。" +
            "可以传start_seconds/end_seconds补看最多30秒片段，frame_interval_seconds调整0.5～5秒间隔。" +
            "声音按当前能力提供原声音轨或ASR对白转写，结果会注明；只有文字时不能判断语气、音乐和音效。" +
            "需feed_id和xsec_token。最多3分钟/40MB。概览不代表完整连续观看，必要时分段补看。",
        parameters = { InputSchema.Obj(properties = buildJsonObject {
            put("feed_id", buildJsonObject { put("type", "string") })
            put("xsec_token", buildJsonObject { put("type", "string") })
            put("start_seconds", buildJsonObject { put("type", "number"); put("minimum", 0); put("description", "补看起点，单位秒；省略范围时返回全片概览") })
            put("end_seconds", buildJsonObject { put("type", "number"); put("minimum", 0); put("description", "补看终点，单次范围最多30秒") })
            put("frame_interval_seconds", buildJsonObject { put("type", "number"); put("minimum", 0.5); put("maximum", 5) })
        }, required = listOf("feed_id", "xsec_token")) },
        execute = { args ->
            val koin = GlobalContext.get()
            val live = loadSettings()
            check(live.gongdu.serverId == server && live.assistants.any { it.id == assistantId && server in it.mcpServers } &&
                live.mcpServers.any { it.id == server && it.commonOptions.enable }) {
                "此助手的小红书工具已关闭"
            }
            val obj = args as? JsonObject ?: error("参数必须是对象")
            val request = gongduVideoRequest(obj)
            val feed = GongduFeed(obj.str("feed_id"), "视频笔记", obj.str("xsec_token"))
            attachmentReferenceGuard.beginWrite().use {
                val note = koin.get<GongduRepository>().detail(feed)
                koin.get<GongduVideoReader>().read(note, model, provider, request)
            }
        },
    ))
}

private fun gongduLinkTool(server: Uuid, assistantId: Uuid): Tool = Tool(
    name = "gongdu_read_link",
    description = "直接读取小红书分享链接或整段分享文字，自动解析短链跳转和笔记标识后读取详情摘要。" +
        "不需要工作区、wget或手工解析令牌；不评论、不关注。只支持小红书官方分享域名，链接必须包含完整访问参数。",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("url", buildJsonObject { put("type", "string"); put("description", "一条完整小红书链接，或包含该链接的分享文字") })
    }, required = listOf("url")) },
    execute = { args ->
        val koin = GlobalContext.get()
        val store = koin.get<SettingsStore>()
        suspend fun checkAccess() {
            val live = store.settingsFlowRaw.first()
            check(live.gongdu.serverId == server &&
                live.assistants.any { it.id == assistantId && server in it.mcpServers } &&
                live.mcpServers.any { it.id == server && it.commonOptions.enable }) {
                "此助手的小红书工具已关闭"
            }
        }
        checkAccess()
        val obj = args as? JsonObject ?: error("参数必须是对象")
        val input = (obj["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("url必须是分享链接文字")
        val feed = GongduLinkReader().resolve(input)
        checkAccess()
        val note = koin.get<GongduRepository>().detail(feed)
        checkAccess()
        val detail = gongduLinkDetail(note)
        GongduToolResultPolicy.compact(server, assistantId, "get_feed_detail", listOf(UIMessagePart.Text(detail.toString())))
    },
)

internal fun gongduLinkDetail(note: GongduNote): JsonObject {
    val raw = GongduCodec.unwrap(note.raw) as? JsonObject ?: note.raw
    val card = raw.obj("note") ?: raw
    return buildJsonObject {
        raw.filterKeys { it != "note" }.forEach { (key, value) -> put(key, value) }
        put("note", JsonObject(card + mapOf(
            "feed_id" to JsonPrimitive(note.feed.id), "xsec_token" to JsonPrimitive(note.feed.token),
            "title" to JsonPrimitive(note.feed.title), "desc" to JsonPrimitive(note.content),
        )))
    }
}

internal fun gongduVideoRequest(args: JsonObject): GongduVideoRequest {
    fun seconds(name: String): Double? {
        val value = args[name] ?: return null
        require(value is JsonPrimitive && !value.isString) { "$name 必须是数字" }
        return requireNotNull(value.doubleOrNull?.takeIf(Double::isFinite)) { "$name 必须是有限数字" }
    }
    return GongduVideoRequest(seconds("start_seconds"), seconds("end_seconds"), seconds("frame_interval_seconds") ?: 2.0)
}
