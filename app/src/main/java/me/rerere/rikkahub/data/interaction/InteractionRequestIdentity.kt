package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import java.util.UUID
import kotlin.uuid.Uuid

/** The persisted reply identity separates regenerations while keeping retries idempotent.
 * Do not include amount/note in the key: changing a sent payment must still fail validation. */
internal fun Tool.withInteractionRequestScope(replyId: Uuid): Tool {
    if (name !in setOf("send_virtual_red_packet", "send_virtual_transfer",
            "send_wallet_red_packet", "send_wallet_transfer", "request_voice_call")) return this
    return copy(execute = { input ->
        val values = input.jsonObject
        val requestId = values["request_id"]?.jsonPrimitive?.content ?: error("缺少单号")
        require(requestId.isNotBlank() && requestId.length <= 128) { "互动单号须为1至128字" }
        val scopedId = UUID.nameUUIDFromBytes("reply:$replyId:$requestId".toByteArray(Charsets.UTF_8)).toString()
        execute(JsonObject(values + ("request_id" to JsonPrimitive(scopedId))))
    })
}
