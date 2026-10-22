package me.rerere.rikkahub.plugin.webview

import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject

/** Encode both arguments as data; filenames, errors and callback IDs cannot become script. */
internal fun pluginBridgeResultJavascript(callbackId: String?, result: Any?): String =
    pluginBridgeJsonJavascript(callbackId, JSONObject.wrap(result)?.toString() ?: "null")

internal fun pluginBridgeJsonJavascript(callbackId: String?, json: String): String {
    return "window.__bridgeResult(${callbackId?.let(::pluginJsonStringLiteral) ?: "null"}, JSON.parse(${pluginJsonStringLiteral(json)}));"
}

internal fun pluginHookJavascript(functionName: String, contextJson: String): String? {
    if (!functionName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) return null
    return "try { const hook = window[${pluginJsonStringLiteral(functionName)}]; if (typeof hook === 'function') hook(${pluginJsonStringLiteral(contextJson)}); } catch(e) { console.error('Hook JS error:', e); }"
}

internal fun pluginJsonStringLiteral(value: String): String = JsonPrimitive(value).toString()
    .replace("\u2028", "\\u2028")
    .replace("\u2029", "\\u2029")
