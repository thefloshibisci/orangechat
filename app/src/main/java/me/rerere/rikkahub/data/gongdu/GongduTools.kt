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
        description = "读取共度中的小红书短视频，返回按时间排列的真实抽样画面和可用平台字幕。" +
            "需要先搜索获得feed_id和xsec_token。最多3分钟/40MB。没有原始音轨，不要声称听过声音或看过未采样动作。",
        parameters = { InputSchema.Obj(properties = buildJsonObject {
            put("feed_id", buildJsonObject { put("type", "string") })
            put("xsec_token", buildJsonObject { put("type", "string") })
        }, required = listOf("feed_id", "xsec_token")) },
        execute = { args ->
            val koin = GlobalContext.get()
            val live = koin.get<SettingsStore>().settingsFlowRaw.first()
            check(live.gongdu.serverId == server && live.assistants.any { it.id == assistantId && server in it.mcpServers }) {
                "此助手的小红书工具已关闭"
            }
            check(Modality.IMAGE in live.getChatModelForAssistant(assistantId)?.inputModalities.orEmpty()) { "当前模型未启用识图" }
            val obj = args as? JsonObject ?: error("参数必须是对象")
            val feed = GongduFeed(obj.str("feed_id"), "视频笔记", obj.str("xsec_token"))
            attachmentReferenceGuard.beginWrite().use {
                val note = koin.get<GongduRepository>().detail(feed)
                koin.get<GongduVideoReader>().read(note)
            }
        },
    ))
}
