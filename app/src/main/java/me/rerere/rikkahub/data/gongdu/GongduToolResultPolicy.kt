package me.rerere.rikkahub.data.gongdu

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Model-facing projections only. UI/video readers must continue to receive the original MCP data. */
object GongduToolResultPolicy {
    const val RESULT_LIMIT = 10000
    const val HISTORY_LIMIT = 24000
    private const val MARKER = "_gongdu_compact"
    private const val PAGE_SIZE = 8
    private const val MAX_SNAPSHOT_ITEMS = 300
    private const val MAX_SNAPSHOT_CHARS = 180000
    private const val MAX_CACHE_CHARS = 720000
    private const val TTL_MS = 30 * 60 * 1000L
    private data class Snapshot(val server: Uuid, val assistant: Uuid, val tool: String,
        val items: List<JsonObject>, val sourceTotal: Int, val time: Long, val chars: Int)
    private val snapshots = linkedMapOf<String, Snapshot>()
    private val protectedKeys = setOf("id", "feed_id", "noteId", "note_id", "user_id", "userId",
        "comment_id", "notification_id", "target_comment_id", "xsec_token", "xsecToken", "feed_xsec_token")
    private val usefulKeys = protectedKeys + setOf("title", "displayTitle", "display_title", "desc", "content", "text",
        "comment_text", "type", "nickname", "nickName", "name", "user", "from", "to", "target",
        "likedCount", "liked_count", "collectedCount", "commentCount", "interactInfo", "interact_info",
        "count", "total", "hasMore", "has_more", "cursor", "success", "error", "code", "message",
        "status", "following", "followed", "is_following", "is_followed", "relationship", "tab",
        "submission_state", "submitted_unconfirmed", "retry_safe", "not_submitted",
        "time", "createTime", "create_time", "tags", "tagList", "comments", "sub_comments",
        "userPageData", "basicInfo", "interactions", "note", "noteCard", "note_card", "data")
    private val listKeys = listOf("feeds", "items", "list", "notes", "notifications")

    fun compact(serverId: Uuid, assistantId: Uuid, toolName: String, parts: List<UIMessagePart>): List<UIMessagePart> {
        var remaining = RESULT_LIMIT
        return parts.map { part ->
            if (part !is UIMessagePart.Text) part else {
                val value = compactText(serverId, assistantId, toolName, part.text)
                val text = if (value.length <= remaining) value else
                    "[共度返回已达到本次文字预算；未完整提供，请缩小查询范围。原始返回不等于操作成功。]".take(remaining)
                remaining = (remaining - text.length).coerceAtLeast(0)
                part.copy(text = text)
            }
        }
    }

    private fun compactText(server: Uuid, assistant: Uuid, tool: String, text: String): String {
        if (tool in setOf("post_comment_to_feed", "reply_comment_in_feed", "reply_notification")) {
            val receipt = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (receipt?.get("_gongdu_write_receipt") == JsonPrimitive(1)) return text
            val state = receipt?.str("status", "submission_state").orEmpty()
            val status = when {
                state == "visible_to_current_account" -> state
                state == "not_submitted" || text.contains("[not_submitted]") -> "not_submitted"
                else -> "submitted_unconfirmed"
            }
            return buildJsonObject {
                put("_gongdu_write_receipt", 1); put("tool", tool); put("status", status)
                put("retry_safe", false); put("public_visibility", "unverified")
                put("notice", if (status == "visible_to_current_account")
                    "仅确认刷新后当前账号可见，未核实其他账号可见；不要重复提交。"
                    else "没有可靠的公开发布确认；可能已经提交，不要自动重试，也不要改发顶层评论。")
                put("receipt", boundedText(text, 2600))
            }.toString()
        }
        if (text.length > 2_000_000) return "[共度原始返回超过处理上限，本次未提供内容；请缩小查询范围。不能据此判定公开操作成功或失败，不要自动重试。]"
        val parsed = runCatching { GongduCodec.payload(text) }.getOrNull() ?: return boundedText(text, 2200)
        if (parsed is JsonObject && parsed[MARKER] == JsonPrimitive(1)) return text
        val unwrapped = GongduCodec.unwrap(parsed)
        val obj = unwrapped as? JsonObject
        // Do not turn business failures into an apparently successful list.
        if ((parsed as? JsonObject)?.get("success") == JsonPrimitive(false) ||
            (parsed as? JsonObject)?.get("error") != null || obj?.get("error") != null ||
            obj?.get("success") == JsonPrimitive(false)) {
            return boundedText(text, 2200)
        }
        // A write receipt (including a serialized MCP content array) must never
        // enter list paging: dropping it can hide an uncertain public action.
        if (tool !in setOf("list_feeds", "search_feeds", "list_notifications") &&
            tool !in setOf("get_feed_detail", "user_profile", "get_my_profile")) {
            return boundedText(text, 3500)
        }
        val list = unwrapped as? JsonArray ?: listKeys.firstNotNullOfOrNull { obj?.get(it) as? JsonArray }
        if (list != null) {
            var chars = 0
            val items = list.take(MAX_SNAPSHOT_ITEMS).mapNotNull { row ->
                val source = row as? JsonObject ?: return@mapNotNull null
                if (!validReferences(source)) return@mapNotNull null
                val projected = projectItem(source, tool)
                val size = projected.toString().length
                if (size > RESULT_LIMIT - 1200 || chars + size > MAX_SNAPSHOT_CHARS) null else {
                    chars += size
                    projected
                }
            }
            val ref = remember(server, assistant, tool, items, list.size)
            return page(server, assistant, ref, 0, PAGE_SIZE)
        }
        if (tool == "get_feed_detail" || tool == "user_profile" || tool == "get_my_profile") {
            val data = project(unwrapped, 0)
            return buildJsonObject {
                put(MARKER, 1); put("tool", tool); put("data", data)
                put("notice", "仅提供正文与必要标识；媒体编码、追踪和大批评论已省略。视频请按需用gongdu_read_video读取。")
            }.toString().let { summary -> if (summary.length <= RESULT_LIMIT) summary else buildJsonObject {
                put(MARKER, 1); put("tool", tool); put("data", minimalDetail(unwrapped))
                put("truncated", true); put("notice", "正文摘要仍过长；仅提供预览，请缩小查询范围。")
            }.toString() }
        }
        return boundedText(text, 3500)
    }

