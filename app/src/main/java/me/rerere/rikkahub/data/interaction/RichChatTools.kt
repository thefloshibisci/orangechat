package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun buildRichChatTools(
    allowCalls: Boolean,
    create: suspend (kind: String, amount: String, note: String, requestId: String) -> ChatInteraction,
    respond: suspend (id: String, action: String) -> ChatInteraction,
): List<Tool> = buildList {
    val kinds = buildList {
        add("send_red_packet" to "red_packet"); add("send_transfer" to "transfer")
        if (allowCalls) add("request_voice_call" to "call")
    }
    kinds.forEach { (name, kind) ->
        add(Tool(name = name,
            description = if (kind == "call") "主动邀请用户语音通话。用户接听前不会开启麦克风；返回邀请状态，不代表已接通。不要连续来电。"
                else "发送${if (kind == "red_packet") "红包" else "转账"}聊天卡片。是否关联小钱包由用户的装个大的开关决定；结果待核实或余额不足时不得声称已发出，也不要另用记账工具重复记账。",
            parameters = { InputSchema.Obj(properties = buildJsonObject {
                put("request_id", buildJsonObject { put("type", "string"); put("description", "本次互动唯一单号；同一次重试必须沿用，不同互动用新单号") })
                if (kind != "call") put("amount", buildJsonObject { put("type", "string"); put("description", if (kind == "red_packet") "元，正数，最多两位小数，单个红包最高200元，虚拟模式也一样" else "元，正数，最多两位小数") })
                put("note", buildJsonObject { put("type", "string"); put("description", "附言，最多200字") })
            }, required = if (kind == "call") listOf("request_id") else listOf("request_id", "amount")) },
            execute = { input ->
                val values = input.jsonObject
                if (kind != "call") parseChatGiftAmount(kind, values["amount"]?.jsonPrimitive?.content.orEmpty())
                val card = create(kind, values["amount"]?.jsonPrimitive?.content.orEmpty(),
                    values["note"]?.jsonPrimitive?.content.orEmpty(), values["request_id"]?.jsonPrimitive?.content ?: error("缺少单号"))
                listOf(UIMessagePart.Text(Json.encodeToString(card)))
            }))
    }
    add(Tool(name = "respond_to_payment", description = "领取或退回用户发来的红包、转账。用卡片ID；已经处理过不会重复入账。sync只核对同一单号的未确认结果。",
        parameters = { InputSchema.Obj(properties = buildJsonObject {
            put("id", buildJsonObject { put("type", "string") })
            put("action", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("accept"); add("return"); add("sync") }) })
        }, required = listOf("id", "action")) },
        execute = { input ->
            val values = input.jsonObject
            listOf(UIMessagePart.Text(Json.encodeToString(respond(values.getValue("id").jsonPrimitive.content,
                values.getValue("action").jsonPrimitive.content))))
        }))
}
