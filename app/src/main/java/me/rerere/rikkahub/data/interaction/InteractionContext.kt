package me.rerere.rikkahub.data.interaction

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.functionToolUnavailableReason
import me.rerere.ai.provider.FunctionToolUnavailableReason
import me.rerere.ai.ui.UIMessage

internal fun paymentModePrompt(pretendRich: Boolean): String = if (pretendRich)
    "当前“装个大的”已开启：你只能用send_virtual_red_packet/send_virtual_transfer发虚拟金额，不关联你的小钱包，不查余额、不扣款、不入账；不要调用记账工具补记。"
else
    "当前“装个大的”已关闭：你只能用send_wallet_red_packet/send_wallet_transfer发关联小钱包的红包或转账；发送前已确定为关联模式，执行结果以实际回执为准；待核实、失败或余额不足不代表发送成功，不要重复记账。"

internal fun resolvePaymentWalletMode(actor: String, pretendRich: Boolean, requested: Boolean?): Boolean {
    require(actor in setOf("user", "assistant"))
    if (actor == "assistant" && requested != null) {
        check(requested == !pretendRich) { "金额模式已切换，本次未发送；请按当前可用工具重新选择，不能把虚拟金额改作钱包扣款。" }
    }
    return requested ?: !pretendRich
}

/** Fresh state, independent of old tool receipts and the conversation's truncation window. */
internal fun interactionStatePrompt(messages: List<UIMessage>, pretendRich: Boolean, callActive: Boolean,
    paymentToolsAvailable: Boolean = true, paymentToolsUnavailableReason: String? = null,
    paymentMessageIds: Set<kotlin.uuid.Uuid>? = null): String {
    val latest = messages.flatMap { it.annotations }.filterIsInstance<ChatInteraction>()
        .filter { it.kind == "call" }.maxByOrNull { it.createdAtMillis }
    return buildString {
        appendLine("[APP当前互动状态；优先于旧工具回执，不要照抄给用户]")
        if (paymentToolsAvailable) {
            appendLine(paymentModePrompt(pretendRich))
            appendLine("本轮已提供收款工具 respond_to_payment：领取用户红包或转账调用 action=accept，退回调用 action=return，id 使用下列互动单号。虚拟金额同样用这个工具领取；只更新卡片、不操作钱包。关联金额以工具回执确认。不要仅凭旧对话推断领取接口尚未接通，也不要只口头说已收下；实际结果以回执为准，报错时如实说明。")
        } else {
            appendLine("本轮未提供红包、转账或收款工具。${paymentToolsUnavailableReason ?: "当前模型未启用工具调用，或本轮禁用了工具。"}你能读到卡片，但不能发送、领取或退回；不要声称已经操作。")
        }
        appendLine("既有红包、转账仍以各自walletLinked字段为准，开关不改变旧单。")
        appendLine("用户发送时可独立选择金额模式；不要从你的装个大的开关推断用户卡片的性质。")
        messages.filter { paymentMessageIds == null || it.id in paymentMessageIds }
            .flatMap { it.annotations }.filterIsInstance<ChatInteraction>()
            .filter { it.actor == "user" && it.kind in setOf("red_packet", "transfer") }
            .distinctBy { it.id }.filter { it.status in setOf("pending", "unconfirmed", "syncing") }
            .takeLast(8).forEach { card ->
                appendLine("用户互动：id=${card.id}；${if (card.kind == "red_packet") "红包" else "转账"}；金额=${formatChatAmount(card.amountCents)}；${if (card.walletLinked) "关联小钱包" else "虚拟金额"}；status=${card.status}。")
            }
        if (callActive) appendLine("当前本会话语音通话已接通，仍在进行。")
        else {
            appendLine("当前本会话没有接通中的语音通话；不要把旧工具回执的‘已发起’当成仍在通话。")
            latest?.let {
                val state = when (it.status) {
                    "ended" -> "已挂断，通话已结束"
                    "declined" -> "未接通，邀请已拒绝"
                    "pending" -> "等待接听，尚未接通"
                    "connecting" -> "正在连接，尚未确认接通"
                    else -> "目前未接通，以当前状态为准"
                }
                appendLine("最近一次通话：$state。")
            }
        }
    }.trim()
}

internal fun paymentToolUnavailableReason(model: Model?, provider: ProviderSetting? = null): String? = when {
    model == null -> "请先选择聊天模型；当前没有可接收红包或转账的模型"
    model.functionToolUnavailableReason(provider) == FunctionToolUnavailableReason.GOOGLE_BUILT_INS ->
        "当前 Google 普通接口的内置工具会覆盖红包工具。请关闭模型内置搜索/网址上下文，或在支持的供应商上启用 Gemini Interactions API。"
    model.functionToolUnavailableReason(provider) == FunctionToolUnavailableReason.CAPABILITY_DISABLED ->
        "当前模型 ${model.modelId} 未开启工具调用。你仍可发送卡片，但 AI 无法领取或发送；请到供应商 → 模型设置检查工具调用能力。"
    else -> null
}