    private fun validReferences(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.all { (key, item) ->
            !(key in protectedKeys && item is JsonPrimitive && item.content.length > 2048) && validReferences(item)
        }
        is JsonArray -> value.all(::validReferences)
        else -> true
    }

    private fun projectItem(row: JsonObject, tool: String): JsonObject {
        val card = row.obj("noteCard") ?: row.obj("note_card") ?: row
        val merged = JsonObject(card + row.filterKeys { it != "noteCard" && it != "note_card" })
        return buildJsonObject {
            if (tool in setOf("list_feeds", "search_feeds")) {
                val feed = GongduCodec.feed(row)
                if (feed.id.isNotBlank()) put("feed_id", feed.id)
                if (feed.token.isNotBlank()) put("xsec_token", feed.token)
            }
            (project(merged, 0) as JsonObject).forEach { (key, value) -> put(key, value) }
        }
    }

    private fun project(value: JsonElement, depth: Int, key: String = ""): JsonElement = when {
        depth > 5 -> JsonPrimitive("[嵌套内容已省略]")
        value is JsonObject -> buildJsonObject {
            value.entries.filter { it.key in usefulKeys &&
                !(it.key in protectedKeys && it.value is JsonPrimitive && (it.value as JsonPrimitive).content.length > 2048) }
                .sortedBy { if (it.key in protectedKeys) 0 else 1 }.take(26).forEach { (name, child) ->
                put(name, project(child, depth + 1, name))
            }
            if (value["video"] != null) put("video_available", true)
            if (value["imageList"] is JsonArray) put("image_count", (value["imageList"] as JsonArray).size)
        }
        value is JsonArray -> JsonArray(value.take(if (key == "comments" || key == "sub_comments") 3 else 6)
            .map { project(it, depth + 1, key) })
        value is JsonPrimitive && value.isString && key !in protectedKeys ->
            JsonPrimitive(boundedText(value.content, if (key in setOf("desc", "content", "text")) 2400 else 240))
        else -> value
    }

    private fun minimalDetail(value: JsonElement): JsonObject {
        val root = value as? JsonObject ?: return buildJsonObject { put("notice", "返回格式未识别") }
        val note = root.obj("note") ?: root
        var references = 0
        return buildJsonObject {
            protectedKeys.forEach { key ->
                val item = note[key] as? JsonPrimitive ?: root[key] as? JsonPrimitive
                if (item != null && item.content.length <= 2048 && references + item.content.length <= 3200) {
                    put(key, item); references += item.content.length
                }
            }
            put("title", note.str("title", "displayTitle").take(240))
            put("text_preview", boundedText(note.str("desc", "content", "text"), 1800))
            put("references_or_fields_omitted", true)
            put("video_available", note["video"] != null)
        }
    }

    private fun boundedText(text: String, limit: Int): String = if (text.length <= limit) text else
        text.take(limit - 32) + "\n[已限长；未提供完整内容]"

    @Synchronized private fun remember(server: Uuid, assistant: Uuid, tool: String, items: List<JsonObject>, total: Int): String {
        expire()
        val payload = JsonArray(items).toString()
        val ref = MessageDigest.getInstance("SHA-256").digest(("$server/$assistant/$tool/$total/" + payload).toByteArray())
            .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
        snapshots[ref] = Snapshot(server, assistant, tool, items, total, System.currentTimeMillis(), payload.length)
        while (snapshots.size > 8 || snapshots.values.sumOf { it.chars } > MAX_CACHE_CHARS) {
            snapshots.remove(snapshots.keys.first())
        }
        return ref
    }

