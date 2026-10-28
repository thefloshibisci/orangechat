package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.OffsetDateTime
import java.time.ZoneId

internal fun buildProactiveWakeupTool(
    now: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
    update: suspend (timeMillis: Long?, reason: String) -> String,
): Tool = Tool(
    name = "schedule_proactive_wakeup",
    description = "为当前会话约定下次主动联系时间，或取消约定。每个会话只保留一个待执行约定，新约定替换旧约定。" +
        "到点读取最新聊天再判断回复，不是预写或定时发送固定文本；遵守主动消息总开关、免打扰和连续追问上限。" +
        "有约定时等待约定时间，无约定时按原间隔检查。仅工具返回成功后才能说已约定。" +
        "时间须为带时区的 ISO-8601 格式，例如 2026-10-29T20:30:00+08:00。" +
        "当前时间：${java.time.Instant.ofEpochMilli(now()).atZone(zone)}。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "schedule 约定或替换；cancel 取消当前会话约定")
                    put("enum", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("schedule"), JsonPrimitive("cancel"))))
                })
                put("time", buildJsonObject {
                    put("type", "string")
                    put("description", "schedule 时必填，未来的具体日期和时间，必须带 UTC 偏移")
                })
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "schedule 时必填，简短说明到点想联系的缘由，最多240字，不是待发送正文")
                })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val input = args as? JsonObject ?: error("Expected an object")
        fun string(key: String) = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val result = when (string("action")) {
            "cancel" -> update(null, "")
            "schedule" -> {
                val time = requireNotNull(string("time")) { "time is required with a timezone offset" }
                val millis = OffsetDateTime.parse(time).toInstant().toEpochMilli()
                require(millis > now()) { "Appointment time must be in the future" }
                val reason = string("reason")?.trim().orEmpty()
                require(reason.isNotBlank() && reason.length <= 240) { "reason must contain 1 to 240 characters" }
                update(millis, reason)
            }
            else -> error("action must be schedule or cancel")
        }
        listOf(UIMessagePart.Text(result))
    },
)
