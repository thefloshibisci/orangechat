package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.*

/** Only an identity-checked RPC receipt can advance a persisted payment card. */
internal fun ChatInteraction.applyWalletReceipt(receipt: JsonObject, operation: String): ChatInteraction {
    fun text(key: String) = (receipt[key] as? JsonPrimitive)?.contentOrNull
    fun unknown(reason: String) = copy(status = "unconfirmed", pendingAction = pendingAction ?: operation, error = reason)
    if ((receipt["success"] as? JsonPrimitive)?.booleanOrNull != true) {
        val rejected = operation == "create" && text("code") in setOf("insufficient_balance", "red_packet_limit")
        return if (rejected) copy(status = "failed", pendingAction = operation, error = text("error"))
        else unknown(text("error") ?: when (receipt["success"]) {
            null, JsonNull -> "钱包回执缺少 success；请核对 RPC 返回结构"
            JsonPrimitive(false) -> "钱包回执 success=false（code=${text("code") ?: "未提供"}）"
            else -> "钱包回执 success 类型或值不符合协议"
        })
    }
    val expected = mapOf("payment_id" to id, "scope" to "$assistantId:$conversationId",
        "kind" to kind, "direction" to if (actor == "assistant") "expense" else "income")
    for ((key, value) in expected) {
        if (text(key) != value) return unknown("钱包回执 $key 缺失或不匹配")
    }
    if ((receipt["amount_cents"] as? JsonPrimitive)?.longOrNull != amountCents) {
        return unknown("钱包回执 amount_cents 缺失或不匹配")
    }
    val state = text("status")
    if (state !in setOf("pending", "accepted", "returned")) return unknown("钱包回执 status 无法识别")
    return copy(status = state!!, pendingAction = null, error = null)
}

internal fun chatWalletOperation(action: String): String = if (action == "sync") "get" else action