    @Synchronized fun page(server: Uuid, assistant: Uuid, ref: String, offset: Int, limit: Int): String {
        require(offset >= 0 && limit in 1..12) { "offset应非负，limit应为1～12" }
        expire()
        val snapshot = snapshots[ref]?.takeIf { it.server == server && it.assistant == assistant }
            ?: return "[共度结果缓存已过期或无权限，请重新查询；没有重新调用平台或重复任何公开操作。]"
        val selected = mutableListOf<JsonObject>()
        var chars = 0
        for (item in snapshot.items.drop(offset).take(limit)) {
            if (chars + item.toString().length > RESULT_LIMIT - 1200) break
            selected += item; chars += item.toString().length
        }
        val next = offset + selected.size
        return buildJsonObject {
            put(MARKER, 1); put("tool", snapshot.tool); put("result_ref", ref)
            put("source_total", snapshot.sourceTotal); put("cached_total", snapshot.items.size)
            put("offset", offset); put("returned", selected.size); put("items", JsonArray(selected))
            put("has_more", next < snapshot.items.size)
            if (next < snapshot.items.size) put("next_offset", next)
            put("omitted", (snapshot.sourceTotal - snapshot.items.size).coerceAtLeast(0))
            put("notice", "这是同次返回的本地摘要快照，非平台分页。用gongdu_result_page及result_ref/next_offset按需读取其余条目；缓存30分钟。详情用feed_id和完整xsec_token查询。")
        }.toString()
    }

    private fun expire() { snapshots.entries.removeAll { System.currentTimeMillis() - it.value.time > TTL_MS } }
}

/** Old stored tool results are compacted on request copies, including nested outputs. */
object GongduToolResultTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val server = ctx.settings.gongdu.serverId ?: return messages
        var remaining = GongduToolResultPolicy.HISTORY_LIMIT
        val prefix = ToolNaming.buildMcpToolName(server, "")
        fun adapt(part: UIMessagePart): UIMessagePart {
            if (part !is UIMessagePart.Tool) return part
            val nested = part.output.map(::adapt)
            val managed = part.toolName.startsWith(prefix) || part.toolName in
                setOf("gongdu_result_page", "gongdu_read_link", "gongdu_read_video")
            if (!managed) return part.copy(output = nested)
            val name = if (part.toolName.startsWith(prefix)) part.toolName.removePrefix(prefix) else part.toolName
            val compact = GongduToolResultPolicy.compact(server, ctx.assistant.id, name, nested)
            val chars = compact.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
            val output = if (chars <= remaining) {
                remaining -= chars
                compact
            } else compact.map { if (it is UIMessagePart.Text) {
                val notice = "[较早的共度工具文字结果已从本次请求省略以控制上下文；工具已执行，不要因此重复公开操作。需要时重新查询只读详情。]".take(remaining)
                remaining -= notice.length
                it.copy(text = notice)
            } else it }
            return part.copy(output = output)
        }
        return messages.asReversed().map { message ->
            message.copy(parts = message.parts.asReversed().map(::adapt).asReversed())
        }.asReversed()
    }
}

fun gongduResultTools(settings: Settings, assistantId: Uuid): List<Tool> {
    val server = settings.gongdu.serverId ?: return emptyList()
    if (settings.assistants.none { it.id == assistantId && server in it.mcpServers }) return emptyList()
    if (settings.mcpServers.none { it.id == server && it.commonOptions.enable }) return emptyList()
    return listOf(Tool(
        name = "gongdu_result_page",
        description = "按需读取先前共度列表摘要的下一批，使用返回的result_ref和next_offset；不访问平台、不重复评论或关注。每批最多12条，缓存30分钟；进程重启后需重新查询。",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("result_ref", buildJsonObject { put("type", "string") })
            put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
            put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 12) })
        }, required = listOf("result_ref", "offset")) },
        execute = { args ->
            val live = GlobalContext.get().get<SettingsStore>().settingsFlowRaw.first()
            check(live.gongdu.serverId == server && live.assistants.any { it.id == assistantId && server in it.mcpServers } &&
                live.mcpServers.any { it.id == server && it.commonOptions.enable }) {
                "此助手的小红书工具已关闭"
            }
            val obj = args as? JsonObject ?: error("参数必须是对象")
            fun integer(key: String, default: Int? = null): Int {
                val value = obj[key] ?: return requireNotNull(default) { "缺少$key" }
                require(value is JsonPrimitive && !value.isString) { "${key}必须是整数" }
                return requireNotNull(value.intOrNull) { "${key}必须是整数" }
            }
            listOf(UIMessagePart.Text(GongduToolResultPolicy.page(server, assistantId, obj.str("result_ref"),
                integer("offset"), integer("limit", 8))))
        },
    ))
}
