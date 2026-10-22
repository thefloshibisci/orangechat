package me.rerere.rikkahub.ui.pages.miniapp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Bridge identifiers and results are data, including quotes, newlines and backslashes.
internal fun miniAppBridgeResult(callbackId: String, result: JsonElement): String =
    "window.__bridgeResult(${JsonPrimitive(callbackId)}, $result);"
