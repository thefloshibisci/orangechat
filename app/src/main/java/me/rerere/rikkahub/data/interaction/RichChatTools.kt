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
    pretendRich: Boolean = false,
): List<Tool> = buildList {
    val kinds = buildList {
        val mode = if (pretendRich) "virtual" else "wallet"
        add("send_${mode}_red_packet" to "red_packet"); add("send_${mode}_transfer" to "transfer")
        if (allowCalls) add("request_voice_call" to "call")
    }
    kinds.forEach { (name, kind) ->
        add(Tool(name = name,
            description = if (kind == "call") "主动邀请用户语音通话。用户接听前不会开启麦克风；返回邀请状态，不代表已接通。不要连续来电。"
                else "【${if (pretendRich) "虚拟金额，不关联钱包" else "关联小钱包"}】发送${if (kind == "red_packet") "红包" else "转账"}聊天卡片。${paymentModePrompt(pretendRich)}卡片已由APP显示，回复只写自然对话，不复述[AI发起红包]等内部记录、互动单号或工具JSON。",
            parameters = { InputSchema.Obj(properties = buildJsonObject {
                put("request_id", buildJsonObject { put("type", "string"); put("description", "当前回复内的互动单号，1至128字；同一笔重试沿用原单号和原金额、附言，不同互动使用新单号。APP会自动区分不同回复和重新生成的版本。") })
                if (kind != "call") put("amount", buildJsonObject { put("type", "string"); put("description", if (kind == "red_packet") "元，正数，最多两位小数，单个红包最高200元，虚拟模式也一样" else "元，正数，最多两位小数") })
                put("note", buildJsonObject { put("type", "string"); put("description", "附言，最多200字") })
            }, required = if (kind == "call") listOf("request_id") else listOf("request_id", "amount")) },
            execute = { input ->
                val values = input.jsonObject
                if (kind != "call") parseChatGiftAmount(kind, values["amount"]?.jsonPrimitive?.content.orEmpty())
                val card = create(kind, values["amount"]?.jsonPrimitive?.content.orEmpty(),
                    values["note"]?.jsonPrimitive?.content.orEmpty(), values["request_id"]?.jsonPrimitive?.content ?: error("缺少单号"))
                listOf(UIMessagePart.Text(chatInteractionToolReceipt(card)))
            }))
    }
    add(Tool(name = "respond_to_payment", description = "领取或退回用户发来的红包、转账。用卡片ID；已经处理过不会重复入账。sync只核对同一单号的未确认结果。",
        parameters = { InputSchema.Obj(properties = buildJsonObject {
            put("id", buildJsonObject { put("type", "string") })
            put("action", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("accept"); add("return"); add("sync") }) })
        }, required = listOf("id", "action")) },
        execute = { input ->
            val values = input.jsonObject
            listOf(UIMessagePart.Text(chatInteractionToolReceipt(respond(values.getValue("id").jsonPrimitive.content,
                values.getValue("action").jsonPrimitive.content))))
        }))
}

internal fun chatInteractionToolReceipt(card: ChatInteraction): String = buildJsonObject {
    Json.encodeToJsonElement(card).jsonObject.forEach { (key, value) -> put(key, value) }
    put("status", card.status)
    put("walletLinked", card.walletLinked)
    if (card.kind != "call") {
        put("wallet_mode", if (card.walletLinked) "linked" else "virtual")
        put("wallet_notice", if (card.walletLinked) "本单关联小钱包；以status和error为准，未确认不能视作到账。"
            else "本单为虚拟金额，不关联小钱包，没有余额变化，也不要另行记账。")
    }
}.toString()
