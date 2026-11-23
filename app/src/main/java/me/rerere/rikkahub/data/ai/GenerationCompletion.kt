package me.rerere.rikkahub.data.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

private const val COMPLETION_KEY = "orange_generation_completed_v1"

/** Proof belongs to the final model turn, not an intermediate executed tool batch. */
private fun UIMessage.completionFingerprint(): String {
    val source = buildJsonArray {
        add(id.toString()); add(role.name); add(finishedAt?.toString().orEmpty())
        parts.filterIsInstance<UIMessagePart.Text>().forEach { add(it.text) }
        getTools().forEach { tool ->
            add(tool.toolCallId); add(tool.toolName); add(tool.input)
            add(Json.encodeToString(tool.output)); add(tool.executionCheckpointState.orEmpty())
        }
    }.toString()
    return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun UIMessage.withoutGenerationCompletion(): UIMessage = copy(parts = parts.map { part ->
    if (part is UIMessagePart.Text && part.metadata?.containsKey(COMPLETION_KEY) == true) {
        part.copy(metadata = JsonObject(part.metadata!!.filterKeys { it != COMPLETION_KEY }))
    } else part
})

internal fun UIMessage.withGenerationCompletion(): UIMessage {
    val clean = withoutGenerationCompletion()
    if (role != MessageRole.ASSISTANT || finishedAt == null || getTools().any { !it.isExecuted }) return clean
    val at = clean.parts.indexOfFirst { it is UIMessagePart.Text && it.text.isNotBlank() }
    if (at < 0) return clean
    val fingerprint = clean.completionFingerprint()
    return clean.copy(parts = clean.parts.mapIndexed { index, part ->
        if (index != at || part !is UIMessagePart.Text) part else part.copy(metadata = buildJsonObject {
            part.metadata?.forEach { (key, value) -> put(key, value) }
            put(COMPLETION_KEY, fingerprint)
        })
    })
}

internal fun UIMessage.hasCompletedGeneration(): Boolean {
    if (role != MessageRole.ASSISTANT || finishedAt == null || getTools().any { !it.isExecuted }) return false
    if (getTools().isEmpty()) return true // Preserve existing tool-free completion semantics.
    val proof = parts.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull {
        (it.metadata?.get(COMPLETION_KEY) as? JsonPrimitive)?.contentOrNull
    } ?: return false
    return proof == completionFingerprint()
}
