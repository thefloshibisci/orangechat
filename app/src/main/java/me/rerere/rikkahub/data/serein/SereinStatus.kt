package me.rerere.rikkahub.data.serein

/** Only allowlisted labels are displayed; never show HTTP bodies, exception text or credentials. */
internal fun sereinFailureLabel(status: String): String = when (status) {
    "timeout", "http_408", "http_504" -> "等待超时，可选 5 秒后再试"
    "network_error" -> "网络连接失败，请检查网络和实例地址"
    "http_401", "http_403" -> "鉴权失败，请检查 Gateway Key 与实例地址"
    "http_404", "http_405" -> "接口不可用，请检查 gateway 地址和服务版本"
    "http_429" -> "服务限流，请稍后重试"
    "http_500", "http_502", "http_503" -> "服务暂不可用，请检查服务运行状态"
    "invalid_response" -> "服务响应格式不匹配，请检查地址和服务版本"
    "invalid_config" -> "连接配置不完整，请检查地址和 Gateway Key"
    "message_too_large", "http_413" -> "单条消息过大，未上传"
    "server_rejected" -> "服务未接收此消息，请检查服务版本"
    "source_changed" -> "消息或分支已更改，未上传"
    else -> "未成功，请检查配置或稍后重试"
}

internal fun sereinRecallLabel(status: SereinRecallStatus?): String = when (status?.status) {
    null -> "还没有本机召回记录；开启后向当前助手发送一条新消息。"
    "ready" -> "找到 ${runCatching { decodeIds(status.memoryIds).size }.getOrDefault(0)} 条候选记忆（不代表模型已使用）"
    "empty" -> "请求成功，未命中可用记忆；请检查云端记忆、检索模型和索引。"
    else -> sereinFailureLabel(status.status)
}

internal fun sereinConnectionLabel(result: HookRecall?, elapsedMs: Long): String =
    if (result?.status in setOf("ready", "empty")) "连接成功 · ${elapsedMs}ms（不展示记忆正文）"
    else "连接未成功 · ${elapsedMs}ms · ${sereinFailureLabel(result?.status ?: "timeout")}"
