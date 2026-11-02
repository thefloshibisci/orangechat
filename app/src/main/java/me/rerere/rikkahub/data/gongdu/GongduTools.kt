package me.rerere.rikkahub.data.gongdu

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.Modality
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

fun gongduTools(settings: Settings, assistantId: Uuid): List<Tool> {
    val server = settings.gongdu.serverId ?: return emptyList()
    if (settings.assistants.none { it.id == assistantId && server in it.mcpServers } ||
        Modality.IMAGE !in settings.getChatModelForAssistant(assistantId)?.inputModalities.orEmpty()) return emptyList()
    return listOf(Tool(
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
            val live = koin.get<SettingsStore>().settingsFlowRaw.first()
            check(live.gongdu.serverId == server && live.assistants.any { it.id == assistantId && server in it.mcpServers }) {
                "此助手的小红书工具已关闭"
            }
            check(Modality.IMAGE in live.getChatModelForAssistant(assistantId)?.inputModalities.orEmpty()) { "当前模型未启用识图" }
            val obj = args as? JsonObject ?: error("参数必须是对象")
            val request = gongduVideoRequest(obj)
            val feed = GongduFeed(obj.str("feed_id"), "视频笔记", obj.str("xsec_token"))
            attachmentReferenceGuard.beginWrite().use {
                val note = koin.get<GongduRepository>().detail(feed)
                koin.get<GongduVideoReader>().read(note, assistantId, request)
            }
        },
    ))
}

internal fun gongduVideoRequest(args: JsonObject): GongduVideoRequest {
    fun seconds(name: String): Double? {
        val value = args[name] ?: return null
        require(value is JsonPrimitive && !value.isString) { "$name 必须是数字" }
        return requireNotNull(value.doubleOrNull?.takeIf(Double::isFinite)) { "$name 必须是有限数字" }
    }
    return GongduVideoRequest(seconds("start_seconds"), seconds("end_seconds"), seconds("frame_interval_seconds") ?: 2.0)
}
